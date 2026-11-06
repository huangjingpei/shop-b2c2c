/*
 * 文件：MerchantOnboardingService.java
 * 用途：商家入驻申请、实名材料登记、人工审核状态机与审核通过后开店/开账号。
 * 维护注意：
 * 1. 状态机：DRAFT -> SUBMITTED -> REVIEWING -> APPROVED/REJECTED/SUPPLEMENT_REQUIRED；WITHDRAWN 为申请人撤回。
 *    所有推进必须在行锁内校验当前状态，禁止仅按前端传参改状态。
 * 2. 身份证/营业执照只保存 CardCipherService 密文与 SHA-256 指纹；接口视图一律脱敏，禁止回传密文或明文。
 * 3. 阶段一为人工审核，verificationMethod 固定 MANUAL，verificationSource 仅记录 MANUAL_REVIEW；
 *    未接第三方核验时不得标注为第三方实名，审核角色当前沿用平台超管（待用户确认）。
 * 4. 审核通过才创建店铺与店长账号；驳回/撤回不得产生店铺、账号或收款账户副作用。
 * 5. 默认费率沿用既有 3.00% 默认值，具体默认数值仍待用户确认；费率政策表与快照在 T39 第三段接入。
 * 6. 审核人、原因、前后状态与材料摘要写入 merchant_onboarding_audit；审计只追加不修改。
 */
package com.shop.b2b2c.service;

