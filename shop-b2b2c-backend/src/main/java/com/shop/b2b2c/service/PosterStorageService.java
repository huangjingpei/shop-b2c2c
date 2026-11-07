/*
 * 文件：PosterStorageService.java
 * 用途：把代付海报 PNG 落到可配置目录，数据库只保存公开访问路径，替代整段 Base64 存入 trade_order.poster_url。
 * 维护注意：
 * 1. 文件名固定为分享凭证的 SHA-256（64 位小写十六进制）+ .png，不可逆且不接受调用方自定义名称，避免目录穿越与猜测他人海报。
 * 2. 读取按文件名白名单正则校验并限制在 storage-dir 内；缺失或非法名称统一 404，不回显路径。
 * 3. 写入先落临时文件再原子替换，重复生成覆盖同名文件；数据库更新失败会留下可被下次覆盖的孤儿文件，不反向回滚已提交订单。
 * 4. 目录必须纳入备份；多实例或容器部署须挂载共享目录，否则跨实例读取按 404 处理。海报仅含公开分享链接，禁止复用到卡密/证件/订单隐私。
 * 5. public-prefix 必须与网关转发路径一致，改动需同步前端 storedPoster 校验与 Nginx 路由。
 * 6. 默认目录 ./uploads/order-posters 依赖部署脚本对 uploads 的授权；若改为私有目录，公开读取须改由鉴权接口提供，不能直接暴露磁盘路径。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PosterStorageService {

    private static final Pattern FILE_NAME = Pattern.compile("[0-9a-f]{64}\\.png");

    // 默认放在部署脚本已创建并授权的 uploads 目录下（相对服务 WorkingDirectory），避免落盘到未授权的 data 目录。
    @Value("${shop.poster.storage-dir:./uploads/order-posters}")
    private String storageDir;

    @Value("${shop.poster.public-prefix:/api/v1/peer-pay/poster/image}")
    private String publicPrefix;

    /**
     * 保存海报并返回数据库应保存的公开访问路径；生成内容为空时返回 null。
     */
    public String store(String token, byte[] png) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(400, "代付凭证不能为空");
        }
        if (png == null || png.length == 0) {
            return null;
        }
        String fileName = digest(token) + ".png";
        Path root = root();
        Path target = root.resolve(fileName);
        try {
            Files.createDirectories(root);
            Path temp = Files.createTempFile(root, "poster-", ".tmp");
            try {
                Files.write(temp, png, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException notAtomic) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            log.error("代付海报落盘失败 token摘要={}: {}", fileName, e.getMessage());
            throw new BusinessException(503, "海报服务暂不可用，请稍后重试");
        }
        return publicPrefix + "/" + fileName;
    }

    /**
     * 按公开访问路径中的文件名读取海报；非法名称、越界路径与文件缺失统一 404。
     */
    public byte[] read(String fileName) {
        Path file = resolve(fileName);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new BusinessException(404, "海报不存在或已被移除");
        }
    }

    private Path resolve(String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            throw new BusinessException(404, "海报不存在或已被移除");
        }
        Path root = root();
        Path file = root.resolve(fileName).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(404, "海报不存在或已被移除");
        }
        return file;
    }

    private Path root() {
        return Paths.get(storageDir).toAbsolutePath().normalize();
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
