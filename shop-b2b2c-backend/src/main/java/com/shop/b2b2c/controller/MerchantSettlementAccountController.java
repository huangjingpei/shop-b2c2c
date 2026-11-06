/*
 * 文件：MerchantSettlementAccountController.java
 * 用途：平台超管登记/核验/停用商家收款账户与打款验证，店长查看本店账户（脱敏）、发起账户变更与回填打款金额。
 * 维护注意：
 * 1. /api/v1/admin/** 沿用超管鉴权；/api/v1/merchant/settlement-accounts 仅店长可读，员工最小权限不得查看资金账户。
 * 2. 响应全部使用 maskedList，禁止返回 accountNoCipher/accountNo 明文。
 * 3. 核验动作只允许通过/驳回；驳回必须带原因。停用不删除历史行。
 * 4. 登记与核验由服务层做户名=实名主体硬校验，控制器不得提供人工放行参数。
 * 5. O5 打款验证：amount 只允许出现在超管视图（adminView），商家端视图/列表一律不返回金额与转账凭证号；
 *    商家只能提交到账金额进行比对，不得查询服务端生成的金额。
 * 6. O5 账户变更：店长仅能发起 change-requests（生成 PENDING 新账户），切换默认只发生在平台核验/打款通过事务内。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.entity.MerchantSettlementAccountAudit;
import com.shop.b2b2c.mapper.MerchantSettlementAccountAuditMapper;
import com.shop.b2b2c.service.MerchantPayoutVerificationService;
import com.shop.b2b2c.service.MerchantSettlementAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "商家收款账户 (Settlement Account)")
@RestController
@RequiredArgsConstructor
public class MerchantSettlementAccountController {

    private final MerchantSettlementAccountService settlementAccountService;
    private final MerchantSettlementAccountAuditMapper auditMapper;
    private final MerchantPayoutVerificationService payoutVerificationService;

    @Data
    public static class VerifyDTO {
        private boolean approved;
        private String reason;
    }

    @Data
    public static class ChangeRequestDTO {
        private String accountType;
        private String holderName;
        private String bankName;
        private String bankBranch;
        private String accountNo;
        private String reason;
    }

    @Data
    public static class PayoutInitiateDTO {
        private String transferVoucherNo;
    }

    @Data
    public static class PayoutConfirmDTO {
        private BigDecimal amount;
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

    @Operation(summary = "【超管】发起打款验证（返回服务端生成金额，仅平台可见）")
    @PostMapping("/api/v1/admin/merchants/{merchantId}/settlement-accounts/{accountId}/payout-verifications")
    public R<Map<String, Object>> initiatePayout(@PathVariable Long merchantId, @PathVariable Long accountId,
                                                 @RequestBody(required = false) PayoutInitiateDTO dto) {
        requireOwnership(merchantId, accountId);
        String voucherNo = dto == null ? null : dto.getTransferVoucherNo();
        return R.ok(payoutVerificationService.adminView(payoutVerificationService.initiate(
                merchantId, accountId, voucherNo, SecurityUtils.getCurrentUserId())));
    }

    @Operation(summary = "【超管】账户打款验证记录（含金额，仅平台可见）")
    @GetMapping("/api/v1/admin/merchants/{merchantId}/settlement-accounts/{accountId}/payout-verifications")
    public R<List<Map<String, Object>>> listPayouts(@PathVariable Long merchantId,
                                                    @PathVariable Long accountId) {
        requireOwnership(merchantId, accountId);
        return R.ok(payoutVerificationService.listByAccount(accountId).stream()
                .map(payoutVerificationService::adminView)
                .collect(Collectors.toList()));
    }

    @Operation(summary = "【超管】代确认打款到账金额（确认人须与发起人不同）")
    @PostMapping("/api/v1/admin/merchants/{merchantId}/settlement-accounts/{accountId}/payout-verifications/{verificationId}/confirm")
    public R<Map<String, Object>> confirmPayoutByAdmin(@PathVariable Long merchantId,
                                                       @PathVariable Long accountId,
                                                       @PathVariable Long verificationId,
                                                       @RequestBody PayoutConfirmDTO dto) {
        requireOwnership(merchantId, accountId);
        return R.ok(payoutVerificationService.adminView(payoutVerificationService.confirmByAdmin(
                verificationId, dto == null ? null : dto.getAmount(), SecurityUtils.getCurrentUserId())));
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
        requireMerchantAdmin();
        return R.ok(settlementAccountService.maskedList(SecurityUtils.requireCurrentMerchantId()));
    }

    @Operation(summary = "【店长】发起收款账户变更申请（生成待核验新账户，不切换默认）")
    @PostMapping("/api/v1/merchant/settlement-accounts/change-requests")
    public R<Map<String, Object>> requestChange(@RequestBody ChangeRequestDTO dto) {
        requireMerchantAdmin();
        if (dto == null) {
            throw new BusinessException(400, "变更申请不能为空");
        }
        MerchantSettlementAccountService.RegisterForm form = new MerchantSettlementAccountService.RegisterForm();
        form.setAccountType(dto.getAccountType());
        form.setHolderName(dto.getHolderName());
        form.setBankName(dto.getBankName());
        form.setBankBranch(dto.getBankBranch());
        form.setAccountNo(dto.getAccountNo());
        form.setDefault(false);
        return R.ok(settlementAccountService.maskedView(settlementAccountService.requestChange(
                SecurityUtils.requireCurrentMerchantId(), form, dto.getReason(), SecurityUtils.getCurrentUserId())));
    }

    @Operation(summary = "【店长】本店打款验证列表（不含金额）")
    @GetMapping("/api/v1/merchant/settlement-accounts/payout-verifications")
    public R<List<Map<String, Object>>> currentMerchantPayouts() {
        requireMerchantAdmin();
        return R.ok(payoutVerificationService.merchantViews(SecurityUtils.requireCurrentMerchantId()));
    }

    @Operation(summary = "【店长】回填打款到账金额完成验证")
    @PostMapping("/api/v1/merchant/settlement-accounts/payout-verifications/{verificationId}/confirm")
    public R<Map<String, Object>> confirmPayoutByMerchant(@PathVariable Long verificationId,
                                                          @RequestBody PayoutConfirmDTO dto) {
        requireMerchantAdmin();
        return R.ok(payoutVerificationService.merchantView(payoutVerificationService.confirmByMerchant(
                verificationId, SecurityUtils.requireCurrentMerchantId(),
                dto == null ? null : dto.getAmount(), SecurityUtils.getCurrentUserId())));
    }

    private void requireMerchantAdmin() {
        if (!"MERCHANT_ADMIN".equals(SecurityUtils.getCurrentRoleCode())) {
            throw new BusinessException(403, "仅店长可操作收款账户");
        }
    }

    private void requireOwnership(Long merchantId, Long accountId) {
        boolean belongs = settlementAccountService.list(merchantId).stream()
                .anyMatch(account -> account.getId().equals(accountId));
        if (!belongs) {
            throw new BusinessException(404, "收款账户不属于该店铺");
        }
    }
}