import cn.dev33.satoken.secure.BCrypt;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.ManagementGuards;
import com.shop.b2b2c.common.PiiMasking;
import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import com.shop.b2b2c.entity.MerchantOnboardingAudit;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.SysUser;
import com.shop.b2b2c.mapper.MerchantOnboardingApplicationMapper;
import com.shop.b2b2c.mapper.MerchantOnboardingAuditMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.SysUserMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MerchantOnboardingService {

    public static final String SUBJECT_INDIVIDUAL = "INDIVIDUAL";
    public static final String SUBJECT_ENTERPRISE = "ENTERPRISE";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_REVIEWING = "REVIEWING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_SUPPLEMENT_REQUIRED = "SUPPLEMENT_REQUIRED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";

    public static final String ACTION_START_REVIEW = "START_REVIEW";
    public static final String ACTION_APPROVE = "APPROVE";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_SUPPLEMENT_REQUIRED = "SUPPLEMENT_REQUIRED";

    private static final String VERIFICATION_METHOD_MANUAL = "MANUAL";
    private static final String VERIFICATION_SOURCE_MANUAL_REVIEW = "MANUAL_REVIEW";

    /** 既有默认佣金费率；该具体默认值尚未经用户确认，不得在文档中表述为最终业务值。 */
    private static final BigDecimal DEFAULT_COMMISSION_RATE = new BigDecimal("3.00");

    private final MerchantOnboardingApplicationMapper applicationMapper;
    private final MerchantOnboardingAuditMapper auditMapper;
    private final MerchantStoreMapper storeMapper;
    private final SysUserMapper userMapper;
    private final CardCipherService cipherService;
    private final MerchantFeePolicyService feePolicyService;

    @Data
    public static class ApplicationForm {
        private String subjectType;
        private String subjectName;
        private String idCardNumber;
        private String licenseNo;
        private String licenseUrl;
        private String legalPerson;
        private String contactPhone;
        private String contactEmail;
    }

    @Data
    public static class Provisioning {
        private String storeCode;
        private String storeName;
        private Integer merchantType;
        private String adminUsername;
        private String adminPassword;
        private Integer rateBps;
        private String promotionReason;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication create(ApplicationForm form, Long operatorId) {
        MerchantOnboardingApplication application = new MerchantOnboardingApplication();
        application.setStatus(STATUS_DRAFT);
        application.setVerificationMethod(VERIFICATION_METHOD_MANUAL);
        application.setVersion(0);
        applyMaterials(application, form);
        ensureNoActiveDuplicate(application, null);
        applicationMapper.insert(application);
        writeAudit(application, "CREATE", null, STATUS_DRAFT, operatorId, null);
        return application;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication updateMaterials(Long id, ApplicationForm form, Long operatorId) {
        MerchantOnboardingApplication application = requireLocked(id);
        if (!STATUS_DRAFT.equals(application.getStatus())
                && !STATUS_SUPPLEMENT_REQUIRED.equals(application.getStatus())) {
            throw new BusinessException(409, "仅草稿或补件状态可修改实名材料");
        }
        String fromStatus = application.getStatus();
        applyMaterials(application, form);
        ensureNoActiveDuplicate(application, application.getId());
        application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
        applicationMapper.updateById(application);
        writeAudit(application, "MATERIAL_UPDATED", fromStatus, fromStatus, operatorId, null);
        return application;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication submit(Long id, Long operatorId) {
        MerchantOnboardingApplication application = requireLocked(id);
        if (!STATUS_DRAFT.equals(application.getStatus())
                && !STATUS_SUPPLEMENT_REQUIRED.equals(application.getStatus())) {
            throw new BusinessException(409, "仅草稿或补件状态可提交审核");
        }
        validateForSubmit(application);
        ensureNoActiveDuplicate(application, application.getId());
        String fromStatus = application.getStatus();
        application.setStatus(STATUS_SUBMITTED);
        application.setSubmittedAt(LocalDateTime.now());
        application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
        applicationMapper.updateById(application);
        writeAudit(application, "SUBMIT", fromStatus, STATUS_SUBMITTED, operatorId, null);
        return application;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication startReview(Long id, Long reviewerId) {
        MerchantOnboardingApplication application = requireLocked(id);
        if (!STATUS_SUBMITTED.equals(application.getStatus())) {
            throw new BusinessException(409, "仅已提交申请可开始审核");
        }
        application.setStatus(STATUS_REVIEWING);
        application.setReviewerId(reviewerId);
        application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
        applicationMapper.updateById(application);
        writeAudit(application, ACTION_START_REVIEW, STATUS_SUBMITTED, STATUS_REVIEWING, reviewerId, null);
        return application;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication approve(Long id, Provisioning provisioning, String reason, Long reviewerId) {
        MerchantOnboardingApplication application = requireLocked(id);
        if (!STATUS_SUBMITTED.equals(application.getStatus())
                && !STATUS_REVIEWING.equals(application.getStatus())) {
            throw new BusinessException(409, "仅已提交或审核中申请可审核通过");
        }
        validateForSubmit(application);
        validateProvisioning(provisioning);

        if (storeMapper.findByStoreCode(provisioning.getStoreCode()).isPresent()) {
            throw new BusinessException("店铺编码已存在");
        }
        if (userMapper.findByUsername(provisioning.getAdminUsername()).isPresent()) {
            throw new BusinessException("登录账号已存在");
        }

        MerchantStore store = new MerchantStore();
        store.setStoreCode(provisioning.getStoreCode().trim());
        store.setStoreName(provisioning.getStoreName().trim());
        store.setMerchantType(resolveMerchantType(application, provisioning));
        store.setOwnerName(application.getSubjectName());
        // 店铺主表只保留脱敏展示值，证件原文仅以密文留在入驻申请中。
        store.setIdCardNumber(maskedIdCard(application));
        store.setContactPhone(application.getContactPhone());
        store.setStatus(1);
        store.setCommissionRate(resolveDefaultRate(provisioning));
        storeMapper.save(store);

        SysUser user = new SysUser();
        user.setMerchantId(store.getId());
        user.setUsername(provisioning.getAdminUsername().trim());
        user.setPassword(BCrypt.hashpw(provisioning.getAdminPassword(), BCrypt.gensalt()));
        user.setRealName(application.getSubjectName() + " 店长");
        user.setRoleCode("MERCHANT_ADMIN");
        user.setIsActive(true);
        userMapper.save(user);

        // T39：开店即建立初始费率政策，作为后续订单快照的唯一费率来源；merchant_store.commission_rate 仅同步展示镜像。
        feePolicyService.createInitialPolicy(store.getId(), provisioning.getRateBps(),
                provisioning.getPromotionReason(), "入驻审核通过初始费率", reviewerId);

        String fromStatus = application.getStatus();
        application.setMerchantId(store.getId());
        application.setStatus(STATUS_APPROVED);
        application.setReviewedAt(LocalDateTime.now());
        application.setReviewerId(reviewerId);
        application.setVerificationMethod(VERIFICATION_METHOD_MANUAL);
        application.setVerificationSource(VERIFICATION_SOURCE_MANUAL_REVIEW);
        application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
        applicationMapper.updateById(application);
        writeAudit(application, ACTION_APPROVE, fromStatus, STATUS_APPROVED, reviewerId, reason);
        return application;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication reject(Long id, String reason, Long reviewerId) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(400, "驳回必须填写原因");
        }
        MerchantOnboardingApplication application = requireLocked(id);
        if (!STATUS_SUBMITTED.equals(application.getStatus())
                && !STATUS_REVIEWING.equals(application.getStatus())
                && !STATUS_SUPPLEMENT_REQUIRED.equals(application.getStatus())) {
            throw new BusinessException(409, "当前状态不可驳回");
        }
        String fromStatus = application.getStatus();
        application.setStatus(STATUS_REJECTED);
        application.setRejectReason(reason.trim());
        application.setReviewedAt(LocalDateTime.now());
        application.setReviewerId(reviewerId);
        application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
        applicationMapper.updateById(application);
        writeAudit(application, ACTION_REJECT, fromStatus, STATUS_REJECTED, reviewerId, reason);
        return application;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication requireSupplement(Long id, String reason, Long reviewerId) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(400, "补件必须填写原因");
        }
        MerchantOnboardingApplication application = requireLocked(id);
        if (!STATUS_SUBMITTED.equals(application.getStatus())
                && !STATUS_REVIEWING.equals(application.getStatus())) {
            throw new BusinessException(409, "仅已提交或审核中申请可要求补件");
        }
        String fromStatus = application.getStatus();
        application.setStatus(STATUS_SUPPLEMENT_REQUIRED);
        application.setRejectReason(reason.trim());
        application.setReviewedAt(LocalDateTime.now());
        application.setReviewerId(reviewerId);
        application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
        applicationMapper.updateById(application);
        writeAudit(application, ACTION_SUPPLEMENT_REQUIRED, fromStatus, STATUS_SUPPLEMENT_REQUIRED, reviewerId, reason);
        return application;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication withdraw(Long id, Long operatorId) {
        MerchantOnboardingApplication application = requireLocked(id);
        if (!STATUS_DRAFT.equals(application.getStatus())
                && !STATUS_SUBMITTED.equals(application.getStatus())
                && !STATUS_REVIEWING.equals(application.getStatus())) {
            throw new BusinessException(409, "当前状态不可撤回");
        }
        String fromStatus = application.getStatus();
        application.setStatus(STATUS_WITHDRAWN);
        application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
        applicationMapper.updateById(application);
        writeAudit(application, "WITHDRAW", fromStatus, STATUS_WITHDRAWN, operatorId, null);
        return application;
    }

    public List<MerchantOnboardingApplication> list() {
        return applicationMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MerchantOnboardingApplication>()
                .orderByDesc(MerchantOnboardingApplication::getId));
    }

    public MerchantOnboardingApplication findById(Long id) {
        MerchantOnboardingApplication application = applicationMapper.selectById(id);
        if (application == null) {
            throw new BusinessException(404, "入驻申请不存在");
        }
        return application;
    }

    public List<MerchantOnboardingAudit> audits(Long applicationId) {
        return auditMapper.findByApplicationId(applicationId);
    }

    public MerchantOnboardingApplication findApprovedByMerchantId(Long merchantId) {
        return applicationMapper.findApprovedByMerchantId(merchantId);
    }

    /** 接口视图：证件号一律脱敏，绝不返回密文；仅供平台审核人员查看。 */
    public Map<String, Object> maskedView(MerchantOnboardingApplication application) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", application.getId());
        view.put("merchantId", application.getMerchantId());
        view.put("subjectType", application.getSubjectType());
        view.put("subjectName", application.getSubjectName());
        view.put("idCardMasked", maskedIdCard(application));
        view.put("licenseMasked", maskedLicense(application));
        view.put("legalPerson", application.getLegalPerson());
        view.put("contactPhone", application.getContactPhone());
        view.put("contactEmail", application.getContactEmail());
        view.put("status", application.getStatus());
        view.put("verificationMethod", application.getVerificationMethod());
        view.put("verificationSource", application.getVerificationSource());
        view.put("rejectReason", application.getRejectReason());
        view.put("submittedAt", application.getSubmittedAt());
        view.put("reviewedAt", application.getReviewedAt());
        view.put("reviewerId", application.getReviewerId());
        view.put("version", application.getVersion());
        return view;
    }

    public String maskedIdCard(MerchantOnboardingApplication application) {
        String plain = cipherService.decrypt(application.getIdCardCipher());
        return PiiMasking.idCard(plain);
    }

    public String maskedLicense(MerchantOnboardingApplication application) {
        String plain = cipherService.decrypt(application.getLicenseNoCipher());
        return PiiMasking.bankAccount(plain);
    }

    private MerchantOnboardingApplication requireLocked(Long id) {
        MerchantOnboardingApplication application = applicationMapper.lockById(id);
        if (application == null) {
            throw new BusinessException(404, "入驻申请不存在");
        }
        return application;
    }

    private void applyMaterials(MerchantOnboardingApplication application, ApplicationForm form) {
        if (form == null) {
            throw new BusinessException(400, "入驻材料不能为空");
        }
        ManagementGuards.text(form.getSubjectType(), 16, true);
        String subjectType = form.getSubjectType().trim().toUpperCase();
        if (!SUBJECT_INDIVIDUAL.equals(subjectType) && !SUBJECT_ENTERPRISE.equals(subjectType)) {
            throw new BusinessException(400, "主体类型仅支持 INDIVIDUAL/ENTERPRISE");
        }
        ManagementGuards.text(form.getSubjectName(), 128, true);
        ManagementGuards.text(form.getContactPhone(), 32, true);
        ManagementGuards.text(form.getIdCardNumber(), 64, false);
        ManagementGuards.text(form.getLicenseNo(), 64, false);
        ManagementGuards.text(form.getLicenseUrl(), 512, false);
        ManagementGuards.text(form.getLegalPerson(), 64, false);
        ManagementGuards.text(form.getContactEmail(), 128, false);

        application.setSubjectType(subjectType);
        application.setSubjectName(form.getSubjectName().trim());
        application.setContactPhone(form.getContactPhone().trim());
        application.setContactEmail(blankToNull(form.getContactEmail()));
        application.setLicenseUrl(blankToNull(form.getLicenseUrl()));
        application.setLegalPerson(blankToNull(form.getLegalPerson()));

        if (form.getIdCardNumber() != null && !form.getIdCardNumber().isBlank()) {
            String raw = form.getIdCardNumber().trim();
            application.setIdCardCipher(cipherService.encrypt(raw));
            if (SUBJECT_INDIVIDUAL.equals(subjectType)) {
                application.setIdCardFingerprint(cipherService.fingerprint(raw));
            }
        }
        if (form.getLicenseNo() != null && !form.getLicenseNo().isBlank()) {
            String raw = form.getLicenseNo().trim();
            application.setLicenseNoCipher(cipherService.encrypt(raw));
            if (SUBJECT_ENTERPRISE.equals(subjectType)) {
                application.setIdCardFingerprint(cipherService.fingerprint(raw));
            }
        }
    }

    private void validateForSubmit(MerchantOnboardingApplication application) {
        if (application.getSubjectType() == null || application.getSubjectName() == null
                || application.getContactPhone() == null) {
            throw new BusinessException(400, "实名主体与联系电话必填");
        }
        if (application.getIdCardCipher() == null) {
            throw new BusinessException(400, "实名材料不完整");
        }
        if (SUBJECT_ENTERPRISE.equals(application.getSubjectType())
                && (application.getLicenseNoCipher() == null || application.getLegalPerson() == null)) {
            throw new BusinessException(400, "企业入驻须提交营业执照号与法定代表人");
        }
        if (application.getIdCardFingerprint() == null) {
            throw new BusinessException(400, "缺少主体去重指纹");
        }
    }

    private void validateProvisioning(Provisioning provisioning) {
        if (provisioning == null) {
            throw new BusinessException(400, "审核通过须提供开店信息");
        }
        ManagementGuards.text(provisioning.getStoreCode(), 64, true);
        ManagementGuards.text(provisioning.getStoreName(), 128, true);
        ManagementGuards.text(provisioning.getAdminUsername(), 64, true);
        ManagementGuards.text(provisioning.getAdminPassword(), 72, true);
        ManagementGuards.text(provisioning.getPromotionReason(), 512, false);
        if (provisioning.getAdminPassword().length() < 8
                || provisioning.getAdminPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(400, "密码须至少8字符且UTF-8编码不超过72字节");
        }
        if (provisioning.getMerchantType() != null
                && (provisioning.getMerchantType() < 1 || provisioning.getMerchantType() > 3)) {
            throw new BusinessException(400, "商户类型不合法");
        }
        if (provisioning.getRateBps() != null
                && (provisioning.getRateBps() < 100 || provisioning.getRateBps() > 1000)) {
            throw new BusinessException(400, "开店费率须在 100–1000 基点（1%–10%）");
        }
    }

    private int resolveMerchantType(MerchantOnboardingApplication application, Provisioning provisioning) {
        if (provisioning.getMerchantType() != null) {
            return provisioning.getMerchantType();
        }
        return SUBJECT_ENTERPRISE.equals(application.getSubjectType()) ? 3 : 1;
    }

    private BigDecimal resolveDefaultRate(Provisioning provisioning) {
        if (provisioning.getRateBps() != null) {
            return BigDecimal.valueOf(provisioning.getRateBps())
                    .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        }
        return DEFAULT_COMMISSION_RATE;
    }

    private void ensureNoActiveDuplicate(MerchantOnboardingApplication application, Long excludeId) {
        if (application.getSubjectType() == null || application.getIdCardFingerprint() == null) {
            return;
        }
        int active = applicationMapper.countActiveByFingerprint(
                application.getSubjectType(), application.getIdCardFingerprint(), excludeId);
        if (active > 0) {
            throw new BusinessException(409, "同一实名主体已有进行中的入驻申请");
        }
    }

    private void writeAudit(MerchantOnboardingApplication application, String action,
                            String fromStatus, String toStatus, Long operatorId, String reason) {
        MerchantOnboardingAudit audit = new MerchantOnboardingAudit();
        audit.setApplicationId(application.getId());
        audit.setMerchantId(application.getMerchantId());
        audit.setAction(action);
        audit.setFromStatus(fromStatus);
        audit.setToStatus(toStatus);
        audit.setOperatorId(operatorId);
        audit.setReason(reason);
        audit.setMaterialHash(cipherService.fingerprint(
                application.getSubjectType() + "|" + application.getIdCardFingerprint()
                        + "|" + application.getSubjectName() + "|" + application.getContactPhone()));
        auditMapper.insert(audit);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
