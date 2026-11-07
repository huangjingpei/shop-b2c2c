/*
 * 文件：PublicOnboardingController.java
 * 用途：未登录商家自助开店接口：创建申请、维护材料、上传证件、提交审核与进度查询。
 * 维护注意：
 * 1. 路由 /api/v1/onboarding/** 在 SaTokenConfigure 中免登录，必须依赖 X-Onboarding-Token 查询凭证做对象级校验；
 *    凭证明文只在创建响应返回一次，库中只存 SHA-256，不得写入日志。
 * 2. 公开接口不能执行审核动作（不能开始审核/通过/驳回/要求补件），这些动作只允许平台超管接口操作；
 *    申请人撤回是申请人自身权利，见第 7 条。
 * 3. 创建时主体类型缺省为 INDIVIDUAL（个人身份证开店）；INDIVIDUAL_BUSINESS/ENTERPRISE 提交时必须同时具备营业执照与经营者/法人材料。
 * 4. 提交前校验身份证正反面附件；个人店还必须上传本人出镜朗读随机数字的实人认证视频（人工审核比对，未接第三方人脸核验）；
 *    个体工商户/企业还必须上传营业执照。附件本体只能由超管审核接口下载。
 * 5. 进度查询必须同时匹配查询凭证与联系电话，任一不符统一返回 404，避免枚举申请ID。
 * 6. O3 协议：/protocols 公开返回当前生效协议（类型/版本/标题/哈希/快照地址）；/consents 凭 token 勾选同意，
 *    服务端校验版本存在且已生效、哈希一致，记录 IP/UA；重复签署同一版本幂等成功。
 * 7. O3 撤回：申请人凭 token 可撤回 DRAFT/SUBMITTED/REVIEWING 状态的申请，终态不可撤回。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.entity.MerchantAgreementConsent;
import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import com.shop.b2b2c.entity.MerchantOnboardingAttachment;
import com.shop.b2b2c.service.MerchantAgreementService;
import com.shop.b2b2c.service.MerchantOnboardingService;
import com.shop.b2b2c.service.OnboardingAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "商家自助开店 (Public Onboarding)")
@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
public class PublicOnboardingController {

    private final MerchantOnboardingService onboardingService;
    private final OnboardingAttachmentService attachmentService;
    private final MerchantAgreementService agreementService;

    @Data
    public static class ApplyRequest {
        /** 缺省 INDIVIDUAL：个人身份证开店是默认方式。 */
        private String subjectType;
        private String subjectName;
        private String storeName;
        private String idCardNumber;
        private String licenseNo;
        private String licenseUrl;
        private String legalPerson;
        private String contactPhone;
        private String contactEmail;
    }

    @Operation(summary = "创建开店申请（返回一次性查询凭证）")
    @PostMapping("/applications")
    public R<Map<String, Object>> create(@RequestBody ApplyRequest request) {
        MerchantOnboardingService.PublicTicket ticket = onboardingService.createPublic(toForm(request));
        Map<String, Object> view = onboardingService.publicView(ticket.getApplication());
        view.put("accessToken", ticket.getAccessToken());
        return R.ok("申请已创建，请妥善保存查询凭证", view);
    }

    @Operation(summary = "修改草稿/补件材料")
    @PutMapping("/applications/{id}")
    public R<Map<String, Object>> update(@PathVariable Long id,
            @RequestHeader(value = "X-Onboarding-Token", required = false) String token,
            @RequestBody ApplyRequest request) {
        MerchantOnboardingApplication application =
                onboardingService.updatePublic(id, token, toForm(request));
        return R.ok(onboardingService.publicView(application));
    }

    @Operation(summary = "上传证件（仅草稿/补件状态）")
    @PostMapping("/applications/{id}/attachments")
    public R<Map<String, Object>> upload(@PathVariable Long id,
            @RequestHeader(value = "X-Onboarding-Token", required = false) String token,
            @RequestParam("kind") String kind,
            @RequestParam("file") MultipartFile file) {
        MerchantOnboardingApplication application = onboardingService.requireByToken(id, token);
        if (!MerchantOnboardingService.STATUS_DRAFT.equals(application.getStatus())
                && !MerchantOnboardingService.STATUS_SUPPLEMENT_REQUIRED.equals(application.getStatus())) {
            throw new BusinessException(409, "仅草稿或补件状态可上传证件");
        }
        MerchantOnboardingAttachment attachment = attachmentService.store(id, kind, file);
        return R.ok(attachmentService.view(attachment));
    }

    @Operation(summary = "已上传附件列表（只返回类型/大小/时间，不含文件本体）")
    @GetMapping("/applications/{id}/attachments")
    public R<List<Map<String, Object>>> attachments(@PathVariable Long id,
            @RequestHeader(value = "X-Onboarding-Token", required = false) String token) {
        onboardingService.requireByToken(id, token);
        return R.ok(attachmentService.list(id).stream()
                .map(attachmentService::view)
                .collect(Collectors.toList()));
    }

    @Operation(summary = "提交审核（校验必传证件）")
    @PostMapping("/applications/{id}/submit")
    public R<Map<String, Object>> submit(@PathVariable Long id,
            @RequestHeader(value = "X-Onboarding-Token", required = false) String token) {
        MerchantOnboardingApplication application = onboardingService.requireByToken(id, token);
        requireAttachments(application);
        return R.ok(onboardingService.publicView(onboardingService.submitPublic(id, token)));
    }

    @Operation(summary = "查询审核进度（凭证 + 手机号）")
    @GetMapping("/applications/{id}")
    public R<Map<String, Object>> status(@PathVariable Long id,
            @RequestHeader(value = "X-Onboarding-Token", required = false) String token,
            @RequestParam(required = false) String phone) {
        return R.ok(onboardingService.publicStatus(id, token, phone));
    }

    @Operation(summary = "凭手机号 + 凭证查询进度（无需申请ID，供查询入口使用）")
    @GetMapping("/applications/by-phone")
    public R<Map<String, Object>> statusByPhone(
            @RequestHeader(value = "X-Onboarding-Token", required = false) String token,
            @RequestParam String phone) {
        MerchantOnboardingApplication application = onboardingService.findByPhoneAndToken(phone, token);
        if (application == null) {
            throw new BusinessException(404, "入驻申请不存在或查询凭证无效");
        }
        return R.ok(onboardingService.publicStatus(application.getId(), token, phone));
    }

    @Operation(summary = "当前生效协议列表（类型/版本/标题/哈希/快照地址）")
    @GetMapping("/protocols")
    public R<List<Map<String, Object>>> protocols(@RequestParam(required = false) String type) {
        return R.ok(agreementService.listPublished(type).stream()
                .map(this::protocolView)
                .collect(Collectors.toList()));
    }

    @Operation(summary = "勾选同意协议（凭查询凭证；重复签署同一版本幂等）")
    @PostMapping("/applications/{id}/consents")
    public R<Map<String, Object>> consent(@PathVariable Long id,
            @RequestHeader(value = "X-Onboarding-Token", required = false) String token,
            @RequestBody ConsentRequest body,
            jakarta.servlet.http.HttpServletRequest http) {
        MerchantOnboardingApplication application = onboardingService.requireByToken(id, token);
        if (body == null) {
            throw new BusinessException(400, "缺少协议签署信息");
        }
        MerchantAgreementConsent consent = agreementService.consent(
                application.getId(), application.getMerchantId(),
                body.getProtocolType(), body.getVersion(), body.getContentHash(),
                application.getSubjectName(), clientIp(http), http.getHeader("User-Agent"));
        return R.ok("协议签署成功", consentView(consent));
    }

    @Operation(summary = "申请人撤回（仅 DRAFT/SUBMITTED/REVIEWING）")
    @PostMapping("/applications/{id}/withdraw")
    public R<Map<String, Object>> withdraw(@PathVariable Long id,
            @RequestHeader(value = "X-Onboarding-Token", required = false) String token) {
        onboardingService.requireByToken(id, token);
        return R.ok(onboardingService.publicView(onboardingService.withdraw(id, null)));
    }

    @Data
    public static class ConsentRequest {
        private String protocolType;
        private String version;
        private String contentHash;
    }

    private Map<String, Object> protocolView(com.shop.b2b2c.entity.MerchantAgreementVersion version) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("protocolType", version.getProtocolType());
        view.put("version", version.getVersion());
        view.put("title", version.getTitle());
        view.put("contentHash", version.getContentHash());
        view.put("contentUrl", version.getContentUrl());
        view.put("effectiveFrom", version.getEffectiveFrom());
        return view;
    }

    private Map<String, Object> consentView(MerchantAgreementConsent consent) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("protocolType", consent.getProtocolType());
        view.put("protocolVersion", consent.getProtocolVersion());
        view.put("contentHash", consent.getContentHash());
        view.put("consentMethod", consent.getConsentMethod());
        view.put("agreedAt", consent.getAgreedAt());
        return view;
    }

    /** 反代后优先取 X-Forwarded-For 首段；证据采集尽力而为，不校验真伪。 */
    private String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 个人店只要求身份证正反面；个体工商户/企业再加营业执照。 */
    private void requireAttachments(MerchantOnboardingApplication application) {
        List<MerchantOnboardingAttachment> attachments = attachmentService.list(application.getId());
        Set<String> kinds = attachments.stream()
                .map(MerchantOnboardingAttachment::getKind)
                .collect(Collectors.toSet());
        if (!kinds.contains(OnboardingAttachmentService.KIND_ID_CARD_FRONT)
                || !kinds.contains(OnboardingAttachmentService.KIND_ID_CARD_BACK)) {
            throw new BusinessException(400, "请上传身份证人像面与国徽面照片");
        }
        if (MerchantOnboardingService.SUBJECT_INDIVIDUAL.equals(application.getSubjectType())
                && !kinds.contains(OnboardingAttachmentService.KIND_FACE_VIDEO)) {
            throw new BusinessException(400, "个人店须上传本人出镜朗读验证数字的认证视频");
        }
        if ((MerchantOnboardingService.SUBJECT_INDIVIDUAL_BUSINESS.equals(application.getSubjectType())
                || MerchantOnboardingService.SUBJECT_ENTERPRISE.equals(application.getSubjectType()))
                && !kinds.contains(OnboardingAttachmentService.KIND_LICENSE)) {
            throw new BusinessException(400, "个体工商户/企业须上传营业执照照片");
        }
    }

    private MerchantOnboardingService.ApplicationForm toForm(ApplyRequest request) {
        if (request == null) {
            throw new BusinessException(400, "开店申请材料不能为空");
        }
        MerchantOnboardingService.ApplicationForm form = new MerchantOnboardingService.ApplicationForm();
        form.setSubjectType(request.getSubjectType() == null || request.getSubjectType().isBlank()
                ? MerchantOnboardingService.SUBJECT_INDIVIDUAL : request.getSubjectType());
        form.setSubjectName(request.getSubjectName());
        form.setStoreName(request.getStoreName());
        form.setIdCardNumber(request.getIdCardNumber());
        form.setLicenseNo(request.getLicenseNo());
        form.setLicenseUrl(request.getLicenseUrl());
        form.setLegalPerson(request.getLegalPerson());
        form.setContactPhone(request.getContactPhone());
        form.setContactEmail(request.getContactEmail());
        return form;
    }
}
