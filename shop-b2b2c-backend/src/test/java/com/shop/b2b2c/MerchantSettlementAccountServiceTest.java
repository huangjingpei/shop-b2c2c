/*
 * 文件：MerchantSettlementAccountServiceTest.java
 * 用途：T39 收款账户登记、户名=实名主体硬校验、人工核验、默认账户切换与脱敏出参的契约用例；
 *      Sprint 56（问题 3 方案 A）增加核验通过同步 merchant_payment_account 台账的用例。
 * 维护注意：
 *  1. 使用 Mockito 隔离，不启动 Spring、不连接数据库。
 *  2. 户名不一致必须拒绝且无写入副作用；若未来允许人工放行，本用例必须同步修改并说明业务依据。
 *  3. 账号只允许密文+脱敏值落库/出参，禁止明文；merchant_payment_account 同步也只带脱敏账号。
 *  4. 台账同步只写真实值（AUDITING/UNBOUND/sub_mchid 空串），已有行的渠道字段绝不回写；
 *     若未来放开伪造 PASSED/BOUND，本用例必须失败。
 *  5. 真实 MySQL 唯一索引与并发行锁仍由用户手动验证。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import com.shop.b2b2c.entity.MerchantPaymentAccount;
import com.shop.b2b2c.entity.MerchantSettlementAccount;
import com.shop.b2b2c.entity.MerchantSettlementAccountAudit;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.MerchantPaymentAccountMapper;
import com.shop.b2b2c.mapper.MerchantSettlementAccountAuditMapper;
import com.shop.b2b2c.mapper.MerchantSettlementAccountMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.service.CardCipherService;
import com.shop.b2b2c.service.MerchantOnboardingService;
import com.shop.b2b2c.service.MerchantSettlementAccountService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MerchantSettlementAccountServiceTest {

    private final MerchantSettlementAccountMapper accountMapper = mock(MerchantSettlementAccountMapper.class);
    private final MerchantSettlementAccountAuditMapper auditMapper = mock(MerchantSettlementAccountAuditMapper.class);
    private final MerchantOnboardingService onboardingService = mock(MerchantOnboardingService.class);
    private final CardCipherService cipherService = new CardCipherService();
    private final MerchantPaymentAccountMapper paymentAccountMapper = mock(MerchantPaymentAccountMapper.class);
    private final MerchantStoreMapper storeMapper = mock(MerchantStoreMapper.class);
    private final MerchantSettlementAccountService service = new MerchantSettlementAccountService(
            accountMapper, auditMapper, onboardingService, cipherService, paymentAccountMapper, storeMapper);

    @Test
    void registerRequiresApprovedOnboarding() {
        when(onboardingService.findApprovedByMerchantId(10L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.register(10L, registerForm("张三"), 7L));

        assertEquals(409, ex.getCode());
        verify(accountMapper, never()).insert(any());
    }

    @Test
    void holderNameMustEqualVerifiedSubject() {
        when(onboardingService.findApprovedByMerchantId(10L)).thenReturn(approvedApplication("张三"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.register(10L, registerForm("李四"), 7L));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("实名主体"));
        verify(accountMapper, never()).insert(any());
    }

    @Test
    void registerStoresCipherMaskedValueAndFingerprintOnly() {
        when(onboardingService.findApprovedByMerchantId(10L)).thenReturn(approvedApplication("张三"));
        when(accountMapper.countByMerchantId(10L)).thenReturn(0);
        when(accountMapper.findByMerchantIdAndFingerprint(eq(10L), any())).thenReturn(null);

        MerchantSettlementAccount account = service.register(10L, registerForm("张三"), 7L);

        assertEquals("PENDING", account.getStatus());
        assertTrue(account.getAccountNoCipher().startsWith("ENC:v1:"));
        assertFalse(account.getAccountNoCipher().contains("6222021234567890"));
        assertEquals("****7890", account.getAccountNoMasked());
        assertEquals(cipherService.fingerprint("6222021234567890"), account.getAccountFingerprint());
        assertTrue(account.getIsDefault());
        verify(accountMapper).insert(account);
        verify(auditMapper).insert(any(MerchantSettlementAccountAudit.class));
    }

    @Test
    void duplicateAccountIsRejected() {
        when(onboardingService.findApprovedByMerchantId(10L)).thenReturn(approvedApplication("张三"));
        when(accountMapper.findByMerchantIdAndFingerprint(eq(10L), any()))
                .thenReturn(new MerchantSettlementAccount());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.register(10L, registerForm("张三"), 7L));

        assertEquals(409, ex.getCode());
        verify(accountMapper, never()).insert(any());
    }

    @Test
    void registerDuplicateKeyReturns409WithoutLeakingSql() {
        // O2：并发下同指纹/双默认账户由唯一索引兜底，翻译为 409 且不泄漏 SQL。
        when(onboardingService.findApprovedByMerchantId(10L)).thenReturn(approvedApplication("张三"));
        when(accountMapper.countByMerchantId(10L)).thenReturn(0);
        when(accountMapper.findByMerchantIdAndFingerprint(eq(10L), any())).thenReturn(null);
        when(accountMapper.insert(any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("Duplicate entry"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.register(10L, registerForm("张三"), 7L));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("冲突"));
        assertTrue(!ex.getMessage().contains("Duplicate entry"));
    }

    @Test
    void verifyApprovedAccountBecomesDefaultAndDisablesOldDefault() {
        MerchantSettlementAccount account = pendingAccount(5L, 10L, true);
        when(accountMapper.lockById(5L)).thenReturn(account);

        service.verify(5L, true, "人工核对一致", 7L);

        assertEquals("VERIFIED", account.getStatus());
        assertTrue(account.getIsDefault());
        assertNotNull(account.getVerifiedAt());
        assertEquals(7L, account.getVerifiedBy());
        verify(accountMapper).disableOtherDefaults(eq(10L), eq(5L), any());
        verify(accountMapper).updateById(account);
        verify(auditMapper).insert(any(MerchantSettlementAccountAudit.class));
    }

    @Test
    void rejectRequiresReasonAndDoesNotVerify() {
        MerchantSettlementAccount account = pendingAccount(6L, 10L, false);
        when(accountMapper.lockById(6L)).thenReturn(account);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verify(6L, false, " ", 7L));

        assertEquals(400, ex.getCode());
        assertEquals("PENDING", account.getStatus());
        verify(accountMapper, never()).updateById(any());

        service.verify(6L, false, "户名与实名主体不一致", 7L);
        assertEquals("REJECTED", account.getStatus());
        assertEquals("户名与实名主体不一致", account.getRejectReason());
        verify(accountMapper).updateById(account);
    }

    @Test
    void verifyRejectsNonPendingAccount() {
        MerchantSettlementAccount account = pendingAccount(7L, 10L, false);
        account.setStatus("VERIFIED");
        when(accountMapper.lockById(7L)).thenReturn(account);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verify(7L, true, "重复核验", 7L));

        assertEquals(409, ex.getCode());
        verify(accountMapper, never()).updateById(any());
    }

    @Test
    void maskedViewNeverExposesCipher() {
        MerchantSettlementAccount account = pendingAccount(8L, 10L, false);
        account.setAccountNoCipher(cipherService.encrypt("6222021234567890"));
        account.setAccountNoMasked("****7890");

        Map<String, Object> view = service.maskedView(account);

        assertEquals("****7890", view.get("accountNoMasked"));
        assertFalse(view.containsKey("accountNoCipher"));
        assertFalse(view.containsValue("6222021234567890"));
    }

    @Test
    void requestChangeCreatesPendingAccountLinkedToCurrentDefault() {
        // O5：店长发起变更，新账户 PENDING 且指向当前默认账户，不抢占默认位（变更审核期间结算不中断）。
        MerchantSettlementAccount oldDefault = verifiedDefaultAccount(20L, 10L);
        when(accountMapper.findDefaultVerified(10L)).thenReturn(oldDefault);
        when(onboardingService.findApprovedByMerchantId(10L)).thenReturn(approvedApplication("张三"));
        when(accountMapper.findByMerchantIdAndFingerprint(eq(10L), any())).thenReturn(null);
        when(accountMapper.countByMerchantId(10L)).thenReturn(1);

        MerchantSettlementAccount created = service.requestChange(10L, registerForm("张三"), "原卡注销", 9L);

        assertEquals("PENDING", created.getStatus());
        assertEquals(20L, created.getReplacesAccountId());
        assertFalse(created.getIsDefault());
        org.mockito.ArgumentCaptor<MerchantSettlementAccountAudit> captor =
                org.mockito.ArgumentCaptor.forClass(MerchantSettlementAccountAudit.class);
        verify(auditMapper).insert(captor.capture());
        assertEquals("CHANGE_REQUESTED", captor.getValue().getAction());
    }

    @Test
    void requestChangeRequiresExistingVerifiedDefault() {
        when(accountMapper.findDefaultVerified(10L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requestChange(10L, registerForm("张三"), null, 9L));

        assertEquals(409, ex.getCode());
        verify(accountMapper, never()).insert(any(MerchantSettlementAccount.class));
    }

    @Test
    void verifyChangeAccountSwitchesDefaultAndKeepsOldVerified() {
        // O5：变更账户核验通过 -> 新账户 VERIFIED+默认，旧账户保持 VERIFIED 但取消默认并写 effectiveTo（同事务切换）。
        MerchantSettlementAccount change = pendingAccount(21L, 10L, false);
        change.setReplacesAccountId(20L);
        MerchantSettlementAccount oldDefault = verifiedDefaultAccount(20L, 10L);
        when(accountMapper.lockById(21L)).thenReturn(change);
        when(accountMapper.lockById(20L)).thenReturn(oldDefault);

        service.verify(21L, true, "人工核对一致", 7L);

        assertEquals("VERIFIED", change.getStatus());
        assertTrue(change.getIsDefault());
        assertEquals("MANUAL", change.getVerifyMethod());
        assertFalse(oldDefault.getIsDefault());
        assertEquals("VERIFIED", oldDefault.getStatus());
        assertNotNull(oldDefault.getEffectiveTo());
        verify(accountMapper).updateById(oldDefault);
        verify(accountMapper).updateById(change);
        verify(accountMapper, never()).disableOtherDefaults(any(), any(), any());
        org.mockito.ArgumentCaptor<MerchantSettlementAccountAudit> captor =
                org.mockito.ArgumentCaptor.forClass(MerchantSettlementAccountAudit.class);
        verify(auditMapper, times(2)).insert(captor.capture());
        java.util.List<String> actions = captor.getAllValues().stream()
                .map(MerchantSettlementAccountAudit::getAction).collect(java.util.stream.Collectors.toList());
        assertTrue(actions.contains("CHANGE_VERIFIED"));
        assertTrue(actions.contains("CHANGE_SWITCHED"));
    }

    @Test
    void confirmPayoutSuccessMarksVerifyMethodPayout() {
        // O5：打款验证金额匹配后账户通过，verify_method=PAYOUT；非变更账户走常规默认切换。
        MerchantSettlementAccount account = pendingAccount(22L, 10L, false);
        when(accountMapper.lockById(22L)).thenReturn(account);
        when(accountMapper.findDefaultVerified(10L)).thenReturn(null);

        service.confirmPayoutSuccess(22L, 7L);

        assertEquals("VERIFIED", account.getStatus());
        assertEquals("PAYOUT", account.getVerifyMethod());
        assertTrue(account.getIsDefault());
        verify(accountMapper).updateById(account);
    }

    @Test
    void confirmPayoutSuccessRejectsNonPendingAccount() {
        MerchantSettlementAccount account = pendingAccount(23L, 10L, false);
        account.setStatus("REJECTED");
        when(accountMapper.lockById(23L)).thenReturn(account);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmPayoutSuccess(23L, 7L));

        assertEquals(409, ex.getCode());
        verify(accountMapper, never()).updateById(any());
    }

    @Test
    void rejectForPayoutExceededRejectsPendingAccountWithReasonCode() {
        // O5：累计 3 次失败 -> REJECTED + PAYOUT_ATTEMPTS_EXCEEDED，需重新登记或走人工核验。
        MerchantSettlementAccount account = pendingAccount(24L, 10L, false);
        when(accountMapper.lockById(24L)).thenReturn(account);

        service.rejectForPayoutExceeded(24L, 7L);

        assertEquals("REJECTED", account.getStatus());
        assertEquals("PAYOUT_ATTEMPTS_EXCEEDED", account.getRejectReason());
        assertFalse(account.getIsDefault());
        verify(accountMapper).updateById(account);
        org.mockito.ArgumentCaptor<MerchantSettlementAccountAudit> captor =
                org.mockito.ArgumentCaptor.forClass(MerchantSettlementAccountAudit.class);
        verify(auditMapper).insert(captor.capture());
        assertEquals("REJECT", captor.getValue().getAction());
    }

    @Test
    void rejectForPayoutExceededSkipsNonPendingAccount() {
        MerchantSettlementAccount account = pendingAccount(25L, 10L, false);
        account.setStatus("VERIFIED");
        when(accountMapper.lockById(25L)).thenReturn(account);

        service.rejectForPayoutExceeded(25L, 7L);

        assertEquals("VERIFIED", account.getStatus());
        verify(accountMapper, never()).updateById(any());
    }

    @Test
    void verifyApprovedDefaultAccountCreatesPaymentAccountWithRealValues() {
        // Sprint 56：核验通过且成为默认账户时创建 merchant_payment_account 台账，状态全部按真实值。
        MerchantSettlementAccount account = pendingAccount(5L, 10L, true);
        when(accountMapper.lockById(5L)).thenReturn(account);
        MerchantStore store = new MerchantStore();
        store.setId(10L);
        store.setPayChannel("WECHAT");
        when(storeMapper.findById(10L)).thenReturn(Optional.of(store));
        when(paymentAccountMapper.findByMerchantIdAndChannel(10L, "WECHAT")).thenReturn(Optional.empty());

        service.verify(5L, true, "人工核对一致", 7L);

        org.mockito.ArgumentCaptor<MerchantPaymentAccount> captor =
                org.mockito.ArgumentCaptor.forClass(MerchantPaymentAccount.class);
        verify(paymentAccountMapper).insert(captor.capture());
        MerchantPaymentAccount created = captor.getValue();
        assertEquals(10L, created.getMerchantId());
        assertEquals("WECHAT", created.getChannel());
        assertEquals(5L, created.getSettlementAccountId());
        // 不伪造渠道结果：进件仍审核中、渠道侧未绑定、官方未分配二级商户号。
        assertEquals("AUDITING", created.getApplyStatus());
        assertEquals("UNBOUND", created.getBindingStatus());
        assertEquals("", created.getSubMchid());
        assertNull(created.getChannelMerchantId());
        // 账号只同步脱敏值，禁止明文。
        assertEquals("****7890", created.getBankAccountNumber());
        assertFalse(created.getBankAccountNumber().contains("6222021234567890"));
        assertEquals("张三", created.getBankAccountName());
    }

    @Test
    void existingPaymentAccountOnlyUpdatesSettlementReferenceAndKeepsChannelFields() {
        // 已有台账行（含真实渠道字段）时只更新 settlement_account_id 引用，渠道字段原样保留。
        MerchantSettlementAccount account = pendingAccount(5L, 10L, true);
        when(accountMapper.lockById(5L)).thenReturn(account);
        MerchantStore store = new MerchantStore();
        store.setId(10L);
        store.setPayChannel("WECHAT");
        when(storeMapper.findById(10L)).thenReturn(Optional.of(store));
        MerchantPaymentAccount existing = new MerchantPaymentAccount();
        existing.setId(99L);
        existing.setMerchantId(10L);
        existing.setChannel("WECHAT");
        existing.setSubMchid("MCH-REAL");
        existing.setBindingStatus("BOUND");
        existing.setApplyStatus("PASSED");
        existing.setSettlementAccountId(30L);
        when(paymentAccountMapper.findByMerchantIdAndChannel(10L, "WECHAT")).thenReturn(Optional.of(existing));

        service.verify(5L, true, "人工核对一致", 7L);

        assertEquals(5L, existing.getSettlementAccountId());
        assertEquals("MCH-REAL", existing.getSubMchid());
        assertEquals("BOUND", existing.getBindingStatus());
        assertEquals("PASSED", existing.getApplyStatus());
        verify(paymentAccountMapper, never()).insert(any());
        verify(paymentAccountMapper).updateById(existing);
    }

    @Test
    void nonDefaultVerifiedAccountDoesNotTouchPaymentAccount() {
        // 核验通过但不是默认账户（已有其他默认）：不同步台账结算引用。
        MerchantSettlementAccount account = pendingAccount(5L, 10L, false);
        when(accountMapper.lockById(5L)).thenReturn(account);
        when(accountMapper.findDefaultVerified(10L)).thenReturn(verifiedDefaultAccount(20L, 10L));

        service.verify(5L, true, "人工核对一致", 7L);

        assertFalse(account.getIsDefault());
        verifyNoInteractions(paymentAccountMapper);
    }

    @Test
    void changeAccountVerifiedSyncsNewSettlementReference() {
        // O5 变更账户核验通过成为新默认：台账结算引用切换到新账户。
        MerchantSettlementAccount change = pendingAccount(21L, 10L, false);
        change.setReplacesAccountId(20L);
        MerchantSettlementAccount oldDefault = verifiedDefaultAccount(20L, 10L);
        when(accountMapper.lockById(21L)).thenReturn(change);
        when(accountMapper.lockById(20L)).thenReturn(oldDefault);
        MerchantStore store = new MerchantStore();
        store.setId(10L);
        store.setPayChannel("WECHAT");
        when(storeMapper.findById(10L)).thenReturn(Optional.of(store));
        MerchantPaymentAccount existing = new MerchantPaymentAccount();
        existing.setId(99L);
        existing.setMerchantId(10L);
        existing.setChannel("WECHAT");
        existing.setSettlementAccountId(20L);
        when(paymentAccountMapper.findByMerchantIdAndChannel(10L, "WECHAT")).thenReturn(Optional.of(existing));

        service.verify(21L, true, "人工核对一致", 7L);

        assertEquals(21L, existing.getSettlementAccountId());
        verify(paymentAccountMapper).updateById(existing);
        verify(paymentAccountMapper, never()).insert(any());
    }

    private MerchantSettlementAccountService.RegisterForm registerForm(String holderName) {
        MerchantSettlementAccountService.RegisterForm form = new MerchantSettlementAccountService.RegisterForm();
        form.setAccountType("BANK");
        form.setHolderName(holderName);
        form.setBankName("中国工商银行");
        form.setBankBranch("北京分行");
        form.setAccountNo("6222021234567890");
        form.setDefault(false);
        return form;
    }

    private MerchantOnboardingApplication approvedApplication(String subjectName) {
        MerchantOnboardingApplication application = new MerchantOnboardingApplication();
        application.setId(1L);
        application.setMerchantId(10L);
        application.setSubjectType("INDIVIDUAL");
        application.setSubjectName(subjectName);
        application.setStatus("APPROVED");
        return application;
    }

    private MerchantSettlementAccount pendingAccount(Long id, Long merchantId, boolean isDefault) {
        MerchantSettlementAccount account = new MerchantSettlementAccount();
        account.setId(id);
        account.setMerchantId(merchantId);
        account.setAccountType("BANK");
        account.setHolderName("张三");
        account.setAccountNoCipher(cipherService.encrypt("6222021234567890"));
        account.setAccountFingerprint(cipherService.fingerprint("6222021234567890"));
        account.setAccountNoMasked("****7890");
        account.setStatus("PENDING");
        account.setIsDefault(isDefault);
        account.setVersion(0);
        return account;
    }

    private MerchantSettlementAccount verifiedDefaultAccount(Long id, Long merchantId) {
        MerchantSettlementAccount account = pendingAccount(id, merchantId, true);
        account.setStatus("VERIFIED");
        return account;
    }
}
