/*
 * 文件：ReviewSlaEscalationJobTest.java
 * 用途：O8 审核SLA超时升级任务单元测试：24h提醒/48h升级分档、初审滞留提醒复核人、
 *       唯一键幂等（重扫不重复计数）、异常不阻塞调度线程。
 * 维护注意：仓储与通知服务使用 Mock；时间通过 scan(now) 注入，测试不依赖真实时钟。
 *           升级只通知不改单状态——任何“超时自动驳回/自动通过”都不得在本任务中引入（属红线）。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.config.ReviewSlaEscalationJob;
import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import com.shop.b2b2c.mapper.MerchantOnboardingApplicationMapper;
import com.shop.b2b2c.service.MerchantNotificationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewSlaEscalationJobTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 12, 0, 0);

    private final MerchantOnboardingApplicationMapper applicationMapper = mock(MerchantOnboardingApplicationMapper.class);
    private final MerchantNotificationService notificationService = mock(MerchantNotificationService.class);
    private final ReviewSlaEscalationJob job =
            new ReviewSlaEscalationJob(applicationMapper, notificationService, 24, 200);

    private MerchantOnboardingApplication application(long id, LocalDateTime submittedAt) {
        MerchantOnboardingApplication app = new MerchantOnboardingApplication();
        app.setId(id);
        app.setStoreName("store" + id);
        app.setSubmittedAt(submittedAt);
        return app;
    }

    @Test
    void submittedOverReminderThresholdWritesReminderOncePerApplication() {
        var app = application(7L, NOW.minusHours(25));
        // 第一次调用为 24h 提醒档，第二次为 48h 升级档；该单只命中提醒档。
        when(applicationMapper.findSubmittedBefore(any(), eq(200)))
                .thenReturn(List.of(app), List.of());
        when(applicationMapper.findPrimaryReviewStale(any(), eq(200))).thenReturn(List.of());
        when(notificationService.notify(any(), any(), any(), any(), any(), any())).thenReturn(true);

        var result = job.scan(NOW);
        assertEquals(1, result.reminded());
        assertEquals(0, result.escalated());
        assertEquals(0, result.reviewerReminded());

        ArgumentCaptor<LocalDateTime> threshold = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(applicationMapper, times(2)).findSubmittedBefore(threshold.capture(), eq(200));
        assertEquals(NOW.minusHours(24), threshold.getAllValues().get(0));
        assertEquals(NOW.minusHours(48), threshold.getAllValues().get(1));

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(eq(MerchantNotificationService.BIZ_REVIEW_SLA_BREACH), eq(7L),
                eq(MerchantNotificationService.CHANNEL_INAPP), eq(ReviewSlaEscalationJob.TEMPLATE_SLA_REMINDER),
                eq("ROLE:SUPERADMIN"), payload.capture());
        assertEquals(25L, payload.getValue().get("waitingHours"));
        assertEquals("store7", payload.getValue().get("storeName"));
    }

    @Test
    void submittedOverDoubleThresholdEscalates() {
        var app = application(8L, NOW.minusHours(50));
        when(applicationMapper.findSubmittedBefore(any(), eq(200)))
                .thenReturn(List.of(app), List.of(app));
        when(applicationMapper.findPrimaryReviewStale(any(), eq(200))).thenReturn(List.of());
        when(notificationService.notify(any(), any(), any(), any(), any(), any())).thenReturn(true);

        var result = job.scan(NOW);
        assertEquals(1, result.reminded(), "超48h的单同时命中24h提醒档（若尚未提醒过）");
        assertEquals(1, result.escalated());
        verify(notificationService).notify(any(), eq(8L), any(),
                eq(ReviewSlaEscalationJob.TEMPLATE_SLA_ESCALATION), any(), any());
    }

    @Test
    void stalePrimaryReviewRemindsReviewerWithAuditBasedTiming() {
        var app = application(9L, NOW.minusHours(30));
        app.setFirstReviewerId(101L);
        when(applicationMapper.findSubmittedBefore(any(), eq(200))).thenReturn(List.of());
        when(applicationMapper.findPrimaryReviewStale(eq(NOW.minusHours(24)), eq(200))).thenReturn(List.of(app));
        when(notificationService.notify(any(), any(), any(), any(), any(), any())).thenReturn(true);

        var result = job.scan(NOW);
        assertEquals(1, result.reviewerReminded());
        verify(notificationService).notify(any(), eq(9L), any(),
                eq(ReviewSlaEscalationJob.TEMPLATE_SLA_REVIEWER), eq("ROLE:SUPERADMIN"), any());
    }

    @Test
    void rerunIsIdempotentWhenUniqueKeyDeduplicates() {
        var app = application(10L, NOW.minusHours(30));
        when(applicationMapper.findSubmittedBefore(any(), eq(200)))
                .thenReturn(List.of(app), List.of());
        when(applicationMapper.findPrimaryReviewStale(any(), eq(200))).thenReturn(List.of());
        // 唯一键冲突 → 已通知过：计数为 0，不视为故障。
        when(notificationService.notify(any(), any(), any(), any(), any(), any())).thenReturn(false);

        var result = job.scan(NOW);
        assertEquals(0, result.total());
        verify(notificationService).notify(any(), eq(10L), any(),
                eq(ReviewSlaEscalationJob.TEMPLATE_SLA_REMINDER), any(), any());
    }

    @Test
    void runNeverThrowsToSchedulerThread() {
        when(applicationMapper.findSubmittedBefore(any(), anyInt()))
                .thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(job::run);
    }

    @Test
    void slaThresholdsFollowConfiguration() {
        var customJob = new ReviewSlaEscalationJob(applicationMapper, notificationService, 4, 50);
        when(applicationMapper.findSubmittedBefore(any(), eq(50))).thenReturn(List.of());
        when(applicationMapper.findPrimaryReviewStale(any(), eq(50))).thenReturn(List.of());
        customJob.scan(NOW);
        ArgumentCaptor<LocalDateTime> threshold = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(applicationMapper, times(2)).findSubmittedBefore(threshold.capture(), eq(50));
        assertEquals(NOW.minusHours(4), threshold.getAllValues().get(0));
        assertEquals(NOW.minusHours(8), threshold.getAllValues().get(1), "升级档固定为提醒档两倍（48h=24h*2）");
        verify(applicationMapper).findPrimaryReviewStale(eq(NOW.minusHours(4)), eq(50));
    }
}
