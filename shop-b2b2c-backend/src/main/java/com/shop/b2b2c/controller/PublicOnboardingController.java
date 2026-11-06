/*
 * 文件：PublicOnboardingController.java
 * 用途：未登录商家自助开店接口：创建申请、维护材料、上传证件、提交审核与进度查询。
 * 维护注意：
 * 1. 路由 /api/v1/onboarding/** 在 SaTokenConfigure 中免登录，必须依赖 X-Onboarding-Token 查询凭证做对象级校验；
 *    凭证明文只在创建响应返回一次，库中只存 SHA-256，不得写入日志。
 * 2. 公开接口不能推进审核状态（不能开始审核/通过/驳回/撤回），这些动作只允许平台超管接口操作。
 * 3. 创建时主体类型缺省为 INDIVIDUAL（个人身份证开店）；INDIVIDUAL_BUSINESS/ENTERPRISE 提交时必须同时具备营业执照与经营者/法人材料。
 * 4. 提交前校验身份证正反面附件；个人店还必须上传本人出镜朗读随机数字的实人认证视频（人工审核比对，未接第三方人脸核验）；
 *    个体工商户/企业还必须上传营业执照。附件本体只能由超管审核接口下载。
 * 5. 进度查询必须同时匹配查询凭证与联系电话，任一不符统一返回 404，避免枚举申请ID。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import com.shop.b2b2c.entity.MerchantOnboardingAttachment;
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
