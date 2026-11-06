/*
 * 文件：MerchantOnboardingServiceTest.java
 * 用途：T39 入驻申请、实名材料密文/脱敏、审核状态机与审核通过开店的契约用例；含 T40 开店 0% 推广费率口径。
 * 维护注意：
 *  1. 使用 Mockito 隔离，不启动 Spring、不连接数据库；加密使用真实 CardCipherService 默认密钥。
 *  2. 证件号只允许密文落库、脱敏出参；若改为明文存储或返回，本用例必须失败。
 *  3. 审核通过才建店/建账号；驳回、补件、撤回不得产生店铺或账号副作用。
 *  4. 0% 费率只允许 PROMOTION 且必须带推广原因；放宽该条件时本用例必须失败。
 *  5. 并发行锁、真实 MySQL 唯一性与三方实名核验仍由用户手动验证。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import com.shop.b2b2c.entity.MerchantOnboardingAudit;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.SysUser;
import com.shop.b2b2c.mapper.MerchantOnboardingApplicationMapper;
import com.shop.b2b2c.mapper.MerchantOnboardingAuditMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.SysUserMapper;
import com.shop.b2b2c.service.CardCipherService;
import com.shop.b2b2c.service.MerchantFeePolicyService;
import com.shop.b2b2c.service.MerchantOnboardingService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantOnboardingServiceTest {

    private final MerchantOnboardingApplicationMapper applicationMapper = mock(MerchantOnboardingApplicationMapper.class);
    private final MerchantOnboardingAuditMapper auditMapper = mock(MerchantOnboardingAuditMapper.class);
    private final MerchantStoreMapper storeMapper = mock(MerchantStoreMapper.class);
    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final CardCipherService cipherService = new CardCipherService();
    private final MerchantFeePolicyService feePolicyService = mock(MerchantFeePolicyService.class);
    private final MerchantOnboardingService service = new MerchantOnboardingService(
            applicationMapper, auditMapper, storeMapper, userMapper, cipherService, feePolicyService);

    @Test
    void individualApplicationStoresCipherAndFingerprintNotPlaintext() {
        MerchantOnboardingService.ApplicationForm form = individualForm();

        MerchantOnboardingApplication created = service.create(form, 7L);

        assertEquals(MerchantOnboardingService.STATUS_DRAFT, created.getStatus());
        assertEquals("MANUAL", created.getVerificationMethod());
        assertNotNull(created.getIdCardCipher());
        assertTrue(created.getIdCardCipher().startsWith("ENC:v1:"));
        assertFalse(created.getIdCardCipher().contains("110101199001011234"));
        assertEquals(cipherService.fingerprint("110101199001011234"), created.getIdCardFingerprint());
        verify(applicationMapper).insert(created);
        verify(auditMapper).insert(any(MerchantOnboardingAudit.class));
    }

    @Test
    void maskedViewNeverExposesCipherOrPlainIdentity() {
        MerchantOnboardingApplication application = approvedApplication(1L, 9L);
        application.setIdCardCipher(cipherService.encrypt("110101199001011234"));
        application.setLicenseNoCipher(cipherService.encrypt("911100001000000000"));

        Map<String, Object> view = service.maskedView(application);

        assertEquals("1101**********1234", view.get("idCardMasked"));
        assertEquals("****0000", view.get("licenseMasked"));
        assertFalse(view.containsKey("idCardCipher"));
        assertFalse(view.containsKey("licenseNoCipher"));
        assertFalse(view.containsValue("110101199001011234"));
    }

    @Test
    void enterpriseSubmitRequiresLicenseAndLegalPerson() {
        MerchantOnboardingService.ApplicationForm form = enterpriseForm();
        form.setLicenseNo(null);
        form.setLegalPerson(null);
        MerchantOnboardingApplication draft = service.create(form, 7L);
        draft.setId(1L);
        when(applicationMapper.lockById(1L)).thenReturn(draft);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.submit(1L, 7L));

        assertEquals(400, ex.getCode());
        assertEquals(MerchantOnboardingService.STATUS_DRAFT, draft.getStatus());
        verify(applicationMapper, never()).updateById(any());
    }

    @Test
    void duplicateActiveSubjectIsRejected() {
        when(applicationMapper.countActiveByFingerprint(eq("INDIVIDUAL"), any(), eq(null))).thenReturn(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(individualForm(), 7L));

        assertEquals(409, ex.getCode());
        verify(applicationMapper, never()).insert(any());
    }

    @Test
    void approveCreatesStoreAndManagerAndWritesAudit() {
        MerchantOnboardingApplication application = submittedApplication(1L);
        when(applicationMapper.lockById(1L)).thenReturn(application);
        when(storeMapper.findByStoreCode("STORE-001")).thenReturn(Optional.empty());
        when(userMapper.findByUsername("merchant001")).thenReturn(Optional.empty());
        when(storeMapper.save(any())).thenAnswer(invocation -> {
            MerchantStore store = invocation.getArgument(0);
            store.setId(99L);
            return store;
        });

        MerchantOnboardingService.Provisioning provisioning = provisioning();
        service.approve(1L, provisioning, "材料齐全", 7L);

        assertEquals(MerchantOnboardingService.STATUS_APPROVED, application.getStatus());
        assertEquals(99L, application.getMerchantId());
        assertEquals("MANUAL_REVIEW", application.getVerificationSource());
        verify(storeMapper).save(any(MerchantStore.class));
        verify(userMapper).save(any(SysUser.class));
        verify(feePolicyService).createInitialPolicy(eq(99L), eq(300), any(), any(), eq(7L));
        verify(applicationMapper).updateById(application);
    }

    @Test
    void rejectDoesNotCreateStoreOrAccount() {
        MerchantOnboardingApplication application = submittedApplication(2L);
        when(applicationMapper.lockById(2L)).thenReturn(application);

        service.reject(2L, "实名材料不一致", 7L);

        assertEquals(MerchantOnboardingService.STATUS_REJECTED, application.getStatus());
        assertEquals("实名材料不一致", application.getRejectReason());
        verify(storeMapper, never()).save(any());
        verify(userMapper, never()).save(any());
        verify(applicationMapper).updateById(application);
    }

    @Test
    void approveRejectsAfterTerminalStatus() {
        MerchantOnboardingApplication application = submittedApplication(3L);
        application.setStatus(MerchantOnboardingService.STATUS_REJECTED);
        when(applicationMapper.lockById(3L)).thenReturn(application);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.approve(3L, provisioning(), "重复审核", 7L));

        assertEquals(409, ex.getCode());
        verify(storeMapper, never()).save(any());
    }

    @Test
    void invalidRateBpsIsRejectedBeforeStoreCreation() {
        MerchantOnboardingApplication application = submittedApplication(4L);
        when(applicationMapper.lockById(4L)).thenReturn(application);
        MerchantOnboardingService.Provisioning provisioning = provisioning();
        provisioning.setRateBps(1200);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.approve(4L, provisioning, "越界费率", 7L));

        assertEquals(400, ex.getCode());
        verify(storeMapper, never()).save(any());
    }

    @Test
    void zeroRateWithoutPromotionReasonIsRejectedBeforeStoreCreation() {
        MerchantOnboardingApplication application = submittedApplication(5L);
        when(applicationMapper.lockById(5L)).thenReturn(application);
        MerchantOnboardingService.Provisioning provisioning = provisioning();
        provisioning.setRateBps(0);
        provisioning.setPromotionReason("  ");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.approve(5L, provisioning, "0费率缺原因", 7L));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("推广原因"));
        verify(storeMapper, never()).save(any());
    }

    @Test
    void zeroRateWithPromotionReasonCreatesPromotionPolicy() {
        MerchantOnboardingApplication application = submittedApplication(6L);
        when(applicationMapper.lockById(6L)).thenReturn(application);
        when(storeMapper.findByStoreCode("STORE-001")).thenReturn(Optional.empty());
        when(userMapper.findByUsername("merchant001")).thenReturn(Optional.empty());
        when(storeMapper.save(any())).thenAnswer(invocation -> {
            MerchantStore store = invocation.getArgument(0);
            store.setId(100L);
            return store;
        });
        MerchantOnboardingService.Provisioning provisioning = provisioning();
        provisioning.setRateBps(0);
        provisioning.setPromotionReason("新店首年推广");

        service.approve(6L, provisioning, "推广期免佣", 7L);

        assertEquals(MerchantOnboardingService.STATUS_APPROVED, application.getStatus());
        verify(feePolicyService).createInitialPolicy(eq(100L), eq(0), eq("新店首年推广"), any(), eq(7L));
    }

    private MerchantOnboardingService.ApplicationForm individualForm() {
        MerchantOnboardingService.ApplicationForm form = new MerchantOnboardingService.ApplicationForm();
        form.setSubjectType("INDIVIDUAL");
        form.setSubjectName("张三");
        form.setIdCardNumber("110101199001011234");
        form.setContactPhone("13800000000");
        return form;
    }

    private MerchantOnboardingService.ApplicationForm enterpriseForm() {
        MerchantOnboardingService.ApplicationForm form = new MerchantOnboardingService.ApplicationForm();
        form.setSubjectType("ENTERPRISE");
        form.setSubjectName("示例科技有限公司");
        form.setIdCardNumber("110101199001011234");
        form.setLicenseNo("911100001000000000");
        form.setLegalPerson("李四");
        form.setContactPhone("13900000000");
        return form;
    }

    private MerchantOnboardingApplication submittedApplication(Long id) {
        MerchantOnboardingApplication application = new MerchantOnboardingApplication();
        application.setId(id);
        application.setSubjectType("INDIVIDUAL");
        application.setSubjectName("张三");
        application.setIdCardCipher(cipherService.encrypt("110101199001011234"));
        application.setIdCardFingerprint(cipherService.fingerprint("110101199001011234"));
        application.setContactPhone("13800000000");
        application.setStatus(MerchantOnboardingService.STATUS_SUBMITTED);
        application.setVerificationMethod("MANUAL");
        application.setVersion(1);
        return application;
    }

    private MerchantOnboardingApplication approvedApplication(Long id, Long merchantId) {
        MerchantOnboardingApplication application = new MerchantOnboardingApplication();
        application.setId(id);
        application.setMerchantId(merchantId);
        application.setSubjectType("INDIVIDUAL");
        application.setSubjectName("张三");
        application.setContactPhone("13800000000");
        application.setStatus(MerchantOnboardingService.STATUS_APPROVED);
        application.setVerificationMethod("MANUAL");
        application.setVerificationSource("MANUAL_REVIEW");
        return application;
    }

    private MerchantOnboardingService.Provisioning provisioning() {
        MerchantOnboardingService.Provisioning provisioning = new MerchantOnboardingService.Provisioning();
        provisioning.setStoreCode("STORE-001");
        provisioning.setStoreName("张三的小店");
        provisioning.setMerchantType(1);
        provisioning.setAdminUsername("merchant001");
        provisioning.setAdminPassword("Password123");
        provisioning.setRateBps(300);
        return provisioning;
    }
}
