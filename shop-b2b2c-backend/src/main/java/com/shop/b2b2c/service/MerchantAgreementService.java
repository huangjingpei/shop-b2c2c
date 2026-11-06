/*
 * 文件：MerchantAgreementService.java
 * 用途：O3 协议版本发布/下线、当前生效查询、申请人/店长勾选同意与签署完整性判定。
 * 维护注意：
 * 1. 同类型仅一条 PUBLISHED 当前生效：发布新版本在事务内先下线同类型旧版本，再插入新版本；重复版本号命中
 *    uk_agreement_type_version 翻译为 409，事务回滚旧版本不受影响。
 * 2. 同意校验：版本必须存在且 PUBLISHED 已生效，contentHash 必须一致（不一致说明协议已更新，409 要求刷新重读）；
 *    重复签署同一版本幂等成功（uk_consent_app_protocol 兜底），不重复写证据。
 * 3. 同意记录 append-only：本类不得出现 UPDATE/DELETE 同意记录的操作。
 * 4. 必需协议集合 = 全部有当前生效版本的协议类型；全部签署完成时在申请单上写 agreed_at（agreement_required=1 时），
 *    agreed_at 只写一次，后续版本升级不清空（未开通申请须同意新版本由 hasAllRequiredConsents 实时判定）。
 * 5. 证据要素：subjectName 取申请单实名主体快照；ip/userAgent 由接口层尽力采集，允许为空但不允许伪造。
 * 6. O4 开通闸门：每次成功签署后在同一事务内经 ObjectProvider 调用 MerchantOnboardingService.openIfConsentsComplete，
 *    申请 APPROVED 且签署齐全即自动开通（店铺 status 0->1、申请 OPENED、审计 OPEN）；用 ObjectProvider 是为避免
 *    与 MerchantOnboardingService 的构造器循环依赖，延迟解析不改变事务边界，开通失败会同滚本次签署。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantAgreementConsent;
import com.shop.b2b2c.entity.MerchantAgreementVersion;
import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import com.shop.b2b2c.mapper.MerchantAgreementConsentMapper;
import com.shop.b2b2c.mapper.MerchantAgreementVersionMapper;
import com.shop.b2b2c.mapper.MerchantOnboardingApplicationMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MerchantAgreementService {

    public static final String TYPE_MERCHANT_SERVICE = "MERCHANT_SERVICE";
    public static final String TYPE_PRIVACY = "PRIVACY";
    public static final String TYPE_SETTLEMENT = "SETTLEMENT";
    public static final String TYPE_PROHIBITED_GOODS = "PROHIBITED_GOODS";
    /** 协议类型字典；新增类型必须先更新开发文档附录 A。 */
    public static final Set<String> PROTOCOL_TYPES = Set.of(
            TYPE_MERCHANT_SERVICE, TYPE_PRIVACY, TYPE_SETTLEMENT, TYPE_PROHIBITED_GOODS);

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_OFFLINE = "OFFLINE";

    private static final String CONSENT_METHOD_CHECKBOX = "CHECKBOX";

    private final MerchantAgreementVersionMapper versionMapper;
    private final MerchantAgreementConsentMapper consentMapper;
    private final MerchantOnboardingApplicationMapper applicationMapper;
    /** O4 自动开通钩子（延迟解析避免循环依赖）；见文件头第 6 条。 */
    private final org.springframework.beans.factory.ObjectProvider<MerchantOnboardingService> onboardingServiceProvider;

    @Data
    public static class PublishForm {
        private String protocolType;
        private String version;
        private String title;
        private String contentHash;
        private String contentUrl;
        private LocalDateTime effectiveFrom;
    }

    /** 当前生效协议列表：每个类型取最新一条已生效 PUBLISHED。 */
    public List<MerchantAgreementVersion> listPublished(String protocolType) {
        List<MerchantAgreementVersion> published;
        if (protocolType != null && !protocolType.isBlank()) {
            String type = normalizeType(protocolType);
            published = versionMapper.findPublishedByType(type);
        } else {
            published = versionMapper.findAllPublished();
        }
        Map<String, MerchantAgreementVersion> current = new LinkedHashMap<>();
        for (MerchantAgreementVersion version : published) {
            current.putIfAbsent(version.getProtocolType(), version);
        }
        return new ArrayList<>(current.values());
    }

    /**
     * 发布新版本：同事务下线同类型旧版本；重复 (protocol_type, version) 返回 409。
     */
    @Transactional(rollbackFor = Exception.class)
    public MerchantAgreementVersion publish(PublishForm form, Long operatorId) {
        if (form == null) {
            throw new BusinessException(400, "协议内容不能为空");
        }
        String type = normalizeType(form.getProtocolType());
        if (form.getVersion() == null || form.getVersion().isBlank()) {
            throw new BusinessException(400, "协议版本号不能为空");
        }
        if (form.getTitle() == null || form.getTitle().isBlank()) {
            throw new BusinessException(400, "协议标题不能为空");
        }
        if (form.getContentHash() == null || form.getContentHash().isBlank()) {
            throw new BusinessException(400, "协议正文哈希不能为空");
        }
        versionMapper.offlinePublishedByType(type);

        MerchantAgreementVersion version = new MerchantAgreementVersion();
        version.setProtocolType(type);
        version.setVersion(form.getVersion().trim());
        version.setTitle(form.getTitle().trim());
        version.setContentHash(form.getContentHash().trim());
        version.setContentUrl(form.getContentUrl() == null ? null : form.getContentUrl().trim());
        version.setEffectiveFrom(form.getEffectiveFrom() == null ? LocalDateTime.now() : form.getEffectiveFrom());
        version.setStatus(STATUS_PUBLISHED);
        version.setCreatedBy(operatorId);
        try {
            versionMapper.insert(version);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "该协议类型下版本号已存在，请勿重复发布");
        }
        return version;
    }

    /** 下线版本：仅 PUBLISHED/DRAFT 可下线；已 OFFLINE 幂等返回。 */
    @Transactional(rollbackFor = Exception.class)
    public MerchantAgreementVersion offline(Long id) {
        MerchantAgreementVersion version = id == null ? null : versionMapper.selectById(id);
        if (version == null) {
            throw new BusinessException(404, "协议版本不存在");
        }
        if (STATUS_OFFLINE.equals(version.getStatus())) {
            return version;
        }
        version.setStatus(STATUS_OFFLINE);
        versionMapper.updateById(version);
        return version;
    }

    /**
     * 勾选同意：校验版本存在、已生效、哈希一致；重复签署幂等成功；全部必需协议签署完成时写 agreed_at。
     */
    @Transactional(rollbackFor = Exception.class)
    public MerchantAgreementConsent consent(Long applicationId, Long merchantId,
            String protocolType, String version, String contentHash,
            String subjectName, String ip, String userAgent) {
        if (applicationId == null) {
            throw new BusinessException(400, "缺少申请编号");
        }
        String type = normalizeType(protocolType);
        if (version == null || version.isBlank()) {
            throw new BusinessException(400, "缺少协议版本号");
        }
        MerchantAgreementVersion record = versionMapper.findByTypeAndVersion(type, version.trim());
        if (record == null) {
            throw new BusinessException(404, "协议版本不存在");
        }
        if (!STATUS_PUBLISHED.equals(record.getStatus())
                || record.getEffectiveFrom() == null
                || record.getEffectiveFrom().isAfter(LocalDateTime.now())) {
            throw new BusinessException(409, "协议版本未生效或已下线，请刷新后重新阅读");
        }
        if (contentHash == null || !record.getContentHash().equals(contentHash.trim())) {
            throw new BusinessException(409, "协议版本已更新，请刷新后重新阅读并同意");
        }
        if (consentMapper.countByApplicationAndTypeAndVersion(applicationId, type, record.getVersion()) > 0) {
            // 重复勾选同一版本：幂等成功，不重复写证据。
            return consentMapper.findByApplicationId(applicationId).stream()
                    .filter(item -> type.equals(item.getProtocolType())
                            && record.getVersion().equals(item.getProtocolVersion()))
                    .findFirst().orElseThrow();
        }

        MerchantAgreementConsent consent = new MerchantAgreementConsent();
        consent.setApplicationId(applicationId);
        consent.setMerchantId(merchantId);
        consent.setProtocolType(type);
        consent.setProtocolVersion(record.getVersion());
        consent.setContentHash(record.getContentHash());
        consent.setSubjectName(subjectName == null ? "" : subjectName.trim());
        consent.setConsentMethod(CONSENT_METHOD_CHECKBOX);
        consent.setIp(ip == null ? null : ip.trim());
        consent.setUserAgent(userAgent == null ? null : userAgent.trim());
        consent.setAgreedAt(LocalDateTime.now());
        try {
            consentMapper.insert(consent);
        } catch (DuplicateKeyException e) {
            // 并发重复签署：唯一索引兜底，幂等返回已有证据。
            return consentMapper.findByApplicationId(applicationId).stream()
                    .filter(item -> type.equals(item.getProtocolType())
                            && record.getVersion().equals(item.getProtocolVersion()))
                    .findFirst().orElseThrow(() -> new BusinessException(409, "协议签署冲突，请刷新后重试"));
        }
        stampAgreedAtIfComplete(applicationId);
        // O4：签署落库后同事务尝试自动开通；非 APPROVED/未签齐时钩子静默跳过。
        MerchantOnboardingService onboardingService = onboardingServiceProvider.getIfAvailable();
        if (onboardingService != null) {
            onboardingService.openIfConsentsComplete(applicationId);
        }
        return consent;
    }

    /**
     * 签署完整性：全部有当前生效版本的协议类型均已签署当前版本；无生效协议时视为齐全（O4 闸门前置读此方法）。
     */
    public boolean hasAllRequiredConsents(Long applicationId) {
        for (MerchantAgreementVersion current : listPublished(null)) {
            if (consentMapper.countByApplicationAndTypeAndVersion(
                    applicationId, current.getProtocolType(), current.getVersion()) == 0) {
                return false;
            }
        }
        return true;
    }

    public List<MerchantAgreementConsent> listConsents(Long applicationId) {
        if (applicationId == null) {
            return List.of();
        }
        return consentMapper.findByApplicationId(applicationId);
    }

    /** 全部必需协议签署完成且申请要求签署时，写 agreed_at（只写一次）。 */
    private void stampAgreedAtIfComplete(Long applicationId) {
        if (!hasAllRequiredConsents(applicationId)) {
            return;
        }
        MerchantOnboardingApplication application = applicationMapper.selectById(applicationId);
        if (application == null || application.getAgreedAt() != null
                || !Boolean.TRUE.equals(application.getAgreementRequired())) {
            return;
        }
        application.setAgreedAt(LocalDateTime.now());
        applicationMapper.updateById(application);
    }

    private String normalizeType(String protocolType) {
        if (protocolType == null || protocolType.isBlank()) {
            throw new BusinessException(400, "缺少协议类型");
        }
        String type = protocolType.trim().toUpperCase();
        if (!PROTOCOL_TYPES.contains(type)) {
            throw new BusinessException(400, "协议类型不合法");
        }
        return type;
    }
}
