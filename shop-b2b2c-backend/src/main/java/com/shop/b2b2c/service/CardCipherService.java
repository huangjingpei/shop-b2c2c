/*
 * 文件：CardCipherService.java
 * 用途：虚拟卡密与高密资产分级可逆加密、解密及SHA-256去重指纹服务 (AES-256-GCM)。
 * 维护注意：库与备份不出现直接可用卡密，使用AES/GCM/NoPadding加密与12字节随机IV；旧数据无ENC:v1:前缀时透明兼容回退。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class CardCipherService {

    public static final String PREFIX_V1 = "ENC:v1:";
    private static final int GCM_IV_LENGTH = 12; // 96-bit standard nonce for GCM
    private static final int GCM_TAG_LENGTH = 128; // 128-bit authentication tag

    private final byte[] secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public CardCipherService(@Value("${shop.security.card-secret-key:}") String configuredKey) {
        this.secretKey = resolveKey(configuredKey);
    }

    public CardCipherService() {
        this(null);
    }

    private byte[] resolveKey(String configuredKey) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            byte[] bytes = configuredKey.trim().getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 32) {
                return bytes;
            }
            try {
                return MessageDigest.getInstance("SHA-256").digest(bytes);
            } catch (Exception e) {
                throw new IllegalStateException("无法初始化卡密加密密钥", e);
            }
        }
        String envKey = System.getenv("CARD_ENCRYPTION_KEY");
        if (envKey != null && !envKey.isBlank()) {
            byte[] bytes = envKey.trim().getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 32) {
                return bytes;
            }
            try {
                return MessageDigest.getInstance("SHA-256").digest(bytes);
            } catch (Exception e) {
                throw new IllegalStateException("无法初始化环境变量卡密加密密钥", e);
            }
        }
        // 开发与测试环境默认密钥种子
        try {
            return MessageDigest.getInstance("SHA-256").digest("shopizer-b2b2c-card-default-secret-seed-2026".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 加密卡密明文为 ENC:v1:<Base64(IV + Ciphertext + Tag)>
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secretKey, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);

            return PREFIX_V1 + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new BusinessException(500, "卡密安全加密失败: " + e.getMessage());
        }
    }

    /**
     * 解密卡密。若文本为旧版本存量明文（无 ENC:v1: 前缀），则透明兼容返回原文本。
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return ciphertext;
        }
        if (!ciphertext.startsWith(PREFIX_V1)) {
            return ciphertext;
        }
        try {
            String payload = ciphertext.substring(PREFIX_V1.length());
            byte[] combined = Base64.getDecoder().decode(payload);
            if (combined.length < GCM_IV_LENGTH + 16) {
                throw new BusinessException(500, "卡密密文载荷损坏");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

            byte[] cipherBytes = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, cipherBytes, 0, cipherBytes.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secretKey, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plainBytes = cipher.doFinal(cipherBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(500, "卡密解密失败或密钥不匹配");
        }
    }

    /**
     * 计算卡密 SHA-256 去重指纹（不含任何明文信息，用于全局快速检索与唯一索引校验）
     */
    public String fingerprint(String content) {
        if (content == null) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
