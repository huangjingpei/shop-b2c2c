/*
 * 文件：CsvEscaper.java
 * 用途：CSV 单元格统一转义工具（F09 商户账单导出复用，T35 对账导出同源）。
 * 维护注意：
 *  1. 转义逗号、双引号、换行；以 = + - @ 开头的值前置单引号，防 Excel 公式注入。
 *  2. 所有导出（对账、账单、账本）必须使用本方法，禁止各处自行拼引号导致口径漂移。
 */
package com.shop.b2b2c.common;

public final class CsvEscaper {

    private CsvEscaper() {
    }

    /** 单元格转义：null 返回空串；含分隔符的加引号；公式前缀加单引号防护。 */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        String safe = value.replace("\"", "\"\"");
        if (safe.startsWith("=") || safe.startsWith("+") || safe.startsWith("-") || safe.startsWith("@")) {
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe + "\"";
        }
        return safe;
    }
}
