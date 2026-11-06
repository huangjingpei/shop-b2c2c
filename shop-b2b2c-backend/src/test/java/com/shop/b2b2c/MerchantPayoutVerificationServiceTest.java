/*
 * 文件：MerchantPayoutVerificationServiceTest.java
 * 用途：O5 打款验证规则单元测试：服务端生成金额、尝试次数上限、金额匹配通过、三次失败拒绝、
 *      过期推进、发起人/确认人不同人、商家端视图不泄漏金额。
 * 维护注意：
 *  1. Mockito 隔离测试，不连接数据库；行锁/唯一索引的并发行为由人工在 MySQL 验证。
 *  2. 金额区间为 (0.00, 1.00]（服务层 0.01-0.99），断言区间变动时同步服务实现与 chk_payout_amount。
 *  3. 新增状态/失败码须同步实体与 schema 注释，不得仅改本测试。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantPayoutVerification;
import com.shop.b2b2c.entity.MerchantSettlementAccount;
import com.shop.b2b2c.mapper.MerchantPayoutVerificationMapper;
import com.shop.b2b2c.mapper.MerchantSettlementAccountMapper;
import com.shop.b2b2c.service.MerchantPayoutVerificationService;
import com.shop.b2b2c.service.MerchantSettlementAccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MerchantPayoutVerificationServiceTest {

    @Mock
    private MerchantPayoutVerificationMapper verificationMapper;
    @Mock
    private MerchantSettlementAccountMapper accountMapper;
    @Mock
    private MerchantSettlementAccountService accountService;
    @InjectMocks
    private MerchantPayoutVerificationService service;

    @Test
    void initiateCreatesPendingAttemptWithServerSideAmount() {
        // 金额只能由服务端生成且在 0.01-0.99；attempt_no=1；默认 24 小时过期。
        when(accountMapper.lockById(5L)).thenReturn(pendingAccount(5L, 10L));
        when(verificationMapper.findPendingByAccountId(5L)).thenReturn(null);
        when(verificationMapper.countByAccountId(5L)).thenReturn(0);

        MerchantPayoutVerification created = service.initiate(10L, 5L, "VOUCHER-001", 7L);

        ArgumentCaptor<MerchantPayoutVerification> captor = ArgumentCaptor.forClass(MerchantPayoutVerification.class);
        verify(verificationMapper).insert(captor.capture());
        MerchantPayoutVerification saved = captor.getValue();
        assertEquals(1, saved.getAttemptNo());
        assertEquals("PENDING", saved.getStatus());
        assertEquals(7L, saved.getRequestedBy());
        assertTrue(saved.getAmount().compareTo(new BigDecimal("0.01")) >= 0);
        assertTrue(saved.getAmount().compareTo(new BigDecimal("0.99")) <= 0);
        assertEquals(2, saved.getAmount().scale());
        assertTrue(saved.getExpiresAt().isAfter(LocalDateTime.now().plusHours(23)));
        assertEquals("VOUCHER-001", saved.getTransferVoucherNo());
        assertSame(saved, created);
    }

    @Test
    void initiateRejectsWrongMerchantAndNonPendingAccount() {
        MerchantSettlementAccount other = pendingAccount(5L, 11L);
        when(accountMapper.lockById(5L)).thenReturn(other);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.initiate(10L, 5L, null, 7L));
        assertEquals(404, ex.getCode());

        MerchantSettlementAccount verified = pendingAccount(6L, 10L);
        verified.setStatus("VERIFIED");
        when(accountMapper.lockById(6L)).thenReturn(verified);

        ex = assertThrows(BusinessException.class,
                () -> service.initiate(10L, 6L, null, 7L));
        assertEquals(409, ex.getCode());
        verify(verificationMapper, never()).insert(any());
    }

    @Test
    void initiateRejectsActivePendingButMarksStaleExpiredAndRestarts() {
        // 进行中的验证 409；已过期未推进的先落 EXPIRED 再允许重新发起（attempt_no 递增）。
        when(accountMapper.lockById(5L)).thenReturn(pendingAccount(5L, 10L));
        MerchantPayoutVerification active = verification(1, 5L, 10L, "PENDING",
                LocalDateTime.now().plusHours(1));
        when(verificationMapper.findPendingByAccountId(5L)).thenReturn(active);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.initiate(10L, 5L, null, 7L));
        assertEquals(409, ex.getCode());
        verify(verificationMapper, never()).insert(any());

        MerchantPayoutVerification stale = verification(2, 5L, 10L, "PENDING",
                LocalDateTime.now().minusMinutes(1));
        when(verificationMapper.findPendingByAccountId(5L)).thenReturn(stale);
        when(verificationMapper.countByAccountId(5L)).thenReturn(1);

        service.initiate(10L, 5L, null, 7L);

        assertEquals("EXPIRED", stale.getStatus());
        assertEquals("EXPIRED", stale.getFailureCode());
        verify(verificationMapper).updateById(stale);
        ArgumentCaptor<MerchantPayoutVerification> captor = ArgumentCaptor.forClass(MerchantPayoutVerification.class);
        verify(verificationMapper).insert(captor.capture());
        assertEquals(2, captor.getValue().getAttemptNo());
    }

    @Test
    void initiateRejectsWhenAttemptsExhausted() {
        when(accountMapper.lockById(5L)).thenReturn(pendingAccount(5L, 10L));
        when(verificationMapper.findPendingByAccountId(5L)).thenReturn(null);
        when(verificationMapper.countByAccountId(5L)).thenReturn(3);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.initiate(10L, 5L, null, 7L));

        assertEquals(409, ex.getCode());
        verify(verificationMapper, never()).insert(any());
    }

    @Test
    void confirmByMerchantAmountMatchSucceedsAndVerifiesAccount() {
        MerchantPayoutVerification verification = verification(1, 5L, 10L, "PENDING",
                LocalDateTime.now().plusHours(1));
        verification.setAmount(new BigDecimal("0.58"));
        when(verificationMapper.lockById(1L)).thenReturn(verification);

        service.confirmByMerchant(1L, 10L, new BigDecimal("0.58"), 9L);

        assertEquals("SUCCESS", verification.getStatus());
        assertEquals(9L, verification.getConfirmedBy());
        assertNotNull(verification.getConfirmedAt());
        verify(verificationMapper).updateById(verification);
        verify(accountService).confirmPayoutSuccess(5L, 9L);
        verify(accountService, never()).rejectForPayoutExceeded(any(), any());
    }

    @Test
    void confirmByMerchantMismatchFailsAndThirdFailureRejectsAccount() {
        MerchantPayoutVerification verification = verification(1, 5L, 10L, "PENDING",
                LocalDateTime.now().plusHours(1));
        verification.setAmount(new BigDecimal("0.58"));
        when(verificationMapper.lockById(1L)).thenReturn(verification);
        when(verificationMapper.countFailedByAccountId(5L)).thenReturn(1);

        service.confirmByMerchant(1L, 10L, new BigDecimal("0.57"), 9L);

        assertEquals("FAILED", verification.getStatus());
        assertEquals("AMOUNT_MISMATCH", verification.getFailureCode());
        verify(accountService, never()).confirmPayoutSuccess(any(), any());
        verify(accountService, never()).rejectForPayoutExceeded(any(), any());

        MerchantPayoutVerification third = verification(2, 5L, 10L, "PENDING",
                LocalDateTime.now().plusHours(1));
        third.setAmount(new BigDecimal("0.58"));
        when(verificationMapper.lockById(2L)).thenReturn(third);
        when(verificationMapper.countFailedByAccountId(5L)).thenReturn(3);

        service.confirmByMerchant(2L, 10L, new BigDecimal("0.01"), 9L);

        assertEquals("FAILED", third.getStatus());
        verify(accountService).rejectForPayoutExceeded(5L, 9L);
    }

    @Test
    void confirmByMerchantRejectsForeignMerchantWith404() {
        MerchantPayoutVerification verification = verification(1, 5L, 11L, "PENDING",
                LocalDateTime.now().plusHours(1));
        when(verificationMapper.lockById(1L)).thenReturn(verification);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmByMerchant(1L, 10L, new BigDecimal("0.58"), 9L));

        assertEquals(404, ex.getCode());
        verify(accountService, never()).confirmPayoutSuccess(any(), any());
    }

    @Test
    void confirmByAdminRequiresDifferentOperatorThanRequester() {
        MerchantPayoutVerification verification = verification(1, 5L, 10L, "PENDING",
                LocalDateTime.now().plusHours(1));
        verification.setAmount(new BigDecimal("0.58"));
        verification.setRequestedBy(7L);
        when(verificationMapper.lockById(1L)).thenReturn(verification);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmByAdmin(1L, new BigDecimal("0.58"), 7L));
        assertEquals(403, ex.getCode());

        service.confirmByAdmin(1L, new BigDecimal("0.58"), 8L);

        assertEquals("SUCCESS", verification.getStatus());
        verify(accountService).confirmPayoutSuccess(5L, 8L);
    }

    @Test
    void confirmRejectsExpiredOrFinishedVerificationWithoutStateChange() {
        MerchantPayoutVerification expired = verification(1, 5L, 10L, "PENDING",
                LocalDateTime.now().minusMinutes(1));
        when(verificationMapper.lockById(1L)).thenReturn(expired);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmByMerchant(1L, 10L, new BigDecimal("0.58"), 9L));
        assertEquals(409, ex.getCode());
        assertEquals("PENDING", expired.getStatus());
        verify(verificationMapper, never()).updateById(any());

        MerchantPayoutVerification finished = verification(2, 5L, 10L, "SUCCESS",
                LocalDateTime.now().plusHours(1));
        when(verificationMapper.lockById(2L)).thenReturn(finished);

        ex = assertThrows(BusinessException.class,
                () -> service.confirmByMerchant(2L, 10L, new BigDecimal("0.58"), 9L));
        assertEquals(409, ex.getCode());
    }

    @Test
    void expireOneMarksOnlyStalePendingVerification() {
        MerchantPayoutVerification stale = verification(1, 5L, 10L, "PENDING",
                LocalDateTime.now().minusMinutes(1));
        when(verificationMapper.lockById(1L)).thenReturn(stale);

        assertTrue(service.expireOne(1L));
        assertEquals("EXPIRED", stale.getStatus());
        assertEquals("EXPIRED", stale.getFailureCode());
        verify(verificationMapper).updateById(stale);

        MerchantPayoutVerification active = verification(2, 5L, 10L, "PENDING",
                LocalDateTime.now().plusHours(1));
        when(verificationMapper.lockById(2L)).thenReturn(active);

        assertFalse(service.expireOne(2L));
        assertEquals("PENDING", active.getStatus());
    }

    @Test
    void merchantViewNeverExposesAmountButAdminViewDoes() {
        MerchantPayoutVerification verification = verification(1, 5L, 10L, "PENDING",
                LocalDateTime.now().plusHours(1));
        verification.setAmount(new BigDecimal("0.58"));
        verification.setTransferVoucherNo("VOUCHER-001");

        Map<String, Object> merchantView = service.merchantView(verification);
        assertFalse(merchantView.containsKey("amount"));
        assertFalse(merchantView.containsKey("transferVoucherNo"));
        assertFalse(merchantView.containsValue(new BigDecimal("0.58")));

        Map<String, Object> adminView = service.adminView(verification);
        assertEquals(new BigDecimal("0.58"), adminView.get("amount"));
    }

    private MerchantSettlementAccount pendingAccount(Long id, Long merchantId) {
        MerchantSettlementAccount account = new MerchantSettlementAccount();
        account.setId(id);
        account.setMerchantId(merchantId);
        account.setStatus("PENDING");
        return account;
    }

    private MerchantPayoutVerification verification(long id, Long accountId, Long merchantId,
                                                    String status, LocalDateTime expiresAt) {
        MerchantPayoutVerification verification = new MerchantPayoutVerification();
        verification.setId(id);
        verification.setAccountId(accountId);
        verification.setMerchantId(merchantId);
        verification.setAttemptNo(1);
        verification.setAmount(new BigDecimal("0.58"));
        verification.setStatus(status);
        verification.setExpiresAt(expiresAt);
        return verification;
    }
}
