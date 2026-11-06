/*
 * 文件：QualificationExpiryJobTest.java
 * 用途：O9 资质有效期任务单元测试：60/30/7 天提醒档位、双资质分别通知、唯一键幂等（重扫不计数）、
 *       逾期 30 天自动冻结（系统操作人）、单店失败不阻塞整批、run 不向调度线程抛异常。
 * 维护注意：仓储/通知/状态机服务全部 Mock；日期经 scan(today) 注入，不依赖真实时钟。
 *           冻结必须走 MerchantStoreStatusService.applyAction（审计留痕），不得直接改 merchant_store.status。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.config.QualificationExpiryJob;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.service.MerchantNotificationService;
import com.shop.b2b2c.service.MerchantStoreStatusService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QualificationExpiryJobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);

    private final MerchantStoreMapper storeMapper = mock(MerchantStoreMapper.class);
    private final MerchantNotificationService notificationService = mock(MerchantNotificationService.class);
    private final MerchantStoreStatusService storeStatusService = mock(MerchantStoreStatusService.class);
    private final QualificationExpiryJob job =
            new QualificationExpiryJob(storeMapper, notificationService, storeStatusService, 200);

    private MerchantStore store(long id, LocalDate idCardExpiry, LocalDate licenseExpiry) {
        MerchantStore store = new MerchantStore();
        store.setId(id);
        store.setStoreName("store" + id);
        store.setStatus(1);
        store.setIdCardExpiry(idCardExpiry);
        store.setLicenseExpiry(licenseExpiry);
        return store;
    }

    @Test
    void reminderNodesQueryExactExpiryDates() {
        // 60/30/7 三个档位分别以 today+N 精确查询。
        when(storeMapper.findExpiringOn(any(), eq(200))).thenReturn(List.of());
        when(storeMapper.findExpiredBeyond(any(), eq(200))).thenReturn(List.of());

        job.scan(TODAY);

        ArgumentCaptor<LocalDate> dates = ArgumentCaptor.forClass(LocalDate.class);
        verify(storeMapper, times(3)).findExpiringOn(dates.capture(), eq(200));
        assertEquals(List.of(TODAY.plusDays(60), TODAY.plusDays(30), TODAY.plusDays(7)), dates.getAllValues());
    }

    @Test
    void expiringStoreNotifiesPerQualificationWithNodeTemplate() {
        // 同一天身份证与执照同时命中 D60：两条通知，模板码区分资质与档位。
        MerchantStore store = store(7L, TODAY.plusDays(60), TODAY.plusDays(60));
        when(storeMapper.findExpiringOn(eq(TODAY.plusDays(60)), eq(200))).thenReturn(List.of(store));
        when(storeMapper.findExpiringOn(eq(TODAY.plusDays(30)), eq(200))).thenReturn(List.of());
        when(storeMapper.findExpiringOn(eq(TODAY.plusDays(7)), eq(200))).thenReturn(List.of());
        when(storeMapper.findExpiredBeyond(any(), eq(200))).thenReturn(List.of());
        when(notificationService.notifyAll(any(), any(), any(), any(), any(), any(), any())).thenReturn(2);

        var result = job.scan(TODAY);
        assertEquals(4, result.reminded(), "两个资质各写 INAPP+SMS 两条");
        assertEquals(0, result.frozen());

        ArgumentCaptor<String> templates = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(notificationService, times(2)).notifyAll(eq(MerchantNotificationService.BIZ_LICENSE_EXPIRING),
                eq(7L), templates.capture(), eq("MERCHANT:7"), payloads.capture(),
                eq(MerchantNotificationService.CHANNEL_INAPP), eq(MerchantNotificationService.CHANNEL_SMS));
        assertEquals(List.of("LICENSE_EXPIRING_ID_CARD_D60", "LICENSE_EXPIRING_LICENSE_D60"),
                templates.getAllValues());
        Map<String, Object> first = payloads.getAllValues().get(0);
        assertEquals("ID_CARD", first.get("qualification"));
        assertEquals(TODAY.plusDays(60).toString(), first.get("expiryDate"));
        assertEquals(60, first.get("daysLeft"));
    }

    @Test
    void onlyMatchingQualificationIsNotified() {
        // 只有执照命中 D7，身份证为其它日期：只发执照档。
        MerchantStore store = store(8L, TODAY.plusDays(60), TODAY.plusDays(7));
        when(storeMapper.findExpiringOn(eq(TODAY.plusDays(60)), eq(200))).thenReturn(List.of(store));
        when(storeMapper.findExpiringOn(eq(TODAY.plusDays(30)), eq(200))).thenReturn(List.of());
        when(storeMapper.findExpiringOn(eq(TODAY.plusDays(7)), eq(200))).thenReturn(List.of(store));
        when(storeMapper.findExpiredBeyond(any(), eq(200))).thenReturn(List.of());
        when(notificationService.notifyAll(any(), any(), any(), any(), any(), any(), any())).thenReturn(2);

        var result = job.scan(TODAY);
        assertEquals(4, result.reminded());
        verify(notificationService).notifyAll(any(), eq(8L), eq("LICENSE_EXPIRING_ID_CARD_D60"), any(), any(), any(), any());
        verify(notificationService).notifyAll(any(), eq(8L), eq("LICENSE_EXPIRING_LICENSE_D7"), any(), any(), any(), any());
        verify(notificationService, never()).notifyAll(any(), any(), eq("LICENSE_EXPIRING_ID_CARD_D7"), any(), any(), any(), any());
    }

    @Test
    void rerunIsIdempotentWhenUniqueKeyDeduplicates() {
        MerchantStore store = store(9L, TODAY.plusDays(30), null);
        when(storeMapper.findExpiringOn(eq(TODAY.plusDays(60)), eq(200))).thenReturn(List.of());
        when(storeMapper.findExpiringOn(eq(TODAY.plusDays(30)), eq(200))).thenReturn(List.of(store));
        when(storeMapper.findExpiringOn(eq(TODAY.plusDays(7)), eq(200))).thenReturn(List.of());
        when(storeMapper.findExpiredBeyond(any(), eq(200))).thenReturn(List.of());
        // 唯一键冲突 → 已通知过：notifyAll 返回 0，不视为故障、不计数。
        when(notificationService.notifyAll(any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        var result = job.scan(TODAY);
        assertEquals(0, result.total());
        verify(notificationService).notifyAll(any(), eq(9L), eq("LICENSE_EXPIRING_ID_CARD_D30"), any(), any(), any(), any());
    }

    @Test
    void expiredBeyondGraceFreezesViaStatusServiceAsSystem() {
        MerchantStore store = store(10L, TODAY.minusDays(31), null);
        when(storeMapper.findExpiringOn(any(), eq(200))).thenReturn(List.of());
        when(storeMapper.findExpiredBeyond(eq(TODAY.minusDays(30)), eq(200))).thenReturn(List.of(store));

        var result = job.scan(TODAY);
        assertEquals(1, result.frozen());
        verify(storeStatusService).applyAction(eq(10L), eq(MerchantStoreStatusService.ACTION_FREEZE),
                eq(MerchantStoreStatusService.REASON_EXPIRED_LICENSE), any(), isNull(), isNull());
    }

    @Test
    void freezeFailureDoesNotBlockOtherStores() {
        MerchantStore conflicted = store(11L, TODAY.minusDays(40), null);
        MerchantStore freezable = store(12L, null, TODAY.minusDays(45));
        when(storeMapper.findExpiringOn(any(), eq(200))).thenReturn(List.of());
        when(storeMapper.findExpiredBeyond(any(), eq(200))).thenReturn(List.of(conflicted, freezable));
        doThrow(new BusinessException(409, "店铺状态已变化，操作冲突，请刷新后重试"))
                .when(storeStatusService).applyAction(eq(11L), any(), any(), any(), isNull(), isNull());

        var result = job.scan(TODAY);
        assertEquals(1, result.frozen(), "单店 409（状态已变化/并发）只跳过该店");
        verify(storeStatusService).applyAction(eq(12L), eq(MerchantStoreStatusService.ACTION_FREEZE),
                eq(MerchantStoreStatusService.REASON_EXPIRED_LICENSE), any(), isNull(), isNull());
    }

    @Test
    void runNeverThrowsToSchedulerThread() {
        when(storeMapper.findExpiringOn(any(), anyInt())).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(job::run);
    }
}
