/*
 * 文件：MerchantOnboardingService.java
 * 用途：商家入驻申请、实名材料登记、人工审核状态机与审核通过后开店/开账号。
 * 维护注意：
 * 1. 状态机：DRAFT -> SUBMITTED -> REVIEWING -> APPROVED/REJECTED/SUPPLEMENT_REQUIRED；WITHDRAWN 为申请人撤回或系统逾期撤回；
 *    OPENED 表示已签协议并开通营业（O4），只从 APPROVED 进入。所有推进必须在行锁内校验当前状态，禁止仅按前端传参改状态。
 * 2. 身份证/营业执照只保存 CardCipherService 密文与 SHA-256 指纹；接口视图一律脱敏，禁止回传密文或明文。
 * 3. 阶段一为人工审核，verificationMethod 固定 MANUAL，verificationSource 仅记录 MANUAL_REVIEW；
 *    未接第三方核验时不得标注为第三方实名，审核角色当前沿用平台超管（待用户确认）。
 * 4. 审核通过才创建店铺与店长账号；驳回/撤回不得产生店铺、账号或收款账户副作用。
 *    O4 开通闸门：approve 建店后 merchant_store.status=0（待开通），不得直接营业；
 *    协议签署齐全（MerchantAgreementService.hasAllRequiredConsents）后自动开通，或超管调用 open 兜底开通；
 *    开通动作幂等（OPENED 重复调用直接返回），店铺侧用 CAS UPDATE ... WHERE status=0 防并发重复开通。
 * 5. 默认费率沿用既有 3.00% 默认值，具体默认数值仍待用户确认；开店允许直接设 0%，但必须走 PROMOTION 并填写推广原因。
 * 6. 审核人、原因码、审核阶段、前后状态与材料摘要写入 merchant_onboarding_audit；审计只追加不修改。
 * 7. S3 公开入驻：申请人凭创建时返回的一次性查询凭证（accessTokenHash 落库、明文不落库）维护材料、上传证件、提交与查进度；
 *    公开接口不能修改状态机，也不能读取证件密文。
 * 8. S3 审核通过自动开店：storeCode 自动生成、登录账号默认取申请手机号、初始密码随机生成并在审核响应/首次进度查询各展示一次；
 *    显式传入的 storeCode/adminUsername/adminPassword 仍优先使用（超管应急开通保留该能力）。
 * 9. O1 初审/复审：shop.finance.dual-approval-required=true 时，首次 APPROVE 只推进到 review_stage=SECONDARY（不建店不建号），
 *    由不同超管再次 APPROVE 才终审通过；SECONDARY 阶段的 APPROVE/REJECT/SUPPLEMENT 必须换人，同人返回 403。
 *    首次 APPROVE 前先校验除当前审核人外还有启用的 SUPERADMIN（没有则 409），避免单据卡在待复审无法推进。
 * 10. O1 原因码：REJECT 与 SUPPLEMENT_REQUIRED 必须携带 REJECT_CODES 字典内的 rejectCode；OTHER 时 reason 必填，其余原因码 reason 可选。
 * 11. 补件重提：仅 SUPPLEMENT_REQUIRED -> SUBMITTED 时 resubmit_count+1；要求补件会重置 review_stage/first_reviewer_id，重提后重新走初审。
 * 12. D3 逾期撤回：APPROVED 超 shop.onboarding.agreement-expire-days（默认30天）未开通、
 *     SUPPLEMENT_REQUIRED 超 shop.onboarding.supplement-expire-days（默认30天）未补件，由 OnboardingExpiryJob 逐条调用
 *     expireStaleApplication 置 WITHDRAWN，审计 action=EXPIRE 且 operator_id 为空（系统任务）；
 *     逾期只撤回申请，已建的待开通店铺与店长账号的停用/注销属 O6 店铺状态机范围，本类不处理。
 * 13. D11 首次登录强制改密：审核开店生成的店长账号 must_change_password=1，
 *     该账号改密前仅 /api/v1/auth/** 可用（SaTokenConfigure 拦截业务工作台），改密成功清除标记并注销全部会话。
 * 14. O9 资质有效期：ApplicationForm 接受 idCardExpiry/licenseExpiry（yyyy-MM-dd，可空），applyMaterials 统一解析校验；
 *     审核通过建店时把两日期快照到 merchant_store（提醒/限制上新/逾期冻结的唯一数据源），申请单保留填报值不回写。
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MerchantOnboardingService {

    /** 个人身份证开店：默认主体类型，仅需身份证正反面。 */
    public static final String SUBJECT_INDIVIDUAL = "INDIVIDUAL";
    /** 个体工商户：营业执照 + 经营者身份证正反面。 */
    public static final String SUBJECT_INDIVIDUAL_BUSINESS = "INDIVIDUAL_BUSINESS";
    /** 企业：营业执照 + 法定代表人身份证正反面。 */
    public static final String SUBJECT_ENTERPRISE = "ENTERPRISE";

    /** 店铺名称禁用词：平台无品牌授权线上核验能力，含这些词一律走线下补充授权。 */
    private static final List<String> STORE_NAME_RESTRICTED_WORDS =
            List.of("旗舰", "官方", "专卖", "专营");

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_REVIEWING = "REVIEWING";
    public static final String STATUS_APPROVED = "APPROVED";
    /** 已开通：协议签署完成且店铺 status=1，可营业；只从 APPROVED 进入。 */
    public static final String STATUS_OPENED = "OPENED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_SUPPLEMENT_REQUIRED = "SUPPLEMENT_REQUIRED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";

    public static final String ACTION_START_REVIEW = "START_REVIEW";
    public static final String ACTION_APPROVE = "APPROVE";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_SUPPLEMENT_REQUIRED = "SUPPLEMENT_REQUIRED";
    /** 开通动作（O4）；初审推进复用 APPROVE，凭 review_stage 区分。 */
    public static final String ACTION_OPEN = "OPEN";
    /** 系统逾期撤回（D3）；审计 operator_id 为空。 */
    public static final String ACTION_EXPIRE = "EXPIRE";

    /** 未进入审核流程。 */
    public static final String REVIEW_STAGE_NONE = "NONE";
    /** 审核员已领单开始审核。 */
    public static final String REVIEW_STAGE_PRIMARY = "PRIMARY";
    /** 初审通过，待复审（仅双人复核开启时出现，状态保持 REVIEWING）。 */
    public static final String REVIEW_STAGE_SECONDARY = "SECONDARY";
    /** 终审通过（= 申请状态 APPROVED）。 */
    public static final String REVIEW_STAGE_PASSED = "PASSED";

    /** 驳回/补件原因码字典（开发文档 5.5）；新增原因码必须先更新文档附录 A。 */
    public static final java.util.Set<String> REJECT_CODES = java.util.Set.of(
            "SUBJECT_INFO_MISMATCH", "NAME_ID_MISMATCH", "FACE_VERIFY_FAILED",
            "ID_CARD_INVALID", "ID_CARD_EXPIRED", "LICENSE_INVALID", "LICENSE_EXPIRED",
            "LICENSE_SUBJECT_MISMATCH", "STORE_NAME_VIOLATION", "MATERIAL_UNCLEAR",
            "MATERIAL_MISSING", "DUPLICATE_SUBJECT", "CATEGORY_NOT_SUPPORTED",
            "RISK_CONTROL", "OTHER");
    /** OTHER 原因码：此时 reason 必填。 */
    public static final String REJECT_CODE_OTHER = "OTHER";

    private static final String VERIFICATION_METHOD_MANUAL = "MANUAL";
    private static final String VERIFICATION_SOURCE_MANUAL_REVIEW = "MANUAL_REVIEW";

    /** 既有默认佣金费率；该具体默认值尚未经用户确认，不得在文档中表述为最终业务值。 */
    private static final BigDecimal DEFAULT_COMMISSION_RATE = new BigDecimal("3.00");

    /** 初始密码字符集：去掉易混淆字符，保证包含字母与数字。 */
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int GENERATED_PASSWORD_LENGTH = 16;
    private static final DateTimeFormatter STORE_CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SecureRandom secureRandom = new SecureRandom();

    private final MerchantOnboardingApplicationMapper applicationMapper;
    private final MerchantOnboardingAuditMapper auditMapper;
    private final MerchantStoreMapper storeMapper;
    private final SysUserMapper userMapper;
    private final CardCipherService cipherService;
    private final MerchantFeePolicyService feePolicyService;
    /** 双人复核统一开关与复核人校验；O1 复用其开关决定是否需要复审，不得在各入口各写一套。 */
    private final FinanceApprovalGuard approvalGuard;
    /** O4 开通闸门：协议是否签署齐全的唯一判定来源，open/openIfConsentsComplete 都必须经它校验。 */
    private final MerchantAgreementService agreementService;
    /** O8 通知发件箱：开店各节点通知统一入口；通知与业务状态同事务提交。 */
    private final MerchantNotificationService notificationService;

    @Data
    public static class ApplicationForm {
        private String subjectType;
        private String subjectName;
        /** 申请人希望使用的店铺名称；审核通过自动开店时使用。 */
        private String storeName;
        private String idCardNumber;
        private String licenseNo;
        private String licenseUrl;
        private String legalPerson;
        private String contactPhone;
        private String contactEmail;
        /** 身份证有效期（yyyy-MM-dd，可空表示长期/未采集）；O9。 */
        private String idCardExpiry;
        /** 营业执照有效期（yyyy-MM-dd，个人店可空）；O9。 */
        private String licenseExpiry;
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
        /** 开店初始费率的复核人；生产双人复核开启时必须与审核操作人不同，未传时严格模式返回 428。 */
        private Long rateConfirmedBy;
    }

    /** 公开申请创建结果：accessToken 明文只在此响应返回一次，后续只能凭它维护同一申请。 */
    public static class PublicTicket {
        private final MerchantOnboardingApplication application;
        private final String accessToken;

        public PublicTicket(MerchantOnboardingApplication application, String accessToken) {
            this.application = application;
            this.accessToken = accessToken;
        }

        public MerchantOnboardingApplication getApplication() {
            return application;
        }

        public String getAccessToken() {
            return accessToken;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication create(ApplicationForm form, Long operatorId) {
        MerchantOnboardingApplication application = new MerchantOnboardingApplication();
        application.setStatus(STATUS_DRAFT);
        application.setVerificationMethod(VERIFICATION_METHOD_MANUAL);
        application.setReviewStage(REVIEW_STAGE_NONE);
        application.setResubmitCount(0);
        application.setAgreementRequired(Boolean.TRUE);
        application.setVersion(0);
        applyMaterials(application, form);
        ensureNoActiveDuplicate(application, null);
        try {
            applicationMapper.insert(application);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // O2：uk_onboarding_active_subject 兜底并发重复申请；事务回滚，不泄漏 SQL。
            throw new BusinessException(409, "同一实名主体已有进行中的入驻申请");
        }
        writeAudit(application, "CREATE", null, STATUS_DRAFT, operatorId, null, null);
        return application;
    }

    /**
     * 公开开店申请：创建草稿并签发查询凭证。凭证只返回一次，库中只保存 SHA-256。
     */
    @Transactional(rollbackFor = Exception.class)
    public PublicTicket createPublic(ApplicationForm form) {
        if (form == null || form.getStoreName() == null || form.getStoreName().isBlank()) {
            throw new BusinessException(400, "请填写店铺名称");
        }
        MerchantOnboardingApplication application = create(form, null);
        String accessToken = randomToken();
        application.setAccessTokenHash(cipherService.fingerprint(accessToken));
        // 个人店必须本人出镜朗读随机数字；口令由申请人录视频时使用，人工审核比对。
        if (SUBJECT_INDIVIDUAL.equals(application.getSubjectType())) {
            application.setFaceVerifyCode(randomDigits(6));
        }
        applicationMapper.updateById(application);
        return new PublicTicket(application, accessToken);
    }

    /**
     * 公开入口对象级校验：申请不存在与凭证错误返回同一 404，避免枚举申请ID。
     */
    public MerchantOnboardingApplication requireByToken(Long id, String accessToken) {
        MerchantOnboardingApplication application = id == null ? null : applicationMapper.selectById(id);
        if (application == null || !tokenMatches(application, accessToken)) {
            throw new BusinessException(404, "入驻申请不存在或查询凭证无效");
        }
        return application;
    }

    /**
     * 公开查询入口：凭手机号 + 查询凭证定位申请，不依赖申请 ID；同手机号多次申请取凭证匹配的最新一条。
     */
    public MerchantOnboardingApplication findByPhoneAndToken(String contactPhone, String accessToken) {
        if (contactPhone == null || contactPhone.isBlank()) return null;
        java.util.List<MerchantOnboardingApplication> apps = applicationMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MerchantOnboardingApplication>()
                        .eq(MerchantOnboardingApplication::getContactPhone, contactPhone.trim())
                        .orderByDesc(MerchantOnboardingApplication::getId));
        for (MerchantOnboardingApplication app : apps) {
            if (tokenMatches(app, accessToken)) return app;
        }
        return null;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication updatePublic(Long id, String accessToken, ApplicationForm form) {
        requireByToken(id, accessToken);
        return updateMaterials(id, form, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication submitPublic(Long id, String accessToken) {
        requireByToken(id, accessToken);
        return submit(id, null);
    }

    /**
     * 公开进度查询：凭证与手机号双重校验；审核通过后初始密码只在首次查询返回一次。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publicStatus(Long id, String accessToken, String contactPhone) {
        MerchantOnboardingApplication application = id == null ? null : applicationMapper.lockById(id);
        if (application == null || !tokenMatches(application, accessToken)) {
            throw new BusinessException(404, "入驻申请不存在或查询凭证无效");
        }
        if (contactPhone == null || contactPhone.isBlank()
                || !contactPhone.trim().equals(application.getContactPhone())) {
            throw new BusinessException(404, "入驻申请不存在或查询凭证无效");
        }
        Map<String, Object> view = publicView(application);
        if ((STATUS_APPROVED.equals(application.getStatus()) || STATUS_OPENED.equals(application.getStatus()))
                && application.getInitialPasswordCipher() != null
                && application.getInitialPasswordRevealedAt() == null) {
            view.put("initialPassword", cipherService.decrypt(application.getInitialPasswordCipher()));
            application.setInitialPasswordRevealedAt(LocalDateTime.now());
            applicationMapper.updateById(application);
        }
        return view;
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
        writeAudit(application, "MATERIAL_UPDATED", fromStatus, fromStatus, operatorId, null, null);
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
        if (STATUS_SUPPLEMENT_REQUIRED.equals(fromStatus)) {
            application.setResubmitCount((application.getResubmitCount() == null ? 0 : application.getResubmitCount()) + 1);
        }
        application.setSubmittedAt(LocalDateTime.now());
        application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
        applicationMapper.updateById(application);
        Long auditId = writeAudit(application, "SUBMIT", fromStatus, STATUS_SUBMITTED, operatorId, null, null);
        // O8：提交成功通知（站内）；biz_id 用审计行ID，补件重提每次都会生成新审计行，互不冲突。
        notificationService.notify(MerchantNotificationService.BIZ_ONBOARDING_SUBMITTED, auditId,
                MerchantNotificationService.CHANNEL_INAPP, MerchantNotificationService.BIZ_ONBOARDING_SUBMITTED,
                application.getContactPhone(),
                notifyPayload("applicationId", application.getId(),
                        "storeName", application.getStoreName(),
                        "status", STATUS_SUBMITTED,
                        "tip", "已提交审核，预计 24 小时内开始处理"));
        return application;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication startReview(Long id, Long reviewerId) {
        MerchantOnboardingApplication application = requireLocked(id);
        if (!STATUS_SUBMITTED.equals(application.getStatus())) {
            throw new BusinessException(409, "仅已提交申请可开始审核");
        }
        application.setStatus(STATUS_REVIEWING);
        application.setReviewStage(REVIEW_STAGE_PRIMARY);
        application.setFirstReviewerId(reviewerId);
        application.setReviewerId(reviewerId);
        application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
        applicationMapper.updateById(application);
        writeAudit(application, ACTION_START_REVIEW, STATUS_SUBMITTED, STATUS_REVIEWING, reviewerId, null, null);
        return application;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication approve(Long id, Provisioning provisioning, String reason, Long reviewerId) {
        MerchantOnboardingApplication application = requireLocked(id);
        if (!STATUS_SUBMITTED.equals(application.getStatus())
                && !STATUS_REVIEWING.equals(application.getStatus())) {
            throw new BusinessException(409, "仅已提交或审核中申请可审核通过");
        }
        String stage = currentReviewStage(application);
        if (approvalGuard.isDualApprovalRequired() && !REVIEW_STAGE_SECONDARY.equals(stage)) {
            // 双人复核开启时的初审：先确认存在第二位启用超管，再推进阶段；否则单据会卡在待复审且第二次必然 403。
            approvalGuard.requireOtherActiveSuperAdmin("入驻初审", reviewerId);
            // 只推进阶段，不建店不建号不建费率；开店参数由终审提交。
            String fromStatus = application.getStatus();
            application.setStatus(STATUS_REVIEWING);
            application.setReviewStage(REVIEW_STAGE_SECONDARY);
            application.setFirstReviewerId(reviewerId);
            application.setReviewerId(reviewerId);
            application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
            applicationMapper.updateById(application);
            writeAudit(application, ACTION_APPROVE, fromStatus, STATUS_REVIEWING, reviewerId, reason, null);
            return application;
        }
        requireSecondaryActor(application, reviewerId);
        validateForSubmit(application);
        Provisioning effective = provisioning == null ? new Provisioning() : provisioning;
        String storeName = firstNonBlank(effective.getStoreName(), application.getStoreName());
        if (storeName == null) {
            throw new BusinessException(400, "店铺名称未填写，无法自动开店");
        }
        ManagementGuards.text(storeName, 128, true);
        // 审核时改写店铺名同样受禁用词约束，不能绕过申请阶段的校验。
        assertStoreNameAllowed(storeName);
        String storeCode = firstNonBlank(effective.getStoreCode(), null);
        if (storeCode == null) {
            storeCode = generateUniqueStoreCode();
        }
        ManagementGuards.text(storeCode, 64, true);
        String adminUsername = firstNonBlank(effective.getAdminUsername(), application.getContactPhone());
        String adminPassword = firstNonBlank(effective.getAdminPassword(), null);
        if (adminPassword == null) {
            adminPassword = generatePassword();
        }
        validateProvisioning(effective, adminUsername, adminPassword);

        if (storeMapper.findByStoreCode(storeCode).isPresent()) {
            throw new BusinessException("店铺编码已存在");
        }
        if (userMapper.findByUsername(adminUsername).isPresent()) {
            throw new BusinessException("登录账号已存在");
        }

        MerchantStore store = new MerchantStore();
        store.setStoreCode(storeCode);
        store.setStoreName(storeName);
        store.setMerchantType(resolveMerchantType(application, effective));
        store.setOwnerName(application.getSubjectName());
        // 店铺主表只保留脱敏展示值，证件原文仅以密文留在入驻申请中。
        store.setIdCardNumber(maskedIdCard(application));
        store.setContactPhone(application.getContactPhone());
        // O9：资质有效期快照到店铺，作为到期提醒/限制上新/逾期30天冻结的唯一数据源；申请单保留填报值。
        store.setIdCardExpiry(application.getIdCardExpiry());
        store.setLicenseExpiry(application.getLicenseExpiry());
        // O4 开通闸门：审核通过只代表资质合格，店铺先落“待开通”，签齐协议或超管 open 后才置 1 营业。
        store.setStatus(0);
        store.setCommissionRate(resolveDefaultRate(effective));
        storeMapper.save(store);

        SysUser user = new SysUser();
        user.setMerchantId(store.getId());
        user.setUsername(adminUsername);
        user.setPassword(BCrypt.hashpw(adminPassword, BCrypt.gensalt()));
        user.setRealName(application.getSubjectName() + " 店长");
        user.setRoleCode("MERCHANT_ADMIN");
        user.setIsActive(true);
        // D11：审核开店生成的店长账号必须首次登录强制改密，初始密码只允许一次性交付。
        user.setMustChangePassword(Boolean.TRUE);
        userMapper.save(user);

        // T39：开店即建立初始费率政策，作为后续订单快照的唯一费率来源；merchant_store.commission_rate 仅同步展示镜像。
        // 财务基线 18：初始费率与调价共用同一复核口径；复核人原样传递，由 FinanceApprovalGuard 判定缺参(428)/同人/双人。
        feePolicyService.createInitialPolicy(store.getId(), effective.getRateBps(),
                effective.getPromotionReason(), "入驻审核通过初始费率", reviewerId,
                effective.getRateConfirmedBy());

        String fromStatus = application.getStatus();
        application.setMerchantId(store.getId());
        application.setStatus(STATUS_APPROVED);
        application.setReviewStage(REVIEW_STAGE_PASSED);
        if (application.getFirstReviewerId() == null) {
            application.setFirstReviewerId(reviewerId);
        }
        application.setReviewedAt(LocalDateTime.now());
        application.setReviewerId(reviewerId);
        application.setVerificationMethod(VERIFICATION_METHOD_MANUAL);
        application.setVerificationSource(VERIFICATION_SOURCE_MANUAL_REVIEW);
        // 初始密码两种交付路径：审核响应瞬时字段 + 申请人首次进度查询解密一次；明文不落库。
        application.setInitialPasswordCipher(cipherService.encrypt(adminPassword));
        application.setInitialPassword(adminPassword);
        application.setStoreCode(storeCode);
        application.setInitialPasswordRevealedAt(null);
        application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
        applicationMapper.updateById(application);
        Long approveAuditId = writeAudit(application, ACTION_APPROVE, fromStatus, STATUS_APPROVED, reviewerId, reason, null);
        // O8：审核通过通知（站内）；初始密码只走审核响应/首次进度查询一次性交付，绝不进通知（密码红线）。
        notificationService.notify(MerchantNotificationService.BIZ_ONBOARDING_APPROVED, approveAuditId,
                MerchantNotificationService.CHANNEL_INAPP, MerchantNotificationService.BIZ_ONBOARDING_APPROVED,
                application.getContactPhone(),
                notifyPayload("applicationId", application.getId(),
                        "storeCode", storeCode,
                        "storeName", application.getStoreName(),
                        "merchantId", store.getId(),
                        "tip", "审核已通过，请尽快签署开店协议并登录商家后台完成开店"));
        return application;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication reject(Long id, String rejectCode, String reason, Long reviewerId) {
        String code = validateRejectCode(rejectCode, reason);
        MerchantOnboardingApplication application = requireLocked(id);
        if (!STATUS_SUBMITTED.equals(application.getStatus())
                && !STATUS_REVIEWING.equals(application.getStatus())
                && !STATUS_SUPPLEMENT_REQUIRED.equals(application.getStatus())) {
            throw new BusinessException(409, "当前状态不可驳回");
        }
        requireSecondaryActor(application, reviewerId);
        String fromStatus = application.getStatus();
        application.setStatus(STATUS_REJECTED);
        application.setRejectCode(code);
        application.setRejectReason(reason == null ? null : reason.trim());
        application.setReviewedAt(LocalDateTime.now());
        application.setReviewerId(reviewerId);
        application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
        applicationMapper.updateById(application);
        Long rejectAuditId = writeAudit(application, ACTION_REJECT, fromStatus, STATUS_REJECTED, reviewerId, reason, code);
        // O8：驳回通知（站内 + 可选短信，短信默认禁用置 SKIPPED）；携带原因码与原因便于申请人整改。
        notificationService.notifyAll(MerchantNotificationService.BIZ_ONBOARDING_REJECTED, rejectAuditId,
                MerchantNotificationService.BIZ_ONBOARDING_REJECTED, application.getContactPhone(),
                notifyPayload("applicationId", application.getId(),
                        "storeName", application.getStoreName(),
                        "rejectCode", code,
                        "reason", application.getRejectReason()),
                MerchantNotificationService.CHANNEL_INAPP, MerchantNotificationService.CHANNEL_SMS);
        return application;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication requireSupplement(Long id, String rejectCode, String reason, Long reviewerId) {
        String code = validateRejectCode(rejectCode, reason);
        MerchantOnboardingApplication application = requireLocked(id);
        if (!STATUS_SUBMITTED.equals(application.getStatus())
                && !STATUS_REVIEWING.equals(application.getStatus())) {
            throw new BusinessException(409, "仅已提交或审核中申请可要求补件");
        }
        requireSecondaryActor(application, reviewerId);
        String fromStatus = application.getStatus();
        application.setStatus(STATUS_SUPPLEMENT_REQUIRED);
        application.setRejectCode(code);
        application.setRejectReason(reason == null ? null : reason.trim());
        // 补件后重新排队审核：初审/复审进度作废，重提后从初审重新开始。
        application.setReviewStage(REVIEW_STAGE_NONE);
        application.setFirstReviewerId(null);
        application.setReviewedAt(LocalDateTime.now());
        application.setReviewerId(reviewerId);
        application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
        applicationMapper.updateById(application);
        Long supplementAuditId = writeAudit(application, ACTION_SUPPLEMENT_REQUIRED, fromStatus, STATUS_SUPPLEMENT_REQUIRED, reviewerId, reason, code);
        // O8：补件通知（站内 + 可选短信，短信默认禁用置 SKIPPED）；biz_id 为本次审计行ID，多次补件各自通知一次。
        notificationService.notifyAll(MerchantNotificationService.BIZ_ONBOARDING_SUPPLEMENT, supplementAuditId,
                MerchantNotificationService.BIZ_ONBOARDING_SUPPLEMENT, application.getContactPhone(),
                notifyPayload("applicationId", application.getId(),
                        "storeName", application.getStoreName(),
                        "rejectCode", code,
                        "reason", application.getRejectReason()),
                MerchantNotificationService.CHANNEL_INAPP, MerchantNotificationService.CHANNEL_SMS);
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
        writeAudit(application, "WITHDRAW", fromStatus, STATUS_WITHDRAWN, operatorId, null, null);
        return application;
    }

    /**
     * O4 超管兜底开通：仅 APPROVED 可开通；协议未签齐 409；重复开通幂等返回。
     * 双人复核开启时必须指定与操作人不同的复核人，校验统一走 FinanceApprovalGuard。
     */
    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboardingApplication open(Long id, Long operatorId, Long confirmedBy) {
        MerchantOnboardingApplication application = requireLocked(id);
        if (STATUS_OPENED.equals(application.getStatus())) {
            // 开通动作幂等：重复调用返回当前态，不重复写审计、不重复 CAS 店铺。
            return application;
        }
        if (operatorId != null) {
            approvalGuard.verify("开通店铺(OPEN)", operatorId, confirmedBy);
            approvalGuard.resolveReviewer(operatorId, confirmedBy);
        }
        return doOpen(application, operatorId);
    }

    /**
     * O4 系统自动开通钩子：协议签署完成后由 MerchantAgreementService 在同一事务内调用。
     * 仅当申请处于 APPROVED 且协议齐全时开通；其余状态（含未审核/已开通）静默跳过，不报错。
     */
    @Transactional(rollbackFor = Exception.class)
    public void openIfConsentsComplete(Long applicationId) {
        if (applicationId == null) {
            return;
        }
        MerchantOnboardingApplication application = applicationMapper.lockById(applicationId);
        if (application == null || !STATUS_APPROVED.equals(application.getStatus())) {
            return;
        }
        doOpen(application, null);
    }

    /** 开通公共段：状态/闸门校验 + 店铺 CAS 0->1 + 申请置 OPENED + 审计 OPEN（operatorId 为空表示系统任务）。 */
    private MerchantOnboardingApplication doOpen(MerchantOnboardingApplication application, Long operatorId) {
        if (!STATUS_APPROVED.equals(application.getStatus())) {
            throw new BusinessException(409, "仅审核通过(APPROVED)的入驻申请可以开通");
        }
        if (Boolean.TRUE.equals(application.getAgreementRequired())
                && !agreementService.hasAllRequiredConsents(application.getId())) {
            throw new BusinessException(409, "开店协议尚未签署齐全，请先完成协议签署后再开通");
        }
        Long merchantId = application.getMerchantId();
        if (merchantId == null) {
            throw new BusinessException(409, "申请尚未建店，无法开通");
        }
        MerchantStore store = storeMapper.findById(merchantId)
                .orElseThrow(() -> new BusinessException(500, "店铺不存在，无法开通"));
        if (!Integer.valueOf(1).equals(store.getStatus())) {
            if (!Integer.valueOf(0).equals(store.getStatus())) {
                throw new BusinessException(409, "店铺当前状态不可开通，请检查店铺营业状态");
            }
            int updated = storeMapper.openIfPending(merchantId);
            if (updated == 0) {
                // 并发下另一请求已开通：重读确认，非 1 则视为状态冲突。
                MerchantStore latest = storeMapper.findById(merchantId)
                        .orElseThrow(() -> new BusinessException(500, "店铺不存在，无法开通"));
                if (!Integer.valueOf(1).equals(latest.getStatus())) {
                    throw new BusinessException(409, "店铺当前状态不可开通，请检查店铺营业状态");
                }
            }
        }
        String fromStatus = application.getStatus();
        application.setStatus(STATUS_OPENED);
        application.setOpenedAt(LocalDateTime.now());
        application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
        applicationMapper.updateById(application);
        Long openAuditId = writeAudit(application, ACTION_OPEN, fromStatus, STATUS_OPENED, operatorId, null, null);
        // O8：开通成功通知（站内 + 可选短信，短信默认禁用置 SKIPPED）；内容只含登录地址与账号，密码红线不落通知。
        String loginAccount = userMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getMerchantId, merchantId).eq(SysUser::getRoleCode, "MERCHANT_ADMIN"))
                .stream().findFirst().map(SysUser::getUsername).orElse(application.getContactPhone());
        notificationService.notifyAll(MerchantNotificationService.BIZ_ONBOARDING_OPENED, openAuditId,
                MerchantNotificationService.BIZ_ONBOARDING_OPENED,
                MerchantNotificationService.merchantRecipient(merchantId),
                notifyPayload("applicationId", application.getId(),
                        "merchantId", merchantId,
                        "storeCode", application.getStoreCode(),
                        "storeName", application.getStoreName(),
                        "username", loginAccount,
                        "loginPath", "/merchant/login",
                        "tip", "店铺已开通营业，首次登录请使用审核交付的初始密码并及时修改"),
                MerchantNotificationService.CHANNEL_INAPP, MerchantNotificationService.CHANNEL_SMS);
        return application;
    }

    /** D3 逾期扫描：返回 (APPROVED 超期未开通) 或 (SUPPLEMENT_REQUIRED 超期未补件) 的申请ID，供定时任务逐条处理。 */
    public List<Long> findExpirableIds(LocalDateTime approvedBefore, LocalDateTime supplementBefore, int limit) {
        return applicationMapper.findExpirableIds(approvedBefore, supplementBefore, limit);
    }

    /**
     * D3 逾期撤回（系统任务）：仅 APPROVED/SUPPLEMENT_REQUIRED 可逾期撤回，返回是否处理；
     * 行锁内复核状态，已被人工推进的单据静默跳过，审计 action=EXPIRE 且操作人为空。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean expireStaleApplication(Long id) {
        MerchantOnboardingApplication application = applicationMapper.lockById(id);
        if (application == null
                || (!STATUS_APPROVED.equals(application.getStatus())
                && !STATUS_SUPPLEMENT_REQUIRED.equals(application.getStatus()))) {
            return false;
        }
        String fromStatus = application.getStatus();
        application.setStatus(STATUS_WITHDRAWN);
        application.setVersion((application.getVersion() == null ? 0 : application.getVersion()) + 1);
        applicationMapper.updateById(application);
        writeAudit(application, ACTION_EXPIRE, fromStatus, STATUS_WITHDRAWN, null, "逾期未开通/未补件，系统自动撤回", null);
        return true;
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
        view.put("storeName", application.getStoreName());
        // 审核人必须看到个人店的随机数字口令，才能比对申请人视频里朗读的数字是否一致。
        if (SUBJECT_INDIVIDUAL.equals(application.getSubjectType())) {
            view.put("faceVerifyCode", application.getFaceVerifyCode());
        }
        view.put("idCardMasked", maskedIdCard(application));
        view.put("licenseMasked", maskedLicense(application));
        view.put("legalPerson", application.getLegalPerson());
        view.put("contactPhone", application.getContactPhone());
        view.put("contactEmail", application.getContactEmail());
        view.put("status", application.getStatus());
        view.put("verificationMethod", application.getVerificationMethod());
        view.put("verificationSource", application.getVerificationSource());
        view.put("rejectCode", application.getRejectCode());
        view.put("rejectReason", application.getRejectReason());
        view.put("resubmitCount", application.getResubmitCount());
        view.put("reviewStage", application.getReviewStage());
        view.put("firstReviewerId", application.getFirstReviewerId());
        view.put("agreementRequired", application.getAgreementRequired());
        view.put("agreedAt", application.getAgreedAt());
        view.put("openedAt", application.getOpenedAt());
        view.put("cancelReason", application.getCancelReason());
        view.put("submittedAt", application.getSubmittedAt());
        view.put("reviewedAt", application.getReviewedAt());
        view.put("reviewerId", application.getReviewerId());
        view.put("version", application.getVersion());
        return view;
    }

    /** 公开进度视图：不返回证件号/密文/审核人，仅返回申请人可确认的进度信息。 */
    public Map<String, Object> publicView(MerchantOnboardingApplication application) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", application.getId());
        view.put("storeName", application.getStoreName());
        view.put("subjectType", application.getSubjectType());
        view.put("subjectName", application.getSubjectName());
        view.put("status", application.getStatus());
        view.put("rejectCode", application.getRejectCode());
        view.put("rejectReason", application.getRejectReason());
        view.put("submittedAt", application.getSubmittedAt());
        view.put("reviewedAt", application.getReviewedAt());
        view.put("openedAt", application.getOpenedAt());
        view.put("initialPasswordRevealed", application.getInitialPasswordRevealedAt() != null);
        if (SUBJECT_INDIVIDUAL.equals(application.getSubjectType())
                && (STATUS_DRAFT.equals(application.getStatus())
                || STATUS_SUPPLEMENT_REQUIRED.equals(application.getStatus()))) {
            view.put("faceVerifyCode", application.getFaceVerifyCode());
        }
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
        if (!SUBJECT_INDIVIDUAL.equals(subjectType)
                && !SUBJECT_INDIVIDUAL_BUSINESS.equals(subjectType)
                && !SUBJECT_ENTERPRISE.equals(subjectType)) {
            throw new BusinessException(400, "主体类型仅支持 INDIVIDUAL/INDIVIDUAL_BUSINESS/ENTERPRISE");
        }
        ManagementGuards.text(form.getSubjectName(), 128, true);
        ManagementGuards.text(form.getStoreName(), 128, false);
        assertStoreNameAllowed(blankToNull(form.getStoreName()));
        ManagementGuards.text(form.getContactPhone(), 32, true);
        ManagementGuards.text(form.getIdCardNumber(), 64, false);
        ManagementGuards.text(form.getLicenseNo(), 64, false);
        ManagementGuards.text(form.getLicenseUrl(), 512, false);
        ManagementGuards.text(form.getLegalPerson(), 64, false);
        ManagementGuards.text(form.getContactEmail(), 128, false);
        ManagementGuards.text(form.getIdCardExpiry(), 10, false);
        ManagementGuards.text(form.getLicenseExpiry(), 10, false);

        application.setSubjectType(subjectType);
        application.setSubjectName(form.getSubjectName().trim());
        application.setStoreName(blankToNull(form.getStoreName()));
        application.setContactPhone(form.getContactPhone().trim());
        application.setContactEmail(blankToNull(form.getContactEmail()));
        application.setLicenseUrl(blankToNull(form.getLicenseUrl()));
        application.setLegalPerson(blankToNull(form.getLegalPerson()));
        // O9：证件有效期只校验格式，不拦截已过期日期——过期由审核员按 ID_CARD_EXPIRED/LICENSE_EXPIRED 原因码判断。
        application.setIdCardExpiry(parseExpiryDate(form.getIdCardExpiry(), "身份证有效期"));
        application.setLicenseExpiry(parseExpiryDate(form.getLicenseExpiry(), "营业执照有效期"));

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
            if (SUBJECT_INDIVIDUAL_BUSINESS.equals(subjectType) || SUBJECT_ENTERPRISE.equals(subjectType)) {
                // 营业执照主体按执照号去重：同一执照不允许并行申请。
                application.setIdCardFingerprint(cipherService.fingerprint(raw));
            }
        }
    }

    private void validateForSubmit(MerchantOnboardingApplication application) {
        if (application.getSubjectType() == null || application.getSubjectName() == null
                || application.getContactPhone() == null) {
            throw new BusinessException(400, "实名主体与联系电话必填");
        }
        if (application.getStoreName() == null) {
            throw new BusinessException(400, "店铺名称必填");
        }
        if (application.getIdCardCipher() == null) {
            throw new BusinessException(400, "实名材料不完整");
        }
        if ((SUBJECT_INDIVIDUAL_BUSINESS.equals(application.getSubjectType())
                || SUBJECT_ENTERPRISE.equals(application.getSubjectType()))
                && (application.getLicenseNoCipher() == null || application.getLegalPerson() == null)) {
            throw new BusinessException(400, "个体工商户/企业入驻须提交营业执照号与经营者或法定代表人");
        }
        if (application.getIdCardFingerprint() == null) {
            throw new BusinessException(400, "缺少主体去重指纹");
        }
    }

    private void validateProvisioning(Provisioning provisioning, String adminUsername, String adminPassword) {
        ManagementGuards.text(adminUsername, 64, true);
        ManagementGuards.text(provisioning.getPromotionReason(), 512, false);
        if (adminPassword == null || adminPassword.length() < 8
                || adminPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(400, "密码须至少8字符且UTF-8编码不超过72字节");
        }
        if (provisioning.getMerchantType() != null
                && (provisioning.getMerchantType() < 1 || provisioning.getMerchantType() > 3)) {
            throw new BusinessException(400, "商户类型不合法");
        }
        if (provisioning.getRateBps() != null) {
            int rateBps = provisioning.getRateBps();
            if (rateBps == 0) {
                // T40 默认口径：允许开店直接设 0%，但必须为 PROMOTION 且写明原因。
                if (provisioning.getPromotionReason() == null || provisioning.getPromotionReason().isBlank()) {
                    throw new BusinessException(400, "0% 推广费率必须填写推广原因");
                }
            } else if (rateBps < 100 || rateBps > 1000) {
                throw new BusinessException(400, "开店费率须为 0%（PROMOTION，需原因）或 100–1000 基点（1%–10%）");
            }
        }
    }

    private int resolveMerchantType(MerchantOnboardingApplication application, Provisioning provisioning) {
        if (provisioning.getMerchantType() != null) {
            return provisioning.getMerchantType();
        }
        if (SUBJECT_ENTERPRISE.equals(application.getSubjectType())) {
            return 3;
        }
        return SUBJECT_INDIVIDUAL_BUSINESS.equals(application.getSubjectType()) ? 2 : 1;
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

    /** 当前审核阶段；历史行无值时按 NONE 处理，保证旧数据行为与单级审核一致。 */
    private String currentReviewStage(MerchantOnboardingApplication application) {
        return application.getReviewStage() == null ? REVIEW_STAGE_NONE : application.getReviewStage();
    }

    /**
     * SECONDARY 阶段的终审/驳回/补件必须由初审人以外的超管执行；仅在双人复核开启且处于 SECONDARY 时约束。
     */
    private void requireSecondaryActor(MerchantOnboardingApplication application, Long operatorId) {
        if (!approvalGuard.isDualApprovalRequired()) {
            return;
        }
        if (!REVIEW_STAGE_SECONDARY.equals(currentReviewStage(application))) {
            return;
        }
        if (operatorId != null && operatorId.equals(application.getFirstReviewerId())) {
            throw new BusinessException(403, "复审人必须不同于初审人");
        }
    }

    /**
     * O1 原因码：REJECT/SUPPLEMENT_REQUIRED 必填且必须在字典内；OTHER 时 reason 必填，其余原因码 reason 可选。
     */
    private String validateRejectCode(String rejectCode, String reason) {
        if (rejectCode == null || rejectCode.isBlank()) {
            throw new BusinessException(400, "必须选择驳回原因码");
        }
        String code = rejectCode.trim().toUpperCase();
        if (!REJECT_CODES.contains(code)) {
            throw new BusinessException(400, "驳回原因码不合法");
        }
        if (REJECT_CODE_OTHER.equals(code) && (reason == null || reason.isBlank())) {
            throw new BusinessException(400, "原因码为 OTHER 时必须填写说明");
        }
        return code;
    }

    /** 追加审核/生命周期审计；返回审计行ID（O8 通知 biz_id 锚点）。 */
    private Long writeAudit(MerchantOnboardingApplication application, String action,
                            String fromStatus, String toStatus, Long operatorId, String reason, String rejectCode) {
        MerchantOnboardingAudit audit = new MerchantOnboardingAudit();
        audit.setApplicationId(application.getId());
        audit.setMerchantId(application.getMerchantId());
        audit.setAction(action);
        audit.setFromStatus(fromStatus);
        audit.setToStatus(toStatus);
        audit.setOperatorId(operatorId);
        audit.setReason(reason);
        audit.setRejectCode(rejectCode);
        audit.setReviewStage(application.getReviewStage());
        audit.setMaterialHash(cipherService.fingerprint(
                application.getSubjectType() + "|" + application.getIdCardFingerprint()
                        + "|" + application.getSubjectName() + "|" + application.getContactPhone()));
        auditMapper.insert(audit);
        return audit.getId();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** O8 通知负载：键值成对、跳过 null 值（Map.of 不接受 null，手工构造的申请字段可能缺省）。 */
    private static Map<String, Object> notifyPayload(Object... kv) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                payload.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return payload;
    }

    /**
     * O9 证件有效期解析：空串/空白 → null；否则必须 yyyy-MM-dd。
     * 只校验格式，已过期日期照样受理（由审核员人工判断，拒绝时走 ID_CARD_EXPIRED/LICENSE_EXPIRED 原因码）。
     * 商家资料页更新有效期也复用本方法解析（更新方另要求未来日期）。
     */
    public static LocalDate parseExpiryDate(String value, String fieldLabel) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException(400, fieldLabel + "格式须为 yyyy-MM-dd");
        }
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? blankToNull(fallback) : preferred.trim();
    }

    /** 店铺名称禁用词校验；不含授权核验，含词即拒绝，避免审核通过后再改名规避。 */
    private void assertStoreNameAllowed(String storeName) {
        if (storeName == null) {
            return;
        }
        String normalized = storeName.replace(" ", "");
        for (String word : STORE_NAME_RESTRICTED_WORDS) {
            if (normalized.contains(word)) {
                throw new BusinessException(400,
                        "店铺名称不得包含“旗舰”“官方”“专卖”“专营”等词；品牌授权店铺请线下提交授权材料");
            }
        }
    }

    /** 自动店铺编码：S + yyyyMMdd + 4 位随机数；碰撞时重试，全部失败不带着空编码进入建店。 */
    private String generateUniqueStoreCode() {
        String prefix = "S" + LocalDate.now().format(STORE_CODE_DATE);
        for (int attempt = 0; attempt < 6; attempt++) {
            String candidate = prefix + String.format("%04d", secureRandom.nextInt(10000));
            if (storeMapper.findByStoreCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new BusinessException(500, "店铺编码生成失败，请稍后重试");
    }

    private String generatePassword() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder builder = new StringBuilder(GENERATED_PASSWORD_LENGTH);
            boolean hasLetter = false;
            boolean hasDigit = false;
            for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++) {
                char next = PASSWORD_ALPHABET.charAt(secureRandom.nextInt(PASSWORD_ALPHABET.length()));
                hasLetter |= Character.isLetter(next);
                hasDigit |= Character.isDigit(next);
                builder.append(next);
            }
            if (hasLetter && hasDigit) {
                return builder.toString();
            }
        }
        throw new BusinessException(500, "初始密码生成失败，请重试");
    }

    private String randomDigits(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(secureRandom.nextInt(10));
        }
        return builder.toString();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private boolean tokenMatches(MerchantOnboardingApplication application, String accessToken) {
        if (accessToken == null || accessToken.isBlank() || application.getAccessTokenHash() == null) {
            return false;
        }
        String actual = cipherService.fingerprint(accessToken);
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
                application.getAccessTokenHash().getBytes(StandardCharsets.UTF_8));
    }
}
