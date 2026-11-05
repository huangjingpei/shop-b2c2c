/*
 * 文件：PiiMasking.java
 * 用途：对身份证号、银行卡号、卡密正文等敏感字段做接口返回前的默认脱敏。
 * 维护注意：本类只改变接口返回值，数据库仍保存原始值；脱敏不等于加密，不得用它替代卡密密文存储。脱敏结果不得用于唯一性判断或对账；空值与异常短值统一返回固定掩码，禁止回显原文。
 */
package com.shop.b2b2c.common;

public final class PiiMasking {
    private PiiMasking() {
    }

    /** 身份证：保留前4位与后4位，中间以*替代；不足10位整体掩码。 */
    public static String idCard(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.length() < 10) {
            return "****";
        }
        int keep = 4;
        return text.substring(0, keep) + "*".repeat(text.length() - keep * 2) + text.substring(text.length() - keep);
    }

    /** 银行卡：仅保留后4位，其余固定掩码；不足5位整体掩码，避免通过长度侧信道推断卡号。 */
    public static String bankAccount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.length() < 5) {
            return "****";
        }
        return "****" + text.substring(text.length() - 4);
    }

    /** 卡密正文：长度大于8时保留前4后4，否则整体掩码。 */
    public static String cardContent(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.length() <= 8) {
            return "****";
        }
        return text.substring(0, 4) + "****" + text.substring(text.length() - 4);
    }
}
