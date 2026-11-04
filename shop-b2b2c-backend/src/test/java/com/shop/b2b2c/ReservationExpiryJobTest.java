/*
 * 文件：ReservationExpiryJobTest.java
 * 用途：测试到期库存预占与超时未支付订单的游标扫描、毒记录异常隔离与防饿死机制 (R11, R12)。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.config.ReservationExpiryJob;
import com.shop.b2b2c.mapper.InventoryReservationMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.service.PeerPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationExpiryJobTest {

    @Mock
    private InventoryReservationMapper reservations;

    @Mock
    private TradeOrderMapper orders;

    @Mock
    private PeerPayService service;

    private ReservationExpiryJob job;

    @BeforeEach
    void setUp() {
        job = new ReservationExpiryJob(reservations, orders, service);
    }

    @Test
    @DisplayName("R11: 预占释放中单条毒记录异常被隔离，不阻断且游标前移保证后续记录正常释放")
    void poisonReservationDoesNotStarveSubsequentExpiredReservations() {
        when(reservations.expiredIdsAfter(0L, 100)).thenReturn(List.of(101L, 102L, 103L));
        doThrow(new RuntimeException("毒数据: 脏库存或锁冲突")).when(service).expireReservation(101L);

        int processed = job.expireHeldReservations();

        // 验证 101 抛出异常但 102 和 103 仍被正常执行
        assertEquals(2, processed);
        verify(service).expireReservation(101L);
        verify(service).expireReservation(102L);
        verify(service).expireReservation(103L);
        // 本批次小于 100，游标重置为 0L 供下轮扫描
        assertEquals(0L, job.getReservationCursor());
    }

    @Test
    @DisplayName("R11: 满批次全部失败时游标保留推进值，下一轮请求不重复死锁在头100条")
    void fullBatchFailureAdvancesCursorToPreventStarvingNextBatches() {
        List<Long> poisonBatch = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            poisonBatch.add(i);
        }
        when(reservations.expiredIdsAfter(0L, 100)).thenReturn(poisonBatch);
        doThrow(new RuntimeException("持续失败")).when(service).expireReservation(anyLong());

        int processed = job.expireHeldReservations();

        assertEquals(0, processed);
        // 游标已前进到 100，而不是重置为 0
        assertEquals(100L, job.getReservationCursor());

        // 第二轮：从 100L 之后获取第二批（包含第 101 条及后续合法记录）
        when(reservations.expiredIdsAfter(100L, 100)).thenReturn(List.of(101L, 102L));
        reset(service);

        int secondProcessed = job.expireHeldReservations();
        assertEquals(2, secondProcessed);
        verify(service).expireReservation(101L);
        verify(service).expireReservation(102L);
        assertEquals(0L, job.getReservationCursor());
    }

    @Test
    @DisplayName("R12: 超时未支付订单定时扫描并触发关闭 (无预占意向单与自购单)")
    void unpaidOrdersExpireAndCloseCleanly() {
        when(orders.findExpiredUnpaidOrderIds(any(), any(), eq(0L), eq(100)))
                .thenReturn(List.of(201L, 202L));

        int processed = job.expireUnpaidOrders();

        assertEquals(2, processed);
        verify(service).expireReservation(201L);
        verify(service).expireReservation(202L);
        assertEquals(0L, job.getOrderCursor());
    }

    @Test
    @DisplayName("R12: 超时未支付订单扫描中个别单据异常不影响同批后续单据关闭")
    void poisonUnpaidOrderDoesNotBlockSubsequentOrders() {
        when(orders.findExpiredUnpaidOrderIds(any(), any(), eq(0L), eq(100)))
                .thenReturn(List.of(301L, 302L));
        doThrow(new IllegalStateException("行锁超时")).when(service).expireReservation(301L);

        int processed = job.expireUnpaidOrders();

        assertEquals(1, processed);
        verify(service).expireReservation(301L);
        verify(service).expireReservation(302L);
    }
}
