/*
 * 文件：CaptchaService.java
 * 用途：登录图形验证码的生成、PNG 渲染与一次性校验（内存 TTL，无第三方依赖）。
 * 维护注意：
 * 1. 验证码保存在单实例内存中，多实例/集群部署时必须改为 Redis 共享存储，否则跨节点校验必然失败；本阶段仅适用于单实例或粘性会话。
 * 2. 验证码一次性使用：无论校验成功还是失败都立即删除，失败后必须重新获取，避免被反复试错。
 * 3. shop.auth.captcha-required 默认关闭仅用于开发与既有自动化用例；生产必须通过环境变量置为 true，前端两个登录页始终提交验证码。
 * 4. 图形由 JDK 离屏绘制，不读取系统字体文件；仓库不得记录验证码明文，也不得在日志输出 captchaId 与答案。
 * 5. 内存条目有上限，超出时拒绝签发新验证码而不是无界增长，防止匿名请求耗尽堆内存。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CaptchaService {

    /** 去掉易混淆字符（0/O、1/I/L），降低人工识别错误率。 */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXY23456789";
    private static final int CODE_LENGTH = 5;
    private static final int WIDTH = 140;
    private static final int HEIGHT = 48;
    /** 内存条目上限；单实例约数十 MB，超出直接拒绝签发。 */
    private static final int MAX_ENTRIES = 20000;

    private final boolean required;
    private final long ttlMillis;
    private final Map<String, Entry> codes = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public CaptchaService(@Value("${shop.auth.captcha-required:false}") boolean required,
                          @Value("${shop.auth.captcha-ttl-seconds:300}") long ttlSeconds) {
        this.required = required;
        this.ttlMillis = Math.max(60, ttlSeconds) * 1000L;
    }

    public boolean required() {
        return required;
    }

    /**
     * 签发验证码。返回 captchaId、可直接放进 img src 的 data URI 以及是否强制校验。
     */
    public Map<String, String> issue() {
        purgeExpired();
        if (codes.size() >= MAX_ENTRIES) {
            throw new BusinessException(429, "验证码请求过于频繁，请稍后再试");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        String code = randomCode();
        codes.put(id, new Entry(code, System.currentTimeMillis() + ttlMillis));
        Map<String, String> result = new HashMap<>();
        result.put("captchaId", id);
        result.put("image", "data:image/png;base64," + renderBase64(code));
        result.put("required", Boolean.toString(required));
        return result;
    }

    /**
     * 校验验证码：强制开启或调用方提交了任一验证码字段时必须通过；校验后条目立即失效。
     */
    public void verify(String captchaId, String captchaCode) {
        boolean provided = !isBlank(captchaId) || !isBlank(captchaCode);
        if (!required && !provided) {
            return;
        }
        if (isBlank(captchaId) || isBlank(captchaCode)) {
            throw new BusinessException(400, "请完成图形验证码");
        }
        Entry entry = codes.remove(captchaId.trim());
        if (entry == null || entry.expireAt <= System.currentTimeMillis()) {
            throw new BusinessException(400, "验证码错误或已过期");
        }
        if (!entry.code.equalsIgnoreCase(captchaCode.trim())) {
            throw new BusinessException(400, "验证码错误或已过期");
        }
    }

    /** 仅测试使用：返回当前答案，不对外暴露。 */
    String peekCode(String captchaId) {
        Entry entry = codes.get(captchaId);
        return entry == null ? null : entry.code;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        codes.entrySet().removeIf(item -> item.getValue().expireAt <= now);
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    private String renderBase64(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(0xF5, 0xF7, 0xFA));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);

            // 干扰线
            graphics.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < 8; i++) {
                graphics.setColor(randomColor(120, 210));
                graphics.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }
            // 干扰点
            for (int i = 0; i < 60; i++) {
                graphics.setColor(randomColor(150, 220));
                graphics.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 2, 2);
            }

            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            for (int i = 0; i < code.length(); i++) {
                graphics.setColor(randomColor(20, 110));
                int x = 12 + i * 24;
                int y = 26 + random.nextInt(12);
                double angle = (random.nextDouble() - 0.5) * 0.5;
                graphics.rotate(angle, x, y);
                graphics.drawString(String.valueOf(code.charAt(i)), x, y);
                graphics.rotate(-angle, x, y);
            }
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("验证码图片生成失败", e);
        }
    }

    private Color randomColor(int min, int max) {
        int range = max - min;
        return new Color(min + random.nextInt(range), min + random.nextInt(range), min + random.nextInt(range));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class Entry {
        private final String code;
        private final long expireAt;

        private Entry(String code, long expireAt) {
            this.code = code;
            this.expireAt = expireAt;
        }
    }
}
