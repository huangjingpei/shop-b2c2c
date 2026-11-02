/*
 * 文件：ManagementGuards.java
 * 用途：商户后台的金额、费率、状态、枚举和文本长度校验。
 * 维护注意：零月费/零佣金合法；金额最多两位小数。这里只校验输入边界，不验证身份、渠道进件或URL真实性；规则变更须覆盖合法边界和无写入副作用。
 */
package com.shop.b2b2c.common;

import java.math.BigDecimal;
import java.util.Set;

public final class ManagementGuards {
    private ManagementGuards() {}

    public static void amount(BigDecimal value, BigDecimal max) {
        if (value == null || value.signum() < 0 || value.scale() > 2 || value.compareTo(max) > 0) {
            throw new BusinessException(400, "金额或费率超出范围，最多两位小数");
        }
    }

    public static void percent(BigDecimal value) { amount(value, new BigDecimal("100")); }
    public static void monthlyFee(BigDecimal value) { amount(value, new BigDecimal("99999999.99")); }

    public static void status(Integer value) {
        if (value == null || value < 0 || value > 3) throw new BusinessException(400, "店铺状态不合法");
    }

    public static void choice(String value, Set<String> choices) {
        if (value == null || !choices.contains(value)) throw new BusinessException(400, "配置枚举值不合法");
    }

    public static void text(String value, int max, boolean required) {
        if ((required && (value == null || value.isBlank())) || (value != null && value.length() > max)) {
            throw new BusinessException(400, "文本必填项或长度不合法");
        }
    }
}
