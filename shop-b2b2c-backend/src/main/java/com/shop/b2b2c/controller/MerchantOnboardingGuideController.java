/*
 * 文件：MerchantOnboardingGuideController.java
 * 用途：O4 商家端开通引导：店长查看本店入驻申请的开通进度（待签协议清单与签署状态），并在商家端完成协议签署。
 * 维护注意：
 * 1. 路径位于 /api/v1/merchant/**，由 SaTokenConfigure 统一拦截登录与商家角色；店铺上下文只取会话 merchantId，
 *    申请单经 findApprovedByMerchantId（APPROVED/OPENED）反查，不接受请求体传申请编号，防止越权查看他店申请。
 * 2. 签署协议仅店长（requireManagementRole），员工 403；签署校验（版本存在/已生效/哈希一致）全部收口在
 *    MerchantAgreementService.consent，本控制器不重复判定；签署齐全后由 MerchantAgreementService 同事务自动开通。
 * 3. 状态视图只返回协议类型/版本/标题/哈希/快照地址与签署标记，不返回证件、费率、银行卡等敏感字段。
 * 4. 已开通（OPENED）店铺仍可查询状态（供前端展示开通结果），但重复签署按 409 拒绝，避免开通后改写证据链。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.entity.MerchantAgreementConsent;
import com.shop.b2b2c.entity.MerchantAgreementVersion;
import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.service.AuthSessionService;
import com.shop.b2b2c.service.MerchantAgreementService;
import com.shop.b2b2c.service.MerchantOnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "商家开通引导 (Merchant Onboarding Guide)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/onboarding")
public class MerchantOnboardingGuideController {

    private final MerchantOnboardingService onboardingService;
    private final MerchantAgreementService agreementService;
    private final MerchantStoreMapper storeMapper;

    @Operation(summary = "【商家】开通引导状态：申请/店铺状态 + 待签协议清单与签署标记")
    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        MerchantOnboardingApplication application = requireApplication(merchantId);
        MerchantStore store = storeMapper.findById(merchantId)
                .orElseThrow(() -> new BusinessException(404, "店铺不存在"));
        Set<String> signed = agreementService.listConsents(application.getId()).stream()
                .map(item -> item.getProtocolType() + "|" + item.getProtocolVersion())
                .collect(Collectors.toSet());
        List<Map<String, Object>> protocols = new ArrayList<>();
        for (MerchantAgreementVersion version : agreementService.listPublished(null)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("protocolType", version.getProtocolType());
            item.put("version", version.getVersion());
            item.put("title", version.getTitle());
            item.put("contentHash", version.getContentHash());
            item.put("contentUrl", version.getContentUrl());
            item.put("effectiveFrom", version.getEffectiveFrom());
            item.put("signed", signed.contains(version.getProtocolType() + "|" + version.getVersion()));
            protocols.add(item);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("applicationId", application.getId());
        view.put("applicationStatus", application.getStatus());
        view.put("storeStatus", store.getStatus());
        view.put("agreedAt", application.getAgreedAt());
        view.put("openedAt", application.getOpenedAt());
        view.put("allConsentsSigned", agreementService.hasAllRequiredConsents(application.getId()));
        view.put("protocols", protocols);
        return R.ok(view);
    }

    @Data
    public static class ConsentRequest {
        private String protocolType;
        private String version;
        private String contentHash;
    }

    @Operation(summary = "【商家】签署开店协议（仅店长；签齐后自动开通店铺）")
    @PostMapping("/consents")
    public R<Map<String, Object>> consent(@RequestBody ConsentRequest body,
                                          jakarta.servlet.http.HttpServletRequest http) {
        // 签署协议是资金相关合规动作，只允许店长；员工最小权限不得代签。
        AuthSessionService.requireManagementRole();
        if (body == null) {
            throw new BusinessException(400, "缺少协议签署信息");
        }
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        MerchantOnboardingApplication application = requireApplication(merchantId);
        if (!MerchantOnboardingService.STATUS_APPROVED.equals(application.getStatus())) {
            throw new BusinessException(409, "店铺已开通或申请不在待开通状态，无需重复签署");
        }
        MerchantAgreementConsent consent = agreementService.consent(
                application.getId(), merchantId,
                body.getProtocolType(), body.getVersion(), body.getContentHash(),
                application.getSubjectName(), clientIp(http), http.getHeader("User-Agent"));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("protocolType", consent.getProtocolType());
        view.put("protocolVersion", consent.getProtocolVersion());
        view.put("contentHash", consent.getContentHash());
        view.put("agreedAt", consent.getAgreedAt());
        return R.ok("协议签署成功", view);
    }

    /** 本店入驻申请必须存在且处于 APPROVED/OPENED；其他阶段（含未审核）无权使用开通引导。 */
    private MerchantOnboardingApplication requireApplication(Long merchantId) {
        MerchantOnboardingApplication application = onboardingService.findApprovedByMerchantId(merchantId);
        if (application == null) {
            throw new BusinessException(404, "当前店铺没有待开通的入驻申请");
        }
        return application;
    }

    /** 反代后优先取 X-Forwarded-For 首段；证据采集尽力而为，不校验真伪（与公开签署入口同一口径）。 */
    private String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
