/*
 * 文件：MerchantCardView.java
 * 用途：商家卡密列表/明文查看/导出的白名单响应对象。
 * 维护注意：列表只返回由指纹尾号派生的脱敏标识与指纹，绝不携带 cardContent；明文只允许在店长按 SKU 限量查看或经二次确认导出时通过 reveal 工厂生成，并保持 Cache-Control: no-store。脱敏只改变接口返回，不代表数据库已加密或已轮换密钥。
 */
package com.shop.b2b2c.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shop.b2b2c.entity.ProductVirtualCard;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MerchantCardView {
    private Long id;
    private Long productId;
    private Long skuId;
    private Integer status;
    private String orderSn;
    private LocalDateTime createdAt;
    private String cardFingerprint;
    /** 由指纹尾号派生的展示标识，不基于明文，避免列表逐条解密。 */
    private String maskedContent;
    /** 仅在 reveal/export 响应出现；列表响应必须为 null 且被 NON_NULL 省略。 */
    private String cardContent;

    public static MerchantCardView masked(ProductVirtualCard card) {
        MerchantCardView view = base(card);
        view.setMaskedContent(maskFingerprint(card.getCardFingerprint()));
        return view;
    }

    public static MerchantCardView reveal(ProductVirtualCard card, String plaintext) {
        MerchantCardView view = base(card);
        view.setMaskedContent(maskFingerprint(card.getCardFingerprint()));
        view.setCardContent(plaintext);
        return view;
    }

    public static String maskFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return "******";
        }
        String value = fingerprint.trim();
        return value.length() <= 4 ? "******" : "******" + value.substring(value.length() - 4);
    }

    private static MerchantCardView base(ProductVirtualCard card) {
        MerchantCardView view = new MerchantCardView();
        view.setId(card.getId());
        view.setProductId(card.getProductId());
        view.setSkuId(card.getSkuId());
        view.setStatus(card.getStatus());
        view.setOrderSn(card.getOrderSn());
        view.setCreatedAt(card.getCreatedAt());
        view.setCardFingerprint(card.getCardFingerprint());
        return view;
    }
}
