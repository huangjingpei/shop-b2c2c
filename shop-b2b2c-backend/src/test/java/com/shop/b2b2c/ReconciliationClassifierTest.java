/*
 * 文件：ReconciliationClassifierTest.java
 * 用途：T+1 长短款/单边/精度/币种/跨天分类的纯规则用例，不启动 Spring、不连接数据库。
 * 维护注意：
 *  1. 金额全部使用 BigDecimal，断言直接比较分类常量与容差边界，不引入浮点字面量。
 *  2. 分类只作运营口径，不自动平账；用例同时覆盖“金额一致时不产生分类”。
 *  3. 时间按 Asia/Shanghai 墙上时间传入，跨天只比较日期部分。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.service.ReconciliationClassifier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationClassifierTest {

    private static final LocalDateTime DAY_ONE = LocalDateTime.of(2026, 11, 4, 23, 59, 59);
    private static final LocalDateTime DAY_TWO = LocalDateTime.of(2026, 11, 5, 0, 0, 1);

    @Test
    void channelOverCollectIsLong() {
        assertEquals(ReconciliationClassifier.LONG,
                ReconciliationClassifier.classify(new BigDecimal("100.00"), "CNY", DAY_ONE,
                        new BigDecimal("120.00"), "CNY", DAY_ONE));
    }

    @Test
    void channelUnderCollectIsShort() {
        assertEquals(ReconciliationClassifier.SHORT,
                ReconciliationClassifier.classify(new BigDecimal("100.00"), "CNY", DAY_ONE,
                        new BigDecimal("80.00"), "CNY", DAY_ONE));
    }

    @Test
    void missingChannelRecordIsOneSidedLocal() {
        // 方案 §4：ONE_SIDED_LOCAL = 我方有、渠道无。classify 第 1 组参数固定为平台侧（见方法签名）。
        assertEquals(ReconciliationClassifier.ONE_SIDED_LOCAL,
                ReconciliationClassifier.classify(new BigDecimal("100.00"), "CNY", DAY_ONE,
                        null, "CNY", DAY_ONE));
    }

    @Test
    void missingLocalRecordIsOneSidedChannel() {
        // 方案 §4：ONE_SIDED_CHANNEL = 渠道有、我方无。
        assertEquals(ReconciliationClassifier.ONE_SIDED_CHANNEL,
                ReconciliationClassifier.classify(null, "CNY", DAY_ONE,
                        new BigDecimal("100.00"), "CNY", DAY_ONE));
    }

    @Test
    void centDifferenceIsPrecisionNotLongOrShort() {
        assertEquals(ReconciliationClassifier.PRECISION,
                ReconciliationClassifier.classify(new BigDecimal("100.00"), "CNY", DAY_ONE,
                        new BigDecimal("100.01"), "CNY", DAY_ONE));
        assertEquals(ReconciliationClassifier.PRECISION,
                ReconciliationClassifier.classify(new BigDecimal("100.00"), "CNY", DAY_ONE,
                        new BigDecimal("99.99"), "CNY", DAY_ONE));
    }

    @Test
    void moreThanOneCentIsLongOrShort() {
        assertEquals(ReconciliationClassifier.LONG,
                ReconciliationClassifier.classify(new BigDecimal("100.00"), "CNY", DAY_ONE,
                        new BigDecimal("100.02"), "CNY", DAY_ONE));
        assertEquals(ReconciliationClassifier.SHORT,
                ReconciliationClassifier.classify(new BigDecimal("100.00"), "CNY", DAY_ONE,
                        new BigDecimal("99.98"), "CNY", DAY_ONE));
    }

    @Test
    void currencyMismatchWinsBeforeAmountClassification() {
        assertEquals(ReconciliationClassifier.CURRENCY,
                ReconciliationClassifier.classify(new BigDecimal("100.00"), "CNY", DAY_ONE,
                        new BigDecimal("120.00"), "USD", DAY_ONE));
    }

    @Test
    void sameAmountOnDifferentBusinessDayIsTimeCrossDay() {
        assertEquals(ReconciliationClassifier.TIME_CROSS_DAY,
                ReconciliationClassifier.classify(new BigDecimal("100.00"), "CNY", DAY_ONE,
                        new BigDecimal("100.00"), "CNY", DAY_TWO));
    }

    @Test
    void consistentAmountAndDayProducesNoCategory() {
        assertNull(ReconciliationClassifier.classify(new BigDecimal("100.00"), "CNY", DAY_ONE,
                new BigDecimal("100.00"), "CNY", DAY_ONE));
        assertNull(ReconciliationClassifier.classify(null, null, null, null, null, null));
    }

    @Test
    void internalDifferenceUsesSameLongShortRule() {
        assertEquals(ReconciliationClassifier.LONG,
                ReconciliationClassifier.classifyInternal(new BigDecimal("3.00"), new BigDecimal("5.00")));
        assertEquals(ReconciliationClassifier.SHORT,
                ReconciliationClassifier.classifyInternal(new BigDecimal("3.00"), new BigDecimal("2.00")));
        assertEquals(ReconciliationClassifier.PRECISION,
                ReconciliationClassifier.classifyInternal(new BigDecimal("3.00"), new BigDecimal("3.01")));
        assertNull(ReconciliationClassifier.classifyInternal(new BigDecimal("3.00"), new BigDecimal("3.00")));
    }

    @Test
    void amountComparisonUsesScaleTwoCompareTo() {
        assertTrue(ReconciliationClassifier.amountEquals(new BigDecimal("100"), new BigDecimal("100.000")));
        assertFalse(ReconciliationClassifier.amountEquals(new BigDecimal("100.00"), new BigDecimal("100.01")));
        assertEquals(new BigDecimal("100.01"),
                ReconciliationClassifier.money(new BigDecimal("100.005")));
    }

    @Test
    void blankCurrencyIsNotTreatedAsMismatch() {
        assertFalse(ReconciliationClassifier.currencyMismatch(null, "CNY"));
        assertFalse(ReconciliationClassifier.currencyMismatch(" ", "CNY"));
        assertTrue(ReconciliationClassifier.currencyMismatch("CNY", "USD"));
    }

    @Test
    void missingTimestampDoesNotCountAsCrossDay() {
        assertFalse(ReconciliationClassifier.dateMismatch(null, DAY_ONE));
        assertFalse(ReconciliationClassifier.dateMismatch(DAY_ONE, null));
        assertTrue(ReconciliationClassifier.dateMismatch(DAY_ONE, DAY_TWO));
    }
}
