/*
 * 文件：MerchantOnboardingController.java
 * 用途：平台超管侧的商家入驻申请、实名材料维护与审核接口。
 * 维护注意：
 * 1. 路由统一位于 /api/v1/admin/**，沿用现有超管鉴权；审核角色尚未由用户最终确认，本卡先复用超管，不得自行新增角色码。
 * 2. 所有响应走 MerchantOnboardingService.maskedView，身份证/营业执照只返回脱敏值，禁止直接序列化实体。
 * 3. 审核动作只允许 START_REVIEW/APPROVE/REJECT/SUPPLEMENT_REQUIRED；APPROVE 必须携带开店信息，REJECT/SUPPLEMENT 必须携带原因。
 * 4. 申请创建与材料修改不允许直接设置 merchantId 或 APPROVED 状态，开店只能由审核通过触发。
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
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "商家入驻审核 (Merchant Onboarding)")
@RestController
@RequiredArgsConstructor
public class MerchantOnboardingController {

    private final MerchantOnboardingService onboardingService;

    @Data
    public static class ReviewDTO {
        private String action;
        private String reason;
        private String storeCode;
        private String storeName;
        private Integer merchantType;
        private String adminUsername;
        private String adminPassword;
        private Integer rateBps;
        private String promotionReason;
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
                application = onboardingService.reject(id, dto.getReason(), reviewerId);
                break;
            case MerchantOnboardingService.ACTION_SUPPLEMENT_REQUIRED:
                application = onboardingService.requireSupplement(id, dto.getReason(), reviewerId);
                break;
            default:
                throw new BusinessException(400, "不支持的审核动作");
        }
        return R.ok(onboardingService.maskedView(application));
    }

    @Operation(summary = "【超管】撤回申请")
    @PostMapping("/api/v1/admin/merchant-onboarding/applications/{id}/withdraw")
    public R<Map<String, Object>> withdraw(@PathVariable Long id) {
        MerchantOnboardingApplication application = onboardingService.withdraw(id, SecurityUtils.getCurrentUserId());
        return R.ok(onboardingService.maskedView(application));
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
        return provisioning;
    }
}
