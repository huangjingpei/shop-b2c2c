/*
 * 文件：PemKeySupport.java
 * 用途：真实支付渠道 RSA 密钥加载与 SHA256withRSA 签名/验签工具 (T32)。
 * 维护注意：
 *  1. 只接受 PKCS#8 私钥（BEGIN PRIVATE KEY）与 X.509 公钥（BEGIN PUBLIC KEY）；证书链/多密钥格式不支持。
 *  2. 密钥内容不得写入日志或异常消息；解析失败统一抛“渠道密钥配置无效”，不泄露原文。
 *  3. 仅用于渠道请求签名与回调验签，不参与卡密加密或用户口令处理。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.common.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class PemKeySupport {

    private PemKeySupport() {
    }

    static PrivateKey privateKey(String pem) {
        try {
            byte[] der = decodePem(pem, "PRIVATE KEY");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new BusinessException(503, "支付渠道私钥配置无效");
        }
    }

    static PublicKey publicKey(String pem) {
        try {
            byte[] der = decodePem(pem, "PUBLIC KEY");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new BusinessException(503, "支付渠道公钥配置无效");
        }
    }

    static String sign(String content, PrivateKey key) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new BusinessException(503, "支付渠道签名失败");
        }
    }

    static boolean verify(String content, String signBase64, PublicKey key) {
        if (signBase64 == null || signBase64.isBlank()) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signBase64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] decodePem(String pem, String label) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("empty pem");
        }
        String normalized = pem
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }
}
