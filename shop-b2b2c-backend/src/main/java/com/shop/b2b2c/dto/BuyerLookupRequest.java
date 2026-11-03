/*
 * 文件：BuyerLookupRequest.java
 * 用途：买家POST查单的订单号、联系方式和独立领取凭证契约。
 * 维护注意：联系方式和领取凭证只放请求体，不进入URL或日志；错误统一处理，不暴露哪个字段命中。领取凭证是能力令牌，不等于手机号验证或付款链接。
 */
package com.shop.b2b2c.dto;
import com.shop.b2b2c.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
public record BuyerLookupRequest(String orderSn, String contact, String queryToken) {
    public static final String INVALID = "订单信息或领取凭证不匹配";
    public BuyerLookupRequest normalized() {
        String normalizedOrder = clean(orderSn);
        if (!normalizedOrder.matches("[A-Za-z0-9_-]+")) throw new BusinessException(400, INVALID);
        return new BuyerLookupRequest(normalizedOrder, clean(contact), clean(queryToken));
    }
    private static String clean(String value) {
        if (value == null || value.isBlank() || value.length() > 64) throw new BusinessException(400, INVALID);
        return value.trim();
    }
    public static boolean matches(String stored, String supplied) {
        return stored != null && !stored.isBlank() && supplied != null && !supplied.isBlank()
                && MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
