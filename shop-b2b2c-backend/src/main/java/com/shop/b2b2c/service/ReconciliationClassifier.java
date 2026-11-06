/*
 * 文件：ReconciliationClassifier.java
 * 用途：T+1 长短款/单边账/精度/币种/跨天分类的纯规则计算，不依赖数据库。
 * 维护注意：
 *  1. 金额统一 BigDecimal、scale=2；比较用 compareTo，禁止 double/float。
 *  2. 分类只作运营口径提示，不自动改账；PRECISION 是容差分类，不代表自动通过。
 *  3. 优先级：币种 > 单边 > 精度(≤0.01) > 长/短款 > 跨天；金额不一致时金额分类优先。
 *  4. 时间为 Asia/Shanghai 墙上时间；仅当两侧都存在且日期不同才判 TIME_CROSS_DAY。
 */
package com.shop.b2b2c.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

public final class ReconciliationClassifier {

    public static final String LONG = "LONG";
    public static final String SHORT = "SHORT";
    public static final String ONE_SIDED_LOCAL = "ONE_SIDED_LOCAL";
    public static final String ONE_SIDED_CHANNEL = "ONE_SIDED_CHANNEL";
    public static final String PRECISION = "PRECISION";
    public static final String CURRENCY = "CURRENCY";
    public static final String TIME_CROSS_DAY = "TIME_CROSS_DAY";
    public static final String INTERNAL = "INTERNAL";

    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");

    private ReconciliationClassifier() {
    }

    /**
     * 渠道侧与平台侧金额分类。
     *
     * @param platformExpected 平台应有金额；null 表示我方无此单据
     * @param platformCurrency 平台币种，可为空
     * @param platformTime     平台记账时间，可为空
     * @param channelActual    渠道实际金额；null 表示渠道无此单据
     * @param channelCurrency  渠道币种，可为空
     * @param channelTime      渠道交易时间，可为空
     * @return 分类；金额与日期均一致且无异常时返回 null
     */
    public static String classify(BigDecimal platformExpected, String platformCurrency, LocalDateTime platformTime,
                                  BigDecimal channelActual, String channelCurrency, LocalDateTime channelTime) {
        if (platformExpected == null && channelActual == null) {
            return null;
        }
        if (platformExpected == null) {
            return ONE_SIDED_CHANNEL;
        }
        if (channelActual == null) {
            return ONE_SIDED_LOCAL;
        }
        if (currencyMismatch(platformCurrency, channelCurrency)) {
            return CURRENCY;
        }
        BigDecimal expected = money(platformExpected);
        BigDecimal actual = money(channelActual);
        BigDecimal diff = actual.subtract(expected);
        if (diff.signum() == 0) {
            return dateMismatch(platformTime, channelTime) ? TIME_CROSS_DAY : null;
        }
        if (diff.abs().compareTo(ONE_CENT) <= 0) {
            return PRECISION;
        }
        return diff.signum() > 0 ? LONG : SHORT;
    }

    /**
     * 内部恒等式比对（服务费账本、平台代收分账、结算金额）。actual 为 null 表示账本/关联单据缺失。
     */
    public static String classifyInternal(BigDecimal expected, BigDecimal actual) {
        if (expected == null && actual == null) {
            return null;
        }
        if (actual == null) {
            return ONE_SIDED_LOCAL;
        }
        if (expected == null) {
            return ONE_SIDED_CHANNEL;
        }
        BigDecimal diff = money(actual).subtract(money(expected));
        if (diff.signum() == 0) {
            return null;
        }
        if (diff.abs().compareTo(ONE_CENT) <= 0) {
            return PRECISION;
        }
        return diff.signum() > 0 ? LONG : SHORT;
    }

    public static boolean currencyMismatch(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return false;
        }
        return !left.trim().equalsIgnoreCase(right.trim());
    }

    public static boolean dateMismatch(LocalDateTime left, LocalDateTime right) {
        return left != null && right != null && !left.toLocalDate().equals(right.toLocalDate());
    }

    public static boolean amountEquals(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return money(left).compareTo(money(right)) == 0;
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
