/*
 * 文件：OrderCursor.java
 * 用途：T24 订单 keyset 分页游标，编码 created_at + id 复合排序键，避免同一秒订单翻页重复或漏行。
 * 维护注意：游标对客户端是不透明字符串，只允许服务端生成；解析失败必须返回 400，不得静默退回第一页。
 *           编码使用 JVM 默认时区与毫秒时间戳，服务端多实例必须保持相同时区配置。
 */
package com.shop.b2b2c.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public record OrderCursor(LocalDateTime createdAt, Long id) {

    public static String encode(LocalDateTime createdAt, Long id) {
        if (createdAt == null || id == null) {
            return null;
        }
        long millis = createdAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return millis + "_" + id;
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
            long millis = Long.parseLong(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (millis < 0 || id <= 0) {
                throw new BusinessException(400, "分页游标不正确");
            }
            return new OrderCursor(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()), id);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "分页游标不正确");
        }
    }
}
