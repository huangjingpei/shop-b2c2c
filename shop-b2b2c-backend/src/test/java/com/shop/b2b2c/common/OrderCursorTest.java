/*
 * 文件：OrderCursorTest.java
 * 用途：锁定 T24 keyset 游标必须保留 created_at 的亚毫秒精度，避免同一毫秒内订单翻页漏行（r24 回归根因）。
 * 维护注意：纯字符串编码/解析用例，不启动 Spring、不连接数据库；时间按本地墙上时间构造，
 *          与 OrderCursor 的生产编码口径一致。断言精度丢失必须失败，不得通过放宽断言规避。
 */
package com.shop.b2b2c.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderCursorTest {

    @Test
    void encodeKeepsMicrosecondPrecisionAndId() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 7, 12, 0, 0, 123_456_000);
        OrderCursor decoded = OrderCursor.decode(OrderCursor.encode(time, 7L));

        assertEquals(time, decoded.createdAt());
        assertEquals(7L, decoded.id());
    }

    @Test
    void encodeKeepsNanosecondPrecision() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 7, 12, 0, 0, 123_456_789);
        OrderCursor decoded = OrderCursor.decode(OrderCursor.encode(time, 8L));

        assertEquals(time, decoded.createdAt());
        assertEquals(8L, decoded.id());
    }

    @Test
    void invalidCursorIsRejectedWith400() {
        BusinessException malformedTime = assertThrows(BusinessException.class,
                () -> OrderCursor.decode("not-a-time_2"));
        assertEquals(400, malformedTime.getCode());

        BusinessException malformedId = assertThrows(BusinessException.class,
                () -> OrderCursor.decode("2026-11-07T12:00:00_0"));
        assertEquals(400, malformedId.getCode());

        BusinessException missingPart = assertThrows(BusinessException.class,
                () -> OrderCursor.decode("2026-11-07T12:00:00"));
        assertEquals(400, missingPart.getCode());
    }

    @Test
    void blankCursorMeansFirstPage() {
        assertNull(OrderCursor.decode(null));
        assertNull(OrderCursor.decode("  "));
    }

    @Test
    void encodeReturnsNullWhenTimeOrIdMissing() {
        assertNull(OrderCursor.encode(null, 1L));
        assertNull(OrderCursor.encode(LocalDateTime.of(2026, 11, 7, 12, 0), null));
    }
}
