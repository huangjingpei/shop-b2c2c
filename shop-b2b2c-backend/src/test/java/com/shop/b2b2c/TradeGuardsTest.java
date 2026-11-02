/*
 * 文件：TradeGuardsTest.java
 * 用途：交易规则的参数化纯单元测试。
 * 维护注意：覆盖金额、数量、交付类型与店铺资格的合法/非法边界；不启动Spring或数据库。调整规则必须同时保留允许值与拒绝值的测试，避免只测试实现细节。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.*;
import com.shop.b2b2c.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class TradeGuardsTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "1.001", "100000000.00"})
    void rejectsInvalidMoney(String amount) {
        assertThrows(BusinessException.class, () -> TradeGuards.requireMoney(amount == null ? null : new BigDecimal(amount)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "19.90", "99999999.99"})
    void acceptsMoneyWithinDatabasePrecision(String amount) {
        assertDoesNotThrow(() -> TradeGuards.requireMoney(new BigDecimal(amount)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 0, 10001})
    void rejectsInvalidQuantity(Integer count) {
        assertThrows(BusinessException.class, () -> TradeGuards.requireQuantity(count));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10000})
    void acceptsQuantityBoundaries(int count) {
        assertDoesNotThrow(() -> TradeGuards.requireQuantity(count));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 4})
    void rejectsUnsupportedDeliveryType(Integer type) {
        assertThrows(BusinessException.class, () -> TradeGuards.requireGoodsType(type));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void acceptsExplicitDeliveryTypes(int type) {
        assertDoesNotThrow(() -> TradeGuards.requireGoodsType(type));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 3})
    void closedOrBannedMerchantCannotTrade(int status) {
        Product product = new Product();
        MerchantStore store = new MerchantStore(); store.setStatus(status);
        assertThrows(BusinessException.class, () -> TradeGuards.requirePurchasable(product, store));
    }

    @Test
    void missingStoreAndOffShelfProductsCannotTrade() {
        Product product = new Product();
        assertThrows(BusinessException.class, () -> TradeGuards.requirePurchasable(product, null));
        MerchantStore store = new MerchantStore(); store.setStatus(1);
        product.setStatus(0);
        assertThrows(BusinessException.class, () -> TradeGuards.requirePurchasable(product, store));
        product.setStatus(1);
        assertDoesNotThrow(() -> TradeGuards.requirePurchasable(product, store));
    }
}
