/*
 * 文件：MerchantNotificationServiceTest.java
 * 用途：O8 通知发件箱服务单元边界：INAPP 落库即送达、SMS 默认禁用 SKIPPED/启用 PENDING、
 *       四元组唯一冲突幂等跳过、密码红线 fail-fast、渠道与必填参数校验。
 * 维护注意：仓储使用 Mock，不连接真实数据库；uk 去重的真实 SQL 行为由 Sprint1 集成用例覆盖。
 *           新增渠道时必须同时补投递状态语义与测试，不得让未知渠道静默落库。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantNotificationOutbox;
import com.shop.b2b2c.mapper.MerchantNotificationOutboxMapper;
import com.shop.b2b2c.service.MerchantNotificationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MerchantNotificationServiceTest {

    private final MerchantNotificationOutboxMapper outboxMapper = mock(MerchantNotificationOutboxMapper.class);
    /** 默认形态：SMS 通道禁用（与生产默认 shop.notification.sms-enabled=false 一致）。 */
    private final MerchantNotificationService service = new MerchantNotificationService(outboxMapper, false);
    /** 开关打开形态：SMS 仅落 PENDING 等真实发送器，本期不接服务商。 */
    private final MerchantNotificationService smsEnabledService = new MerchantNotificationService(outboxMapper, true);

    @Test
    void inappNotificationIsDeliveredOnInsertWithSentAt() {
        assertTrue(service.notify(MerchantNotificationService.BIZ_ONBOARDING_SUBMITTED, 11L,
                MerchantNotificationService.CHANNEL_INAPP, "ONBOARDING_SUBMITTED", "13800000000",
                Map.of("applicationId", 11, "storeName", "旗舰店")));
        ArgumentCaptor<MerchantNotificationOutbox> captor = ArgumentCaptor.forClass(MerchantNotificationOutbox.class);
        verify(outboxMapper).insert(captor.capture());
        MerchantNotificationOutbox row = captor.getValue();
        assertEquals("ONBOARDING_SUBMITTED", row.getBizType());
        assertEquals(11L, row.getBizId());
        assertEquals("INAPP", row.getChannel());
        assertEquals(MerchantNotificationService.STATUS_SENT, row.getStatus());
        assertNotNull(row.getSentAt());
        assertEquals(0, row.getAttempts());
        assertTrue(row.getPayloadJson().contains("旗舰店"));
    }

    @Test
    void smsIsSkippedWhenDisabledAndOnlyPendingWhenEnabled() {
        assertTrue(service.notify(MerchantNotificationService.BIZ_ONBOARDING_REJECTED, 21L,
                MerchantNotificationService.CHANNEL_SMS, "ONBOARDING_REJECTED", "13800000000",
                Map.of("applicationId", 21)));
        ArgumentCaptor<MerchantNotificationOutbox> captor = ArgumentCaptor.forClass(MerchantNotificationOutbox.class);
        verify(outboxMapper).insert(captor.capture());
        assertEquals(MerchantNotificationService.STATUS_SKIPPED, captor.getValue().getStatus());
        assertTrue(captor.getValue().getLastError().contains("禁用"));
        assertNull(captor.getValue().getSentAt());

        assertTrue(smsEnabledService.notify(MerchantNotificationService.BIZ_ONBOARDING_REJECTED, 22L,
                MerchantNotificationService.CHANNEL_SMS, "ONBOARDING_REJECTED", "13800000000",
                Map.of("applicationId", 22)));
        verify(outboxMapper, times(2)).insert(captor.capture());
        assertEquals(MerchantNotificationService.STATUS_PENDING, captor.getValue().getStatus());
    }

    @Test
    void duplicateUniqueKeyIsAlreadyNotifiedNotAnError() {
        doThrow(new DuplicateKeyException("uk_notification_event")).when(outboxMapper).insert(any());
        assertFalse(service.notify(MerchantNotificationService.BIZ_REVIEW_SLA_BREACH, 31L,
                MerchantNotificationService.CHANNEL_INAPP, "REVIEW_SLA_REMINDER",
                MerchantNotificationService.roleRecipient("SUPERADMIN"), Map.of("applicationId", 31)));
    }

    @Test
    void passwordKeysAreRejectedFailFastBeforeAnyInsert() {
        var ex = assertThrows(BusinessException.class,
                () -> service.notify(MerchantNotificationService.BIZ_ONBOARDING_OPENED, 41L,
                        MerchantNotificationService.CHANNEL_INAPP, "ONBOARDING_OPENED", "MERCHANT:1",
                        Map.of("username", "merchant1", "initialPassword", "abc123")));
        assertEquals(500, ex.getCode());
        var nested = assertThrows(BusinessException.class,
                () -> service.notify(MerchantNotificationService.BIZ_ONBOARDING_OPENED, 42L,
                        MerchantNotificationService.CHANNEL_INAPP, "ONBOARDING_OPENED", "MERCHANT:1",
                        Map.of("account", Map.of("passwordHint", "x"))));
        assertEquals(500, nested.getCode());
        verifyNoInteractions(outboxMapper);
    }

    @Test
    void invalidChannelAndBlankFieldsAreRejected() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.notify("ONBOARDING_OPENED", 1L, "EMAIL", "T", "r", null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.notify(" ", 1L, "INAPP", "T", "r", null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.notify("ONBOARDING_OPENED", null, "INAPP", "T", "r", null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.notify("ONBOARDING_OPENED", 1L, "INAPP", " ", "r", null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.notify("ONBOARDING_OPENED", 1L, "INAPP", "T", " ", null)).getCode());
        verifyNoInteractions(outboxMapper);
    }

    @Test
    void notifyAllWritesEachChannelOnceAndSkipsDuplicates() {
        assertEquals(2, service.notifyAll(MerchantNotificationService.BIZ_ONBOARDING_OPENED, 51L,
                "ONBOARDING_OPENED", "MERCHANT:7", Map.of("merchantId", 7),
                MerchantNotificationService.CHANNEL_INAPP, MerchantNotificationService.CHANNEL_SMS));
        verify(outboxMapper, times(2)).insert(any());

        reset(outboxMapper);
        doThrow(new DuplicateKeyException("dup")).when(outboxMapper).insert(any());
        assertEquals(0, service.notifyAll(MerchantNotificationService.BIZ_ONBOARDING_OPENED, 51L,
                "ONBOARDING_OPENED", "MERCHANT:7", Map.of("merchantId", 7),
                MerchantNotificationService.CHANNEL_INAPP, MerchantNotificationService.CHANNEL_SMS));
    }

    @Test
    void recipientFormatsFollowTheConvention() {
        assertEquals("MERCHANT:9", MerchantNotificationService.merchantRecipient(9L));
        assertEquals("ROLE:SUPERADMIN", MerchantNotificationService.roleRecipient("SUPERADMIN"));
    }
}
