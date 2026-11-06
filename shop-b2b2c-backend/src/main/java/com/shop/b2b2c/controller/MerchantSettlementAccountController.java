/*
 * 文件：MerchantSettlementAccountController.java
 * 用途：平台超管登记/核验/停用商家收款账户，以及店长查看本店账户（脱敏）。
 * 维护注意：
 * 1. /api/v1/admin/** 沿用超管鉴权；/api/v1/merchant/settlement-accounts 仅店长可读，员工最小权限不得查看资金账户。
 * 2. 响应全部使用 maskedList，禁止返回 accountNoCipher/accountNo 明文。
 * 3. 核验动作只允许通过/驳回；驳回必须带原因。停用不删除历史行。
 * 4. 登记与核验由服务层做户名=实名主体硬校验，控制器不得提供人工放行参数。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.entity.MerchantSettlementAccountAudit;
import com.shop.b2b2c.mapper.MerchantSettlementAccountAuditMapper;
import com.shop.b2b2c.service.MerchantSettlementAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "商家收款账户 (Settlement Account)")
@RestController
@RequiredArgsConstructor
public class MerchantSettlementAccountController {

    private final MerchantSettlementAccountService settlementAccountService;
    private final MerchantSettlementAccountAuditMapper auditMapper;

    @Data
    public static class VerifyDTO {
        private boolean approved;
        private String reason;
    }

    @Operation(summary = "【超管】店铺收款账户列表（账号脱敏）")
    @GetMapping("/api/v1/admin/merchants/{merchantId}/settlement-accounts")
    public R<List<Map<String, Object>>> list(@PathVariable Long merchantId) {
        return R.ok(settlementAccountService.maskedList(merchantId));
    }

    @Operation(summary = "【超管】登记商家收款账户（待人工核验）")
    @PostMapping("/api/v1/admin/merchants/{merchantId}/settlement-accounts")
    public R<Map<String, Object>> register(@PathVariable Long merchantId,
                                           @RequestBody MerchantSettlementAccountService.RegisterForm form) {
        return R.ok(settlementAccountService.maskedView(
                settlementAccountService.register(merchantId, form, SecurityUtils.getCurrentUserId())));
    }

    @Operation(summary = "【超管】人工核验收款账户")
    @PostMapping("/api/v1/admin/merchants/{merchantId}/settlement-accounts/{accountId}/verify")
    public R<Map<String, Object>> verify(@PathVariable Long merchantId, @PathVariable Long accountId,
                                         @RequestBody VerifyDTO dto) {
        requireOwnership(merchantId, accountId);
        return R.ok(settlementAccountService.maskedView(
                settlementAccountService.verify(accountId, dto.isApproved(), dto.getReason(),
                        SecurityUtils.getCurrentUserId())));
    }

    @Operation(summary = "【超管】停用商家收款账户")
    @PostMapping("/api/v1/admin/merchants/{merchantId}/settlement-accounts/{accountId}/disable")
    public R<Map<String, Object>> disable(@PathVariable Long merchantId, @PathVariable Long accountId,
                                          @RequestParam(required = false) String reason) {
        requireOwnership(merchantId, accountId);
        return R.ok(settlementAccountService.maskedView(
                settlementAccountService.disable(accountId, reason, SecurityUtils.getCurrentUserId())));
    }

    @Operation(summary = "【超管】收款账户审计时间线")
    @GetMapping("/api/v1/admin/merchants/{merchantId}/settlement-accounts/{accountId}/audits")
    public R<List<MerchantSettlementAccountAudit>> audits(@PathVariable Long merchantId, @PathVariable Long accountId) {
        requireOwnership(merchantId, accountId);
        return R.ok(auditMapper.findByAccountId(accountId));
    }

    @Operation(summary = "【店长】本店收款账户列表（账号脱敏）")
    @GetMapping("/api/v1/merchant/settlement-accounts")
    public R<List<Map<String, Object>>> currentMerchantAccounts() {
        if (!"MERCHANT_ADMIN".equals(SecurityUtils.getCurrentRoleCode())) {
            throw new BusinessException(403, "仅店长可查看收款账户");
        }
        return R.ok(settlementAccountService.maskedList(SecurityUtils.requireCurrentMerchantId()));
    }

    private void requireOwnership(Long merchantId, Long accountId) {
        boolean belongs = settlementAccountService.list(merchantId).stream()
                .anyMatch(account -> account.getId().equals(accountId));
        if (!belongs) {
            throw new BusinessException(404, "收款账户不属于该店铺");
        }
    }
}
