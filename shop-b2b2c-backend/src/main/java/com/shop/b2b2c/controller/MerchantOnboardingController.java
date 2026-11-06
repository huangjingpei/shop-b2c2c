/*
 * 文件：MerchantOnboardingController.java
 * 用途：平台超管侧的商家入驻申请、实名材料维护与审核接口。
 * 维护注意：
 * 1. 路由统一位于 /api/v1/admin/**，沿用现有超管鉴权；审核角色尚未由用户最终确认，本卡先复用超管，不得自行新增角色码。
 * 2. 所有响应走 MerchantOnboardingService.maskedView，身份证/营业执照只返回脱敏值，禁止直接序列化实体。
 * 3. 审核动作只允许 START_REVIEW/APPROVE/REJECT/SUPPLEMENT_REQUIRED；APPROVE 可只传审核意见，系统自动生成店铺编码、登录账号（申请手机号）与初始密码，显式开店参数仍优先；REJECT/SUPPLEMENT 必须携带原因。
 * 4. 申请创建与材料修改不允许直接设置 merchantId 或 APPROVED 状态，开店只能由审核通过触发。
 * 5. 证件附件只能经本控制器超管鉴权下载（inline），不得暴露私有目录路径；审核通过响应中的 initialPassword 只出现一次，前端不得留存。
 * 6. O3 协议同意记录只读查询（append-only 证据链）：返回签署人、版本、哈希、IP/UA 与签署时间，不提供修改/删除入口。
 * 7. O4 open 兜底开通：仅 APPROVED 且协议签齐可开通（否则 409），重复开通幂等返回；
 *    双人复核开启时 confirmedBy 必填且必须与操作人不同（缺参 428、同人 403，校验走 FinanceApprovalGuard）。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import com.shop.b2b2c.entity.MerchantOnboardingAudit;
import com.shop.b2b2c.service.MerchantOnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "商家入驻审核 (Merchant Onboarding)")
@RestController
@RequiredArgsConstructor
public class MerchantOnboardingController {

    private final MerchantOnboardingService onboardingService;
    private final com.shop.b2b2c.service.OnboardingAttachmentService attachmentService;
    private final com.shop.b2b2c.service.MerchantAgreementService agreementService;

    @Data
    public static class ReviewDTO {
        private String action;
        private String reason;
        /** 驳回/补件原因码（文档 5.5 字典）；REJECT/SUPPLEMENT_REQUIRED 必填，OTHER 时 reason 必填。 */
        private String rejectCode;
        private String storeCode;
        private String storeName;
        private Integer merchantType;
        private String adminUsername;
        private String adminPassword;
        private Integer rateBps;
        private String promotionReason;
        /** 初始费率复核人；生产双人复核开启时必须与当前审核人不同。 */
        private Long rateConfirmedBy;
    }

    @Operation(summary = "【超管】入驻申请列表（证件脱敏）")
    @GetMapping("/api/v1/admin/merchant-onboarding/applications")
    public R<List<Map<String, Object>>> list() {
        List<Map<String, Object>> views = onboardingService.list().stream()
                .map(onboardingService::maskedView)
                .collect(Collectors.toList());
        return R.ok(views);
    }

    @Operation(summary = "【超管】入驻申请详情（证件脱敏，不返回密文）")
    @GetMapping("/api/v1/admin/merchant-onboarding/applications/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        MerchantOnboardingApplication application = onboardingService.findById(id);
        return R.ok(onboardingService.maskedView(application));
    }

    @Operation(summary = "【超管】入驻审核时间线")
    @GetMapping("/api/v1/admin/merchant-onboarding/applications/{id}/audits")
    public R<List<MerchantOnboardingAudit>> audits(@PathVariable Long id) {
        return R.ok(onboardingService.audits(id));
    }

    @Operation(summary = "【超管】协议同意记录（append-only 证据链）")
    @GetMapping("/api/v1/admin/merchant-onboarding/applications/{id}/consents")
    public R<List<com.shop.b2b2c.entity.MerchantAgreementConsent>> consents(@PathVariable Long id) {
        onboardingService.findById(id);
        return R.ok(agreementService.listConsents(id));
    }

    @Operation(summary = "【超管】证件附件列表（不含文件本体）")
    @GetMapping("/api/v1/admin/merchant-onboarding/applications/{id}/attachments")
    public R<List<Map<String, Object>>> attachments(@PathVariable Long id) {
        onboardingService.findById(id);
        return R.ok(attachmentService.list(id).stream()
                .map(attachmentService::view)
                .collect(Collectors.toList()));
    }

    @Operation(summary = "【超管】下载证件/实人认证视频（inline，仅审核使用）")
    @GetMapping("/api/v1/admin/merchant-onboarding/applications/{id}/attachments/{attachmentId}")
    public ResponseEntity<Resource> download(@PathVariable Long id, @PathVariable Long attachmentId) {
        onboardingService.findById(id);
        com.shop.b2b2c.entity.MerchantOnboardingAttachment attachment = attachmentService.find(id, attachmentId);
        Path path = attachmentService.load(attachment);
        String filename = attachment.getKind() + extension(attachment.getContentType());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(new FileSystemResource(path));
    }

    private String extension(String contentType) {
        if ("image/jpeg".equals(contentType)) return ".jpg";
        if ("image/png".equals(contentType)) return ".png";
        if ("image/webp".equals(contentType)) return ".webp";
        if ("application/pdf".equals(contentType)) return ".pdf";
        if ("video/mp4".equals(contentType)) return ".mp4";
        if ("video/webm".equals(contentType)) return ".webm";
        return "";
    }

    @Operation(summary = "【超管】新建入驻申请（草稿）")
    @PostMapping("/api/v1/admin/merchant-onboarding/applications")
    public R<Map<String, Object>> create(@RequestBody MerchantOnboardingService.ApplicationForm form) {
        MerchantOnboardingApplication application = onboardingService.create(form, SecurityUtils.getCurrentUserId());
        return R.ok(onboardingService.maskedView(application));
    }

    @Operation(summary = "【超管】修改草稿/补件材料")
    @PutMapping("/api/v1/admin/merchant-onboarding/applications/{id}")
    public R<Map<String, Object>> updateMaterials(@PathVariable Long id,
                                                  @RequestBody MerchantOnboardingService.ApplicationForm form) {
        MerchantOnboardingApplication application =
                onboardingService.updateMaterials(id, form, SecurityUtils.getCurrentUserId());
        return R.ok(onboardingService.maskedView(application));
    }

    @Operation(summary = "【超管】提交审核")
    @PostMapping("/api/v1/admin/merchant-onboarding/applications/{id}/submit")
    public R<Map<String, Object>> submit(@PathVariable Long id) {
        MerchantOnboardingApplication application = onboardingService.submit(id, SecurityUtils.getCurrentUserId());
        return R.ok(onboardingService.maskedView(application));
    }

    @Operation(summary = "【超管】审核：开始审核/通过开店/驳回/要求补件")
    @PostMapping("/api/v1/admin/merchant-onboarding/applications/{id}/review")
    public R<Map<String, Object>> review(@PathVariable Long id, @RequestBody ReviewDTO dto) {
        if (dto == null || dto.getAction() == null || dto.getAction().isBlank()) {
            throw new BusinessException(400, "审核动作必填");
        }
        Long reviewerId = SecurityUtils.getCurrentUserId();
        MerchantOnboardingApplication application;
        switch (dto.getAction().trim().toUpperCase()) {
            case MerchantOnboardingService.ACTION_START_REVIEW:
                application = onboardingService.startReview(id, reviewerId);
                break;
            case MerchantOnboardingService.ACTION_APPROVE:
                application = onboardingService.approve(id, toProvisioning(dto), dto.getReason(), reviewerId);
                break;
            case MerchantOnboardingService.ACTION_REJECT:
                application = onboardingService.reject(id, dto.getRejectCode(), dto.getReason(), reviewerId);
                break;
            case MerchantOnboardingService.ACTION_SUPPLEMENT_REQUIRED:
                application = onboardingService.requireSupplement(id, dto.getRejectCode(), dto.getReason(), reviewerId);
                break;
            default:
                throw new BusinessException(400, "不支持的审核动作");
        }
        Map<String, Object> view = onboardingService.maskedView(application);
        // 自动开店生成的初始密码只在本次审核响应返回一次，前端必须立即展示且不得持久化。
        if (application.getInitialPassword() != null) {
            view.put("initialPassword", application.getInitialPassword());
            view.put("storeCode", application.getStoreCode());
        }
        return R.ok(view);
    }

    @Operation(summary = "【超管】撤回申请")
    @PostMapping("/api/v1/admin/merchant-onboarding/applications/{id}/withdraw")
    public R<Map<String, Object>> withdraw(@PathVariable Long id) {
        MerchantOnboardingApplication application = onboardingService.withdraw(id, SecurityUtils.getCurrentUserId());
        return R.ok(onboardingService.maskedView(application));
    }

    @Operation(summary = "【超管】开通店铺（O4 兜底；协议签齐后开通，双人复核开启时须带复核人）")
    @PostMapping("/api/v1/admin/merchant-onboarding/applications/{id}/open")
    public R<Map<String, Object>> open(@PathVariable Long id,
                                       @RequestParam(required = false) Long confirmedBy) {
        MerchantOnboardingApplication application =
                onboardingService.open(id, SecurityUtils.getCurrentUserId(), confirmedBy);
        return R.ok("店铺已开通", onboardingService.maskedView(application));
    }

    private MerchantOnboardingService.Provisioning toProvisioning(ReviewDTO dto) {
        MerchantOnboardingService.Provisioning provisioning = new MerchantOnboardingService.Provisioning();
        provisioning.setStoreCode(dto.getStoreCode());
        provisioning.setStoreName(dto.getStoreName());
        provisioning.setMerchantType(dto.getMerchantType());
        provisioning.setAdminUsername(dto.getAdminUsername());
        provisioning.setAdminPassword(dto.getAdminPassword());
        provisioning.setRateBps(dto.getRateBps());
        provisioning.setPromotionReason(dto.getPromotionReason());
        provisioning.setRateConfirmedBy(dto.getRateConfirmedBy());
        return provisioning;
    }
}
