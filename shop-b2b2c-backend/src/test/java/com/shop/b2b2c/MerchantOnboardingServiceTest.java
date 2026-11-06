/*
 * 文件：MerchantOnboardingServiceTest.java
 * 用途：T39 入驻申请、实名材料密文/脱敏、审核状态机与审核通过开店的契约用例；含 T40 开店 0% 推广费率口径；
 *      O1 覆盖初审/复审双人复核、原因码字典（T32）、补件重提计数（T08）、OPENED 状态兼容（T29/T35）；
 *      O4 覆盖建店待开通(status=0)、未签协议开通 409（T17）、开通幂等与审计、签齐自动开通；D3 覆盖逾期撤回。
 * 维护注意：
 *  1. 使用 Mockito 隔离，不启动 Spring、不连接数据库；加密使用真实 CardCipherService 默认密钥。
 *  2. 证件号只允许密文落库、脱敏出参；若改为明文存储或返回，本用例必须失败。
 *  3. 审核通过才建店/建账号；驳回、补件、撤回不得产生店铺或账号副作用。
 *  4. 0% 费率只允许 PROMOTION 且必须带推广原因；放宽该条件时本用例必须失败。
 *  5. 并发行锁、真实 MySQL 唯一性与三方实名核验仍由用户手动验证。
 *  6. G31 起审核未显式选复核人时原样传 null，由 FinanceApprovalGuard 区分严格模式 428 与宽松模式同人标记。
 *  7. dualService 固定双人复核开启（生产形态）：初审只推进阶段，终审必须换人；service 固定关闭（开发形态）。
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
import com.shop.b2b2c.service.FinanceApprovalGuard;
import com.shop.b2b2c.service.MerchantFeePolicyService;
import com.shop.b2b2c.service.MerchantOnboardingService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    private final com.shop.b2b2c.service.MerchantAgreementService agreementService =
            mock(com.shop.b2b2c.service.MerchantAgreementService.class);
    /** 开发形态：双人复核关闭，单次 APPROVE 直接通过。 */
    private final MerchantOnboardingService service = new MerchantOnboardingService(
            applicationMapper, auditMapper, storeMapper, userMapper, cipherService, feePolicyService,
            new FinanceApprovalGuard(false, userMapper), agreementService);
    /** 生产形态：双人复核开启，初审只推进阶段，终审必须换不同超管。 */
    private final MerchantOnboardingService dualService = new MerchantOnboardingService(
            applicationMapper, auditMapper, storeMapper, userMapper, cipherService, feePolicyService,
            new FinanceApprovalGuard(true, userMapper), agreementService);

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
        verify(feePolicyService).createInitialPolicy(eq(99L), eq(300), any(), any(), eq(7L), isNull());
        verify(applicationMapper).updateById(application);
    }

    @Test
    void rejectDoesNotCreateStoreOrAccount() {
        MerchantOnboardingApplication application = submittedApplication(2L);
        when(applicationMapper.lockById(2L)).thenReturn(application);

        service.reject(2L, "NAME_ID_MISMATCH", "实名材料不一致", 7L);

        assertEquals(MerchantOnboardingService.STATUS_REJECTED, application.getStatus());
        assertEquals("NAME_ID_MISMATCH", application.getRejectCode());
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
        verify(feePolicyService).createInitialPolicy(eq(100L), eq(0), eq("新店首年推广"), any(), eq(7L), isNull());
    }

    @Test
    void publicApplicationStoresOnlyTokenHashAndIssuesFaceCode() {
        when(applicationMapper.insert(any())).thenAnswer(invocation -> {
            MerchantOnboardingApplication application = invocation.getArgument(0);
            application.setId(10L);
            return 1;
        });

        MerchantOnboardingService.PublicTicket ticket = service.createPublic(individualForm());

        assertEquals(10L, ticket.getApplication().getId());
        assertNotNull(ticket.getApplication().getAccessTokenHash());
        assertNotEquals(ticket.getAccessToken(), ticket.getApplication().getAccessTokenHash());
        assertNotNull(ticket.getApplication().getFaceVerifyCode());
        verify(applicationMapper).updateById(ticket.getApplication());
    }

    @Test
    void approveWithoutProvisioningAutoGeneratesStoreAndAccountFromPhone() {
        MerchantOnboardingApplication application = submittedApplication(11L);
        when(applicationMapper.lockById(11L)).thenReturn(application);
        when(storeMapper.findByStoreCode(anyString())).thenReturn(Optional.empty());
        when(userMapper.findByUsername(anyString())).thenReturn(Optional.empty());
        when(storeMapper.save(any())).thenAnswer(invocation -> {
            MerchantStore store = invocation.getArgument(0);
            store.setId(90L);
            return store;
        });

        service.approve(11L, null, "材料齐全", 7L);

        assertEquals(MerchantOnboardingService.STATUS_APPROVED, application.getStatus());
        assertEquals(90L, application.getMerchantId());
        assertNotNull(application.getInitialPassword());
        assertNotNull(application.getInitialPasswordCipher());
        MerchantStore createdStore = storeCaptor();
        SysUser createdUser = userCaptor();
        assertEquals("13800000000", createdUser.getUsername());
        // D11：审核开店生成的店长账号必须首次登录强制改密。
        assertEquals(Boolean.TRUE, createdUser.getMustChangePassword());
        assertEquals("张三的小店", createdStore.getStoreName());
        assertEquals(1, createdStore.getMerchantType());
        // 初始密码明文只存在瞬时字段，审核列表/详情视图不得回传。
        assertFalse(service.maskedView(application).containsKey("initialPassword"));
    }

    @Test
    void publicStatusRevealsInitialPasswordOnlyOnce() {
        MerchantOnboardingApplication application = approvedApplication(12L, 90L);
        application.setAccessTokenHash(cipherService.fingerprint("query-token"));
        application.setInitialPasswordCipher(cipherService.encrypt("InitialPassword1"));
        when(applicationMapper.lockById(12L)).thenReturn(application);

        Map<String, Object> first = service.publicStatus(12L, "query-token", "13800000000");
        Map<String, Object> second = service.publicStatus(12L, "query-token", "13800000000");

        assertEquals("InitialPassword1", first.get("initialPassword"));
        assertFalse(second.containsKey("initialPassword"));
        assertTrue((Boolean) second.get("initialPasswordRevealed"));
    }

    @Test
    void publicStatusRejectsWrongPhoneEvenWithValidToken() {
        MerchantOnboardingApplication application = approvedApplication(13L, 91L);
        application.setAccessTokenHash(cipherService.fingerprint("query-token"));
        when(applicationMapper.lockById(13L)).thenReturn(application);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.publicStatus(13L, "query-token", "13900000000"));
        assertEquals(404, error.getCode());
    }

    // ---------- O1：初审/复审双人复核（生产形态 dualService） ----------

    @Test
    void dualApprovalPrimaryApproveOnlyAdvancesStageWithoutProvisioning() {
        MerchantOnboardingApplication application = submittedApplication(20L);
        when(applicationMapper.lockById(20L)).thenReturn(application);

        MerchantOnboardingApplication result = dualService.approve(20L, provisioning(), "初审通过", 7L);

        assertEquals(MerchantOnboardingService.STATUS_REVIEWING, result.getStatus());
        assertEquals(MerchantOnboardingService.REVIEW_STAGE_SECONDARY, result.getReviewStage());
        assertEquals(7L, result.getFirstReviewerId());
        // 初审不得产生店铺、账号、初始费率副作用。
        verify(storeMapper, never()).save(any());
        verify(userMapper, never()).save(any());
        verify(feePolicyService, never()).createInitialPolicy(any(), any(), any(), any(), any(), any());
    }

    @Test
    void dualApprovalFinalApproveRejectsSameReviewerAsPrimary() {
        MerchantOnboardingApplication application = submittedApplication(21L);
        application.setStatus(MerchantOnboardingService.STATUS_REVIEWING);
        application.setReviewStage(MerchantOnboardingService.REVIEW_STAGE_SECONDARY);
        application.setFirstReviewerId(7L);
        when(applicationMapper.lockById(21L)).thenReturn(application);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dualService.approve(21L, provisioning(), "同人终审", 7L));

        assertEquals(403, ex.getCode());
        verify(storeMapper, never()).save(any());
    }

    @Test
    void dualApprovalFinalApproveByDifferentReviewerProvisionsStore() {
        MerchantOnboardingApplication application = submittedApplication(22L);
        application.setStatus(MerchantOnboardingService.STATUS_REVIEWING);
        application.setReviewStage(MerchantOnboardingService.REVIEW_STAGE_SECONDARY);
        application.setFirstReviewerId(7L);
        when(applicationMapper.lockById(22L)).thenReturn(application);
        when(storeMapper.findByStoreCode("STORE-001")).thenReturn(Optional.empty());
        when(userMapper.findByUsername("merchant001")).thenReturn(Optional.empty());
        when(storeMapper.save(any())).thenAnswer(invocation -> {
            MerchantStore store = invocation.getArgument(0);
            store.setId(122L);
            return store;
        });

        MerchantOnboardingService.Provisioning provisioning = provisioning();
        provisioning.setRateConfirmedBy(7L);
        dualService.approve(22L, provisioning, "复审通过", 8L);

        assertEquals(MerchantOnboardingService.STATUS_APPROVED, application.getStatus());
        assertEquals(MerchantOnboardingService.REVIEW_STAGE_PASSED, application.getReviewStage());
        assertEquals(7L, application.getFirstReviewerId());
        assertEquals(122L, application.getMerchantId());
        verify(feePolicyService).createInitialPolicy(eq(122L), eq(300), any(), any(), eq(8L), eq(7L));
    }

    @Test
    void dualApprovalRejectAtSecondaryStageRequiresDifferentReviewer() {
        MerchantOnboardingApplication application = submittedApplication(23L);
        application.setStatus(MerchantOnboardingService.STATUS_REVIEWING);
        application.setReviewStage(MerchantOnboardingService.REVIEW_STAGE_SECONDARY);
        application.setFirstReviewerId(7L);
        when(applicationMapper.lockById(23L)).thenReturn(application);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dualService.reject(23L, "RISK_CONTROL", null, 7L));
        assertEquals(403, ex.getCode());
        assertEquals(MerchantOnboardingService.STATUS_REVIEWING, application.getStatus());

        dualService.reject(23L, "RISK_CONTROL", null, 8L);
        assertEquals(MerchantOnboardingService.STATUS_REJECTED, application.getStatus());
        assertEquals("RISK_CONTROL", application.getRejectCode());
    }

    @Test
    void singleModeApproveSetsPassedStageAndFirstReviewer() {
        MerchantOnboardingApplication application = submittedApplication(24L);
        when(applicationMapper.lockById(24L)).thenReturn(application);
        when(storeMapper.findByStoreCode("STORE-001")).thenReturn(Optional.empty());
        when(userMapper.findByUsername("merchant001")).thenReturn(Optional.empty());
        when(storeMapper.save(any())).thenAnswer(invocation -> {
            MerchantStore store = invocation.getArgument(0);
            store.setId(124L);
            return store;
        });

        service.approve(24L, provisioning(), "材料齐全", 7L);

        assertEquals(MerchantOnboardingService.REVIEW_STAGE_PASSED, application.getReviewStage());
        assertEquals(7L, application.getFirstReviewerId());
    }

    @Test
    void startReviewMarksPrimaryStageAndFirstReviewer() {
        MerchantOnboardingApplication application = submittedApplication(25L);
        when(applicationMapper.lockById(25L)).thenReturn(application);

        service.startReview(25L, 7L);

        assertEquals(MerchantOnboardingService.STATUS_REVIEWING, application.getStatus());
        assertEquals(MerchantOnboardingService.REVIEW_STAGE_PRIMARY, application.getReviewStage());
        assertEquals(7L, application.getFirstReviewerId());
    }

    // ---------- O1：驳回/补件原因码（T32） ----------

    @Test
    void rejectRequiresReasonCode() {
        MerchantOnboardingApplication application = submittedApplication(26L);
        when(applicationMapper.lockById(26L)).thenReturn(application);

        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.reject(26L, null, "有原因但无原因码", 7L));
        assertEquals(400, missing.getCode());

        BusinessException invalid = assertThrows(BusinessException.class,
                () -> service.reject(26L, "NOT_A_CODE", "随意原因码", 7L));
        assertEquals(400, invalid.getCode());

        BusinessException otherWithoutReason = assertThrows(BusinessException.class,
                () -> service.reject(26L, "OTHER", "  ", 7L));
        assertEquals(400, otherWithoutReason.getCode());

        // 非 OTHER 原因码可以不写自由文本。
        service.reject(26L, "MATERIAL_UNCLEAR", null, 7L);
        assertEquals(MerchantOnboardingService.STATUS_REJECTED, application.getStatus());
        assertEquals("MATERIAL_UNCLEAR", application.getRejectCode());
        assertEquals(null, application.getRejectReason());
    }

    @Test
    void supplementRequiresReasonCodeAndResetsReviewStage() {
        MerchantOnboardingApplication application = submittedApplication(27L);
        application.setStatus(MerchantOnboardingService.STATUS_REVIEWING);
        application.setReviewStage(MerchantOnboardingService.REVIEW_STAGE_PRIMARY);
        application.setFirstReviewerId(7L);
        when(applicationMapper.lockById(27L)).thenReturn(application);

        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.requireSupplement(27L, "", "缺原因码", 7L));
        assertEquals(400, missing.getCode());

        service.requireSupplement(27L, "MATERIAL_MISSING", "请补充营业执照", 7L);

        assertEquals(MerchantOnboardingService.STATUS_SUPPLEMENT_REQUIRED, application.getStatus());
        assertEquals("MATERIAL_MISSING", application.getRejectCode());
        // 补件后重新排队：初审/复审进度作废。
        assertEquals(MerchantOnboardingService.REVIEW_STAGE_NONE, application.getReviewStage());
        assertEquals(null, application.getFirstReviewerId());
    }

    // ---------- O1：补件重提计数（T08） ----------

    @Test
    void resubmitFromSupplementIncrementsResubmitCount() {
        MerchantOnboardingApplication application = submittedApplication(28L);
        application.setStatus(MerchantOnboardingService.STATUS_SUPPLEMENT_REQUIRED);
        application.setResubmitCount(0);
        when(applicationMapper.lockById(28L)).thenReturn(application);

        service.submit(28L, null);

        assertEquals(MerchantOnboardingService.STATUS_SUBMITTED, application.getStatus());
        assertEquals(1, application.getResubmitCount());
    }

    @Test
    void submitFromDraftDoesNotChangeResubmitCount() {
        MerchantOnboardingApplication application = submittedApplication(29L);
        application.setStatus(MerchantOnboardingService.STATUS_DRAFT);
        application.setResubmitCount(0);
        when(applicationMapper.lockById(29L)).thenReturn(application);

        service.submit(29L, null);

        assertEquals(0, application.getResubmitCount());
    }

    // ---------- O1：OPENED 状态兼容（T29/T35） ----------

    @Test
    void publicStatusRevealsInitialPasswordOnceForOpenedApplication() {
        MerchantOnboardingApplication application = approvedApplication(30L, 90L);
        application.setStatus(MerchantOnboardingService.STATUS_OPENED);
        application.setAccessTokenHash(cipherService.fingerprint("query-token"));
        application.setInitialPasswordCipher(cipherService.encrypt("InitialPassword1"));
        when(applicationMapper.lockById(30L)).thenReturn(application);

        Map<String, Object> first = service.publicStatus(30L, "query-token", "13800000000");
        Map<String, Object> second = service.publicStatus(30L, "query-token", "13800000000");

        assertEquals("InitialPassword1", first.get("initialPassword"));
        assertFalse(second.containsKey("initialPassword"));
    }

    // ---------- O2：主体防重唯一索引兜底 ----------

    @Test
    void createDuplicateSubjectConflictReturns409WithoutLeakingSql() {
        MerchantOnboardingService.ApplicationForm form = individualForm();
        when(applicationMapper.insert(any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("Duplicate entry"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(form, 7L));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("进行中的入驻申请"));
        assertTrue(!ex.getMessage().contains("Duplicate entry"));
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void mapperQueriesMustCoverOpenedStatus() throws Exception {
        // T35：开通后初始费率查询、收款账户登记、主体防重都必须把 OPENED 视为"已审核/进行中"。
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/shop/b2b2c/mapper/MerchantOnboardingApplicationMapper.java"));
        assertTrue(source.contains("status IN ('APPROVED','OPENED')"),
                "findApprovedByMerchantId 必须同时覆盖 APPROVED 与 OPENED");
        assertTrue(source.contains("'APPROVED','OPENED'")
                        && source.contains("countActiveByFingerprint"),
                "主体防重查询必须把 OPENED 计入进行中申请");
    }

    // ---------- O4：开通闸门（T17/T35） ----------

    @Test
    void approveCreatesStoreInPendingStatusUntilOpened() {
        // O4：审核通过只代表资质合格，店铺必须先落 status=0 待开通，签齐协议或超管 open 后才营业。
        MerchantOnboardingApplication application = submittedApplication(41L);
        when(applicationMapper.lockById(41L)).thenReturn(application);

        service.approve(41L, provisioning(), null, 9L);

        assertEquals(0, storeCaptor().getStatus());
        assertEquals(MerchantOnboardingService.STATUS_APPROVED, application.getStatus());
    }

    @Test
    void openRequiresApprovedStatusAndAllConsents() {
        // T17：未审核通过不得开通。
        MerchantOnboardingApplication draft = submittedApplication(42L);
        draft.setStatus(MerchantOnboardingService.STATUS_DRAFT);
        when(applicationMapper.lockById(42L)).thenReturn(draft);
        assertEquals(409, org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> service.open(42L, 9L, null)).getCode());
        org.mockito.Mockito.verifyNoInteractions(agreementService);

        // T17：APPROVED 但协议未签齐同样 409，且不触碰店铺。
        MerchantOnboardingApplication approved = approvedApplication(42L, 99L);
        approved.setAgreementRequired(true);
        when(applicationMapper.lockById(42L)).thenReturn(approved);
        when(agreementService.hasAllRequiredConsents(42L)).thenReturn(false);
        assertEquals(409, org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> service.open(42L, 9L, null)).getCode());
        verify(storeMapper, never()).openIfPending(any());
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void openSetsStoreOpenAndApplicationOpenedIdempotently() {
        MerchantOnboardingApplication approved = approvedApplication(43L, 99L);
        approved.setAgreementRequired(true);
        when(applicationMapper.lockById(43L)).thenReturn(approved);
        when(agreementService.hasAllRequiredConsents(43L)).thenReturn(true);
        MerchantStore store = new MerchantStore();
        store.setId(99L);
        store.setStatus(0);
        when(storeMapper.findById(99L)).thenReturn(Optional.of(store));
        when(storeMapper.openIfPending(99L)).thenReturn(1);

        MerchantOnboardingApplication opened = service.open(43L, 9L, null);

        assertEquals(MerchantOnboardingService.STATUS_OPENED, opened.getStatus());
        assertNotNull(opened.getOpenedAt());
        verify(storeMapper).openIfPending(99L);
        org.mockito.ArgumentCaptor<MerchantOnboardingAudit> audit =
                org.mockito.ArgumentCaptor.forClass(MerchantOnboardingAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals(MerchantOnboardingService.ACTION_OPEN, audit.getValue().getAction());
        assertEquals(MerchantOnboardingService.STATUS_APPROVED, audit.getValue().getFromStatus());
        assertEquals(MerchantOnboardingService.STATUS_OPENED, audit.getValue().getToStatus());
        assertEquals(9L, audit.getValue().getOperatorId().longValue());

        // 幂等：重复开通直接返回，不再 CAS 店铺、不再写审计。
        MerchantOnboardingApplication again = service.open(43L, 9L, null);
        assertEquals(MerchantOnboardingService.STATUS_OPENED, again.getStatus());
        verify(storeMapper, org.mockito.Mockito.times(1)).openIfPending(any());
        verify(auditMapper, org.mockito.Mockito.times(1)).insert(any());
    }

    @Test
    void openIfConsentsCompleteIgnoresNonApprovedAndOpensApprovedAsSystem() {
        // 签署发生在提交前（DRAFT/SUBMITTED）时不得提前开通。
        MerchantOnboardingApplication draft = submittedApplication(44L);
        draft.setStatus(MerchantOnboardingService.STATUS_DRAFT);
        when(applicationMapper.lockById(44L)).thenReturn(draft);
        service.openIfConsentsComplete(44L);
        verify(storeMapper, never()).openIfPending(any());
        verify(auditMapper, never()).insert(any());

        // APPROVED 且协议齐全：系统自动开通，审计操作人为空。
        org.mockito.Mockito.reset(applicationMapper, auditMapper, storeMapper);
        MerchantOnboardingApplication approved = approvedApplication(44L, 99L);
        approved.setAgreementRequired(true);
        when(applicationMapper.lockById(44L)).thenReturn(approved);
        when(agreementService.hasAllRequiredConsents(44L)).thenReturn(true);
        MerchantStore store = new MerchantStore();
        store.setId(99L);
        store.setStatus(0);
        when(storeMapper.findById(99L)).thenReturn(Optional.of(store));
        when(storeMapper.openIfPending(99L)).thenReturn(1);

        service.openIfConsentsComplete(44L);

        assertEquals(MerchantOnboardingService.STATUS_OPENED, approved.getStatus());
        org.mockito.ArgumentCaptor<MerchantOnboardingAudit> audit =
                org.mockito.ArgumentCaptor.forClass(MerchantOnboardingAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals(MerchantOnboardingService.ACTION_OPEN, audit.getValue().getAction());
        org.junit.jupiter.api.Assertions.assertNull(audit.getValue().getOperatorId());
    }

    // ---------- D3：逾期撤回 ----------

    @Test
    void expireStaleApplicationWithdrawsApprovedWithSystemAudit() {
        MerchantOnboardingApplication approved = approvedApplication(45L, 99L);
        when(applicationMapper.lockById(45L)).thenReturn(approved);

        assertTrue(service.expireStaleApplication(45L));

        assertEquals(MerchantOnboardingService.STATUS_WITHDRAWN, approved.getStatus());
        org.mockito.ArgumentCaptor<MerchantOnboardingAudit> audit =
                org.mockito.ArgumentCaptor.forClass(MerchantOnboardingAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals(MerchantOnboardingService.ACTION_EXPIRE, audit.getValue().getAction());
        assertEquals(MerchantOnboardingService.STATUS_WITHDRAWN, audit.getValue().getToStatus());
        org.junit.jupiter.api.Assertions.assertNull(audit.getValue().getOperatorId());
    }

    @Test
    void expireStaleApplicationSkipsTerminalAndInFlightStatuses() {
        for (String status : new String[]{
                MerchantOnboardingService.STATUS_DRAFT,
                MerchantOnboardingService.STATUS_SUBMITTED,
                MerchantOnboardingService.STATUS_OPENED,
                MerchantOnboardingService.STATUS_WITHDRAWN}) {
            org.mockito.Mockito.reset(applicationMapper, auditMapper);
            MerchantOnboardingApplication application = approvedApplication(46L, 99L);
            application.setStatus(status);
            when(applicationMapper.lockById(46L)).thenReturn(application);

            org.junit.jupiter.api.Assertions.assertFalse(service.expireStaleApplication(46L));

            verify(applicationMapper, never()).updateById(any());
            verify(auditMapper, never()).insert(any());
        }
    }

    private MerchantStore storeCaptor() {
        org.mockito.ArgumentCaptor<MerchantStore> captor =
                org.mockito.ArgumentCaptor.forClass(MerchantStore.class);
        verify(storeMapper).save(captor.capture());
        return captor.getValue();
    }

    private SysUser userCaptor() {
        org.mockito.ArgumentCaptor<SysUser> captor = org.mockito.ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).save(captor.capture());
        return captor.getValue();
    }

    private MerchantOnboardingService.ApplicationForm individualForm() {
        MerchantOnboardingService.ApplicationForm form = new MerchantOnboardingService.ApplicationForm();
        form.setSubjectType("INDIVIDUAL");
        form.setSubjectName("张三");
        form.setStoreName("张三的小店");
        form.setIdCardNumber("110101199001011234");
        form.setContactPhone("13800000000");
        return form;
    }

    private MerchantOnboardingService.ApplicationForm enterpriseForm() {
        MerchantOnboardingService.ApplicationForm form = new MerchantOnboardingService.ApplicationForm();
        form.setSubjectType("ENTERPRISE");
        form.setSubjectName("示例科技有限公司");
        form.setStoreName("示例科技小店");
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
        application.setStoreName("张三的小店");
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
