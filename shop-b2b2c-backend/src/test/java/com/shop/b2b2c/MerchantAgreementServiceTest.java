/*
 * 文件：MerchantAgreementServiceTest.java
 * 用途：O3 协议版本发布/下线、签署校验（版本存在/已生效/哈希一致）、幂等重复签署、agreed_at 盖章与
 *       append-only 结构的契约用例。
 * 维护注意：
 *  1. 使用 Mockito 隔离，不启动 Spring、不连接数据库；Mapper 层 SQL 行为由用户手动验证。
 *  2. 重复版本发布命中 uk_agreement_type_version 必须翻译为 409 且事务回滚；重复签署同一版本必须幂等成功且不重复写证据。
 *  3. 同意记录 append-only 是开通闸门合规前提：静态断言禁止 Consent Mapper 出现 UPDATE/DELETE。
 *  4. agreed_at 只在"全部必需协议签署完成"时写一次；版本升级不清空，完整性由 hasAllRequiredConsents 实时判定。
 *  5. O4：每次成功签署后必须经 ObjectProvider 钩子调用 openIfConsentsComplete（同事务自动开通）；
 *     既有用例 provider 默认返回 null 不触发钩子，仅专门用例验证钩子调用。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantAgreementConsent;
import com.shop.b2b2c.entity.MerchantAgreementVersion;
import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import com.shop.b2b2c.mapper.MerchantAgreementConsentMapper;
import com.shop.b2b2c.mapper.MerchantAgreementVersionMapper;
import com.shop.b2b2c.mapper.MerchantOnboardingApplicationMapper;
import com.shop.b2b2c.service.MerchantAgreementService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantAgreementServiceTest {

    private final MerchantAgreementVersionMapper versionMapper = mock(MerchantAgreementVersionMapper.class);
    private final MerchantAgreementConsentMapper consentMapper = mock(MerchantAgreementConsentMapper.class);
    private final MerchantOnboardingApplicationMapper applicationMapper = mock(MerchantOnboardingApplicationMapper.class);
    /** O4 自动开通钩子提供者；默认 getIfAvailable 返回 null 不触发钩子，专项用例再桩出服务。 */
    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<com.shop.b2b2c.service.MerchantOnboardingService> onboardingProvider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
    private final MerchantAgreementService service = new MerchantAgreementService(
            versionMapper, consentMapper, applicationMapper, onboardingProvider);

    // ---------- 发布/下线 ----------

    @Test
    void publishOfflinesPreviousVersionAndInsertsNew() {
        MerchantAgreementService.PublishForm form = publishForm("MERCHANT_SERVICE", "v2026.11", "hash-a");

        MerchantAgreementVersion version = service.publish(form, 9L);

        verify(versionMapper).offlinePublishedByType("MERCHANT_SERVICE");
        ArgumentCaptor<MerchantAgreementVersion> captor = ArgumentCaptor.forClass(MerchantAgreementVersion.class);
        verify(versionMapper).insert(captor.capture());
        MerchantAgreementVersion inserted = captor.getValue();
        assertEquals("MERCHANT_SERVICE", inserted.getProtocolType());
        assertEquals("v2026.11", inserted.getVersion());
        assertEquals("hash-a", inserted.getContentHash());
        assertEquals(MerchantAgreementService.STATUS_PUBLISHED, inserted.getStatus());
        assertEquals(9L, inserted.getCreatedBy());
        assertNotNull(inserted.getEffectiveFrom());
        assertEquals(version, inserted);
    }

    @Test
    void publishRejectsBlankRequiredFields() {
        MerchantAgreementService.PublishForm noVersion = publishForm("PRIVACY", " ", "hash");
        BusinessException ex1 = assertThrows(BusinessException.class, () -> service.publish(noVersion, 1L));
        assertEquals(400, ex1.getCode());

        MerchantAgreementService.PublishForm noTitle = publishForm("PRIVACY", "v1", "hash");
        noTitle.setTitle(" ");
        BusinessException ex2 = assertThrows(BusinessException.class, () -> service.publish(noTitle, 1L));
        assertEquals(400, ex2.getCode());

        MerchantAgreementService.PublishForm noHash = publishForm("PRIVACY", "v1", " ");
        BusinessException ex3 = assertThrows(BusinessException.class, () -> service.publish(noHash, 1L));
        assertEquals(400, ex3.getCode());

        BusinessException ex4 = assertThrows(BusinessException.class,
                () -> service.publish(publishForm("UNKNOWN_TYPE", "v1", "hash"), 1L));
        assertEquals(400, ex4.getCode());

        verify(versionMapper, never()).insert(any());
    }

    @Test
    void publishDuplicateVersionReturns409() {
        when(versionMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_agreement_type_version"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.publish(publishForm("SETTLEMENT", "v1", "hash"), 1L));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("版本号已存在"));
    }

    @Test
    void offlineIsIdempotentForOfflineVersion() {
        MerchantAgreementVersion offline = published("PRIVACY", "v1", "hash");
        offline.setStatus(MerchantAgreementService.STATUS_OFFLINE);
        when(versionMapper.selectById(5L)).thenReturn(offline);

        MerchantAgreementVersion result = service.offline(5L);

        assertEquals(MerchantAgreementService.STATUS_OFFLINE, result.getStatus());
        verify(versionMapper, never()).updateById(any());
    }

    @Test
    void offlineMissingVersionReturns404() {
        when(versionMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.offline(99L));

        assertEquals(404, ex.getCode());
    }

    // ---------- 签署校验 ----------

    @Test
    void consentRejectsUnknownVersion() {
        when(versionMapper.findByTypeAndVersion("PRIVACY", "v9")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consent(1L, null, "PRIVACY", "v9", "hash", "张三", "1.1.1.1", "UA"));

        assertEquals(404, ex.getCode());
        verify(consentMapper, never()).insert(any());
    }

    @Test
    void consentRejectsOfflineVersion() {
        MerchantAgreementVersion record = published("PRIVACY", "v1", "hash");
        record.setStatus(MerchantAgreementService.STATUS_OFFLINE);
        when(versionMapper.findByTypeAndVersion("PRIVACY", "v1")).thenReturn(record);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consent(1L, null, "PRIVACY", "v1", "hash", "张三", "1.1.1.1", "UA"));

        assertEquals(409, ex.getCode());
        verify(consentMapper, never()).insert(any());
    }

    @Test
    void consentRejectsFutureEffectiveVersion() {
        MerchantAgreementVersion record = published("PRIVACY", "v1", "hash");
        record.setEffectiveFrom(LocalDateTime.now().plusDays(1));
        when(versionMapper.findByTypeAndVersion("PRIVACY", "v1")).thenReturn(record);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consent(1L, null, "PRIVACY", "v1", "hash", "张三", null, null));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("未生效或已下线"));
        verify(consentMapper, never()).insert(any());
    }

    @Test
    void consentRejectsHashMismatch() {
        when(versionMapper.findByTypeAndVersion("PRIVACY", "v1")).thenReturn(published("PRIVACY", "v1", "hash-new"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consent(1L, null, "PRIVACY", "v1", "hash-old", "张三", "1.1.1.1", "UA"));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("协议版本已更新"));
        verify(consentMapper, never()).insert(any());
    }

    @Test
    void consentPersistsEvidenceWithIpAndUserAgent() {
        when(versionMapper.findByTypeAndVersion("PRIVACY", "v1")).thenReturn(published("PRIVACY", "v1", "hash"));
        when(consentMapper.countByApplicationAndTypeAndVersion(1L, "PRIVACY", "v1")).thenReturn(0, 1);
        when(versionMapper.findAllPublished()).thenReturn(List.of(published("PRIVACY", "v1", "hash")));
        MerchantOnboardingApplication application = new MerchantOnboardingApplication();
        application.setId(1L);
        application.setAgreementRequired(Boolean.TRUE);
        when(applicationMapper.selectById(1L)).thenReturn(application);

        MerchantAgreementConsent consent = service.consent(
                1L, 10L, "privacy", "v1", "hash", "张三", "203.0.113.8", "Mozilla/5.0");

        ArgumentCaptor<MerchantAgreementConsent> captor = ArgumentCaptor.forClass(MerchantAgreementConsent.class);
        verify(consentMapper).insert(captor.capture());
        MerchantAgreementConsent inserted = captor.getValue();
        assertEquals(1L, inserted.getApplicationId());
        assertEquals(10L, inserted.getMerchantId());
        assertEquals("PRIVACY", inserted.getProtocolType());
        assertEquals("v1", inserted.getProtocolVersion());
        assertEquals("hash", inserted.getContentHash());
        assertEquals("张三", inserted.getSubjectName());
        assertEquals("CHECKBOX", inserted.getConsentMethod());
        assertEquals("203.0.113.8", inserted.getIp());
        assertEquals("Mozilla/5.0", inserted.getUserAgent());
        assertNotNull(inserted.getAgreedAt());
        assertEquals(consent, inserted);
    }

    @Test
    void consentTriggersAutoOpenHookAfterSuccessfulInsert() {
        // O4：成功签署后同事务经钩子尝试自动开通；非 APPROVED 由服务侧静默跳过，本用例只验证钩子被调用。
        com.shop.b2b2c.service.MerchantOnboardingService onboarding =
                mock(com.shop.b2b2c.service.MerchantOnboardingService.class);
        when(onboardingProvider.getIfAvailable()).thenReturn(onboarding);
        when(versionMapper.findByTypeAndVersion("PRIVACY", "v1")).thenReturn(published("PRIVACY", "v1", "hash"));
        when(consentMapper.countByApplicationAndTypeAndVersion(1L, "PRIVACY", "v1")).thenReturn(0, 1);
        when(versionMapper.findAllPublished()).thenReturn(List.of(published("PRIVACY", "v1", "hash")));
        MerchantOnboardingApplication application = new MerchantOnboardingApplication();
        application.setId(1L);
        application.setAgreementRequired(Boolean.TRUE);
        when(applicationMapper.selectById(1L)).thenReturn(application);

        service.consent(1L, 10L, "PRIVACY", "v1", "hash", "张三", null, null);

        verify(onboarding).openIfConsentsComplete(1L);
    }

    @Test
    void idempotentDuplicateConsentDoesNotTriggerAutoOpenHook() {
        // 重复签署走幂等返回，不再触发开通钩子，避免重复审计。
        MerchantAgreementConsent existing = new MerchantAgreementConsent();
        existing.setApplicationId(1L);
        existing.setProtocolType("PRIVACY");
        existing.setProtocolVersion("v1");
        when(versionMapper.findByTypeAndVersion("PRIVACY", "v1")).thenReturn(published("PRIVACY", "v1", "hash"));
        when(consentMapper.countByApplicationAndTypeAndVersion(1L, "PRIVACY", "v1")).thenReturn(1);
        when(consentMapper.findByApplicationId(1L)).thenReturn(List.of(existing));

        service.consent(1L, null, "PRIVACY", "v1", "hash", "张三", null, null);

        verify(onboardingProvider, never()).getIfAvailable();
    }

    @Test
    void consentIsIdempotentForRepeatedSameVersion() {
        MerchantAgreementConsent existing = new MerchantAgreementConsent();
        existing.setApplicationId(1L);
        existing.setProtocolType("PRIVACY");
        existing.setProtocolVersion("v1");
        when(versionMapper.findByTypeAndVersion("PRIVACY", "v1")).thenReturn(published("PRIVACY", "v1", "hash"));
        when(consentMapper.countByApplicationAndTypeAndVersion(1L, "PRIVACY", "v1")).thenReturn(1);
        when(consentMapper.findByApplicationId(1L)).thenReturn(List.of(existing));

        MerchantAgreementConsent consent = service.consent(1L, null, "PRIVACY", "v1", "hash", "张三", null, null);

        assertEquals(existing, consent);
        verify(consentMapper, never()).insert(any());
    }

    @Test
    void consentDuplicateKeyReturnsExistingEvidence() {
        MerchantAgreementConsent existing = new MerchantAgreementConsent();
        existing.setApplicationId(1L);
        existing.setProtocolType("PRIVACY");
        existing.setProtocolVersion("v1");
        when(versionMapper.findByTypeAndVersion("PRIVACY", "v1")).thenReturn(published("PRIVACY", "v1", "hash"));
        when(consentMapper.countByApplicationAndTypeAndVersion(1L, "PRIVACY", "v1")).thenReturn(0);
        when(consentMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_consent_app_protocol"));
        when(consentMapper.findByApplicationId(1L)).thenReturn(List.of(existing));

        MerchantAgreementConsent consent = service.consent(1L, null, "PRIVACY", "v1", "hash", "张三", null, null);

        assertEquals(existing, consent);
    }

    // ---------- agreed_at 盖章 ----------

    @Test
    void agreedAtStampedOnceWhenAllRequiredConsentsComplete() {
        when(versionMapper.findByTypeAndVersion("PRIVACY", "v1")).thenReturn(published("PRIVACY", "v1", "hash"));
        when(consentMapper.countByApplicationAndTypeAndVersion(1L, "PRIVACY", "v1")).thenReturn(0, 1);
        when(versionMapper.findAllPublished()).thenReturn(List.of(published("PRIVACY", "v1", "hash")));
        MerchantOnboardingApplication application = new MerchantOnboardingApplication();
        application.setId(1L);
        application.setAgreementRequired(Boolean.TRUE);
        when(applicationMapper.selectById(1L)).thenReturn(application);

        service.consent(1L, null, "PRIVACY", "v1", "hash", "张三", null, null);

        ArgumentCaptor<MerchantOnboardingApplication> captor =
                ArgumentCaptor.forClass(MerchantOnboardingApplication.class);
        verify(applicationMapper).updateById(captor.capture());
        assertNotNull(captor.getValue().getAgreedAt());
    }

    @Test
    void agreedAtNotStampedWhenOtherProtocolMissing() {
        when(versionMapper.findByTypeAndVersion("PRIVACY", "v1")).thenReturn(published("PRIVACY", "v1", "hash"));
        when(consentMapper.countByApplicationAndTypeAndVersion(1L, "PRIVACY", "v1")).thenReturn(0, 1);
        when(versionMapper.findAllPublished()).thenReturn(List.of(
                published("PRIVACY", "v1", "hash"), published("SETTLEMENT", "v1", "hash2")));
        when(consentMapper.countByApplicationAndTypeAndVersion(1L, "SETTLEMENT", "v1")).thenReturn(0);

        service.consent(1L, null, "PRIVACY", "v1", "hash", "张三", null, null);

        verify(applicationMapper, never()).updateById(any());
    }

    @Test
    void agreedAtNotStampedWhenAlreadyStamped() {
        when(versionMapper.findByTypeAndVersion("PRIVACY", "v1")).thenReturn(published("PRIVACY", "v1", "hash"));
        when(consentMapper.countByApplicationAndTypeAndVersion(1L, "PRIVACY", "v1")).thenReturn(0, 1);
        when(versionMapper.findAllPublished()).thenReturn(List.of(published("PRIVACY", "v1", "hash")));
        MerchantOnboardingApplication application = new MerchantOnboardingApplication();
        application.setId(1L);
        application.setAgreementRequired(Boolean.TRUE);
        application.setAgreedAt(LocalDateTime.now().minusDays(1));
        when(applicationMapper.selectById(1L)).thenReturn(application);

        service.consent(1L, null, "PRIVACY", "v1", "hash", "张三", null, null);

        verify(applicationMapper, never()).updateById(any());
    }

    // ---------- 完整性判定 ----------

    @Test
    void hasAllRequiredConsentsTrueWhenNoPublishedProtocols() {
        when(versionMapper.findAllPublished()).thenReturn(List.of());

        assertTrue(service.hasAllRequiredConsents(1L));
    }

    @Test
    void hasAllRequiredConsentsFalseWhenCurrentVersionUnsigned() {
        when(versionMapper.findAllPublished()).thenReturn(List.of(published("PRIVACY", "v2", "hash")));
        when(consentMapper.countByApplicationAndTypeAndVersion(1L, "PRIVACY", "v2")).thenReturn(0);

        assertFalse(service.hasAllRequiredConsents(1L));
    }

    @Test
    void listPublishedKeepsLatestPerType() {
        MerchantAgreementVersion newer = published("PRIVACY", "v2", "hash2");
        MerchantAgreementVersion older = published("PRIVACY", "v1", "hash1");
        when(versionMapper.findAllPublished()).thenReturn(List.of(newer, older));

        List<MerchantAgreementVersion> current = service.listPublished(null);

        assertEquals(1, current.size());
        assertEquals("v2", current.get(0).getVersion());
    }

    // ---------- 结构静态断言 ----------

    @Test
    void consentMapperStaysAppendOnly() throws Exception {
        String mapper = Files.readString(
                Path.of("src/main/java/com/shop/b2b2c/mapper/MerchantAgreementConsentMapper.java"));
        assertFalse(mapper.contains("@Update"), "同意记录 Mapper 禁止 UPDATE");
        assertFalse(mapper.contains("@Delete"), "同意记录 Mapper 禁止 DELETE");
    }

    @Test
    void schemaAndMigrationV10DeclareAgreementTables() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/schema.sql"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS `merchant_agreement_version`"),
                "schema 必须包含协议版本表");
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS `merchant_agreement_consent`"),
                "schema 必须包含协议同意表");
        assertTrue(schema.contains("UNIQUE KEY `uk_agreement_type_version` (`protocol_type`, `version`)"),
                "协议版本必须按 (protocol_type, version) 唯一");
        assertTrue(schema.contains("UNIQUE KEY `uk_consent_app_protocol` (`application_id`, `protocol_type`, `protocol_version`)"),
                "同意记录必须按 (application_id, protocol_type, protocol_version) 唯一");
        assertTrue(schema.contains("CONSTRAINT fk_consent_app FOREIGN KEY (`application_id`) REFERENCES `merchant_onboarding_application` (`id`)"),
                "同意记录必须外键关联入驻申请");

        String migration = Files.readString(
                Path.of("src/main/resources/db/migrations/V10__o3_agreement_tables.sql"));
        assertTrue(migration.contains("uk_agreement_type_version"), "V10 必须创建版本唯一索引");
        assertTrue(migration.contains("uk_consent_app_protocol"), "V10 必须创建同意唯一索引");
        assertTrue(migration.contains("fk_consent_app"), "V10 必须创建同意外键");
    }

    // ---------- 辅助 ----------

    private MerchantAgreementService.PublishForm publishForm(String type, String version, String hash) {
        MerchantAgreementService.PublishForm form = new MerchantAgreementService.PublishForm();
        form.setProtocolType(type);
        form.setVersion(version);
        form.setTitle("平台服务协议");
        form.setContentHash(hash);
        return form;
    }

    private MerchantAgreementVersion published(String type, String version, String hash) {
        MerchantAgreementVersion record = new MerchantAgreementVersion();
        record.setProtocolType(type);
        record.setVersion(version);
        record.setTitle("协议");
        record.setContentHash(hash);
        record.setEffectiveFrom(LocalDateTime.now().minusDays(1));
        record.setStatus(MerchantAgreementService.STATUS_PUBLISHED);
        return record;
    }
}
