/*
 * 文件：OnboardingAttachmentService.java
 * 用途：商家入驻证件附件的校验、私有目录落盘、元数据登记与鉴权读取。
 * 维护注意：
 * 1. 文件只能落在 shop.onboarding.attachment-dir 下的随机相对路径，禁止使用原始文件名；解析下载路径时必须 normalized 后校验仍在根目录内。
 * 2. 只接受 JPG/PNG/WEBP/PDF，并且按文件魔数识别真实类型；声明 Content-Type 仅作参考，不允许仅凭后缀放行。
 * 3. 落盘失败不写元数据；元数据写入失败要尽力删除已落盘文件，避免孤儿文件。
 * 4. 私有目录必须纳入备份，并禁止由 Nginx/静态资源映射暴露；下载必须经过平台超管鉴权接口。
 * 5. 大小上限同时受 Spring multipart 与 shop.onboarding.max-attachment-bytes 约束，前者是请求级兜底。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantOnboardingAttachment;
import com.shop.b2b2c.mapper.MerchantOnboardingAttachmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OnboardingAttachmentService {

    public static final String KIND_ID_CARD_FRONT = "ID_CARD_FRONT";
    public static final String KIND_ID_CARD_BACK = "ID_CARD_BACK";
    public static final String KIND_LICENSE = "LICENSE";
    /** 个人店实人认证视频：本人出镜朗读 faceVerifyCode 指定的随机数字。 */
    public static final String KIND_FACE_VIDEO = "FACE_VIDEO";
    public static final String KIND_OTHER = "OTHER";

    private static final Set<String> ALLOWED_KINDS = Set.of(
            KIND_ID_CARD_FRONT, KIND_ID_CARD_BACK, KIND_LICENSE, KIND_FACE_VIDEO, KIND_OTHER);

    private final MerchantOnboardingAttachmentMapper attachmentMapper;

    @Value("${shop.onboarding.attachment-dir:./data/onboarding-attachments}")
    private String attachmentDir;

    @Value("${shop.onboarding.max-attachment-bytes:5242880}")
    private long maxAttachmentBytes;

    @Value("${shop.onboarding.max-video-bytes:20971520}")
    private long maxVideoBytes;

    /**
     * 保存并登记附件。调用方必须先校验申请查询凭证/审核权限。
     */
    public MerchantOnboardingAttachment store(Long applicationId, String kind, MultipartFile file) {
        if (applicationId == null) {
            throw new BusinessException(400, "入驻申请ID必填");
        }
        String normalizedKind = kind == null ? "" : kind.trim().toUpperCase();
        if (!ALLOWED_KINDS.contains(normalizedKind)) {
            throw new BusinessException(400,
                    "附件类型仅支持 ID_CARD_FRONT/ID_CARD_BACK/LICENSE/FACE_VIDEO/OTHER");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的证件文件");
        }
        long limit = KIND_FACE_VIDEO.equals(normalizedKind) ? maxVideoBytes : maxAttachmentBytes;
        if (file.getSize() > limit) {
            throw new BusinessException(400,
                    (KIND_FACE_VIDEO.equals(normalizedKind) ? "实人认证视频不得超过 " : "单个证件文件不得超过 ")
                            + (limit / 1024 / 1024) + "MB");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(400, "证件文件读取失败");
        }
        FileType type = detect(content);
        if (type == null) {
            throw new BusinessException(400, "仅支持 JPG/PNG/WEBP/PDF 证件照片或 MP4/WEBM 认证视频");
        }

        String fileKey = String.valueOf(applicationId) + "/" + UUID.randomUUID().toString().replace("-", "") + type.extension;
        Path target = safeResolve(fileKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new BusinessException(500, "证件文件保存失败");
        }

        MerchantOnboardingAttachment attachment = new MerchantOnboardingAttachment();
        attachment.setApplicationId(applicationId);
        attachment.setKind(normalizedKind);
        attachment.setFileKey(fileKey);
        attachment.setContentType(type.contentType);
        attachment.setSizeBytes((long) content.length);
        attachment.setSha256(sha256(content));
        try {
            attachmentMapper.insert(attachment);
        } catch (RuntimeException e) {
            // 元数据失败时不留孤儿文件；删除失败不掩盖原始异常。
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // 已尽力清理
            }
            throw e;
        }
        return attachment;
    }

    public Path load(MerchantOnboardingAttachment attachment) {
        Path path = safeResolve(attachment.getFileKey());
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(404, "证件文件不存在或已被移除");
        }
        return path;
    }

    public List<MerchantOnboardingAttachment> list(Long applicationId) {
        return attachmentMapper.findByApplicationId(applicationId);
    }

    public MerchantOnboardingAttachment find(Long applicationId, Long attachmentId) {
        MerchantOnboardingAttachment attachment = attachmentMapper.findByApplicationAndId(applicationId, attachmentId);
        if (attachment == null) {
            throw new BusinessException(404, "证件附件不存在");
        }
        return attachment;
    }

    public Map<String, Object> view(MerchantOnboardingAttachment attachment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", attachment.getId());
        view.put("kind", attachment.getKind());
        view.put("contentType", attachment.getContentType());
        view.put("sizeBytes", attachment.getSizeBytes());
        view.put("createdAt", attachment.getCreatedAt());
        return view;
    }

    private Path safeResolve(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            throw new BusinessException(404, "证件文件不存在或已被移除");
        }
        Path root = Paths.get(attachmentDir).toAbsolutePath().normalize();
        Path resolved = root.resolve(fileKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new BusinessException(404, "证件文件不存在或已被移除");
        }
        return resolved;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 按文件魔数识别，避免仅凭扩展名放行可执行文件或伪装图片。 */
    private FileType detect(byte[] content) {
        if (content.length < 4) return null;
        if (content[0] == (byte) 0xFF && content[1] == (byte) 0xD8 && content[2] == (byte) 0xFF) {
            return new FileType("image/jpeg", ".jpg");
        }
        if (content[0] == (byte) 0x89 && content[1] == 0x50 && content[2] == 0x4E && content[3] == 0x47) {
            return new FileType("image/png", ".png");
        }
        if (content.length >= 12 && content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F') {
            if (content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
                return new FileType("image/webp", ".webp");
            }
        }
        if (content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F') {
            return new FileType("application/pdf", ".pdf");
        }
        if (content.length >= 8 && content[4] == 'f' && content[5] == 't' && content[6] == 'y' && content[7] == 'p') {
            return new FileType("video/mp4", ".mp4");
        }
        if (content[0] == (byte) 0x1A && content[1] == 0x45 && content[2] == (byte) 0xDF && content[3] == (byte) 0xA3) {
            return new FileType("video/webm", ".webm");
        }
        return null;
    }

    private static final class FileType {
        private final String contentType;
        private final String extension;

        private FileType(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }
    }
}
