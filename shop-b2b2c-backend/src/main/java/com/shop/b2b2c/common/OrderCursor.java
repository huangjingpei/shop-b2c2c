/*
 * 文件：OrderCursor.java
 * 用途：T24 订单 keyset 分页游标，编码 created_at + id 复合排序键，避免同一秒订单翻页重复或漏行。
 * 维护注意：游标对客户端是不透明字符串，只允许服务端生成；解析失败必须返回 400，不得静默退回第一页。
 *           时间分量按 ISO 本地时间完整精度编码（含微秒/纳秒），禁止截断到毫秒：数据库若保留亚毫秒精度，
 *           截断会让“同一毫秒内更晚创建”的行既不属于 `< 游标`、也不属于 `= 游标且 id 更小`，导致翻页漏行。
 *           服务端多实例必须保持相同时区配置与相同的游标实现版本。
 */
package com.shop.b2b2c.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public record OrderCursor(LocalDateTime createdAt, Long id) {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public static String encode(LocalDateTime createdAt, Long id) {
        if (createdAt == null || id == null) {
            return null;
        }
        return createdAt.format(TIME_FORMAT) + "_" + id;
    }

    public static OrderCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] parts = cursor.trim().split("_", -1);
        if (parts.length != 2) {
            throw new BusinessException(400, "分页游标不正确");
        }
        try {
            LocalDateTime createdAt = LocalDateTime.parse(parts[0], TIME_FORMAT);
            long id = Long.parseLong(parts[1]);
            if (id <= 0) {
                throw new BusinessException(400, "分页游标不正确");
            }
            return new OrderCursor(createdAt, id);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new BusinessException(400, "分页游标不正确");
        }
    }
}
