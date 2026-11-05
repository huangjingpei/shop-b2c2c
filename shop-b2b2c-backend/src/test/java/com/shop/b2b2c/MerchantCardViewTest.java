/*
 * 文件：MerchantCardViewTest.java
 * 用途：验证商家卡密列表脱敏只暴露指纹尾号、不泄露明文，且 reveal 才携带 cardContent。
 * 维护注意：本测试不启动 Spring；脱敏规则变更时必须同步更新 shared/cardAssets.test.mjs 的展示约定与卡片说明。
 */
package com.shop.b2b2c;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.dto.MerchantCardView;
import com.shop.b2b2c.entity.ProductVirtualCard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MerchantCardViewTest {
    private ProductVirtualCard card(String content, String fingerprint) {
        ProductVirtualCard card = new ProductVirtualCard();
        card.setId(1L); card.setSkuId(2L); card.setCardContent(content); card.setCardFingerprint(fingerprint);
        return card;
    }

    @Test void maskedViewCarriesOnlyFingerprintTailAndNeverPlaintext() {
        var view = MerchantCardView.masked(card("CARD-SECRET-8899", "abcdef1234567890"));
        assertEquals("******7890", view.getMaskedContent());
        assertNull(view.getCardContent());
        var json = new ObjectMapper().valueToTree(view);
        assertFalse(json.has("cardContent"));
        assertFalse(json.toString().contains("CARD-SECRET-8899"));
    }

    @Test void revealViewIsTheOnlyPathCarryingPlaintext() {
        var view = MerchantCardView.reveal(card("CARD-SECRET-8899", "abcdef1234567890"), "CARD-SECRET-8899");
        assertEquals("CARD-SECRET-8899", view.getCardContent());
        assertEquals("******7890", view.getMaskedContent());
    }

    @Test void missingOrShortFingerprintFallsBackToStarMask() {
        assertEquals("******", MerchantCardView.maskFingerprint(null));
        assertEquals("******", MerchantCardView.maskFingerprint("  "));
        assertEquals("******", MerchantCardView.maskFingerprint("abcd"));
    }
}
