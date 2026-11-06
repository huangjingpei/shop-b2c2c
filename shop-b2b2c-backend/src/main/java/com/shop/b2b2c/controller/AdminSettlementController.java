/*
 * 文件：AdminSettlementController.java
 * 用途：平台超管的充值确认/驳回、结算批次生成与确认、逐笔明细查询入口（T34）。
 * 维护注意：
 *  1. 路径位于 /api/v1/admin/**，由 SaTokenConfigure 统一要求 SUPERADMIN，控制器内仍显式校验一次。
 *  2. 充值确认以人工核对后的金额入账；确认接口幂等由充值单号+账本幂等键保证。
 *  3. 批次生成只生成/重算 DRAFT，确认后不可再改；跨店查询必须显式传 merchantId。
 *  4. 复核人候选只返回启用中的其他 SUPERADMIN，不返回密码；是否允许同人由 FinanceApprovalGuard 判定。
 */
package com.shop.b2b2c.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.dto.SettlementDTOs;
import com.shop.b2b2c.entity.MerchantAccount;
import com.shop.b2b2c.entity.MerchantCollectionStatusAudit;
import com.shop.b2b2c.entity.MerchantRechargeOrder;
import com.shop.b2b2c.entity.MerchantSettlementBatch;
import com.shop.b2b2c.entity.MerchantSettlementItem;
import com.shop.b2b2c.entity.PaymentChannelStatement;
import com.shop.b2b2c.entity.ReconciliationDifference;
import com.shop.b2b2c.entity.SysUser;
import com.shop.b2b2c.mapper.SysUserMapper;
import com.shop.b2b2c.service.FinanceApprovalGuard;
import com.shop.b2b2c.service.MerchantAccountService;
import com.shop.b2b2c.service.ReconciliationService;
import com.shop.b2b2c.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Tag(name = "平台服务费结算 (Admin Settlement)")
@RestController
@RequestMapping("/api/v1/admin/settlement")
@RequiredArgsConstructor
public class AdminSettlementController {

    private final MerchantAccountService accountService;
    private final SettlementService settlementService;
    private final ReconciliationService reconciliationService;
    private final SysUserMapper userMapper;
    private final FinanceApprovalGuard approvalGuard;

    @Operation(summary = "查询可选的财务复核人（排除当前操作人）")
    @GetMapping("/reviewers")
    public R<Map<String, Object>> reviewers() {
        StpUtil.checkRole("SUPERADMIN");
        Long currentUserId = StpUtil.getLoginIdAsLong();
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRoleCode, "SUPERADMIN")
                .eq(SysUser::getIsActive, true)
                .ne(SysUser::getId, currentUserId)
                .orderByAsc(SysUser::getId));
        List<Map<String, Object>> reviewers = new ArrayList<>();
        for (SysUser user : users) {
            // 只暴露复核选择所需字段，禁止返回密码散列或店铺绑定信息。
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", user.getId());
            item.put("username", user.getUsername());
            item.put("realName", user.getRealName());
            reviewers.add(item);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("dualApprovalRequired", approvalGuard.isDualApprovalRequired());
        result.put("reviewers", reviewers);
        return R.ok(result);
    }

    @Operation(summary = "查询待确认充值单")
    @GetMapping("/recharges/pending")
    public R<List<MerchantRechargeOrder>> pendingRecharges(@RequestParam(required = false) Long merchantId) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok(accountService.pendingRecharges(merchantId));
    }

    @Operation(summary = "人工确认充值到账")
    @PostMapping("/recharges/{rechargeNo}/confirm")
    public R<MerchantRechargeOrder> confirmRecharge(@PathVariable String rechargeNo,
                                                    @RequestBody(required = false) SettlementDTOs.ConfirmRequest request) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok("充值已确认", accountService.confirmRecharge(rechargeNo,
                request == null ? null : request.getConfirmedAmount(), StpUtil.getLoginIdAsLong(),
                request == null ? null : request.getConfirmedBy()));
    }

    @Operation(summary = "驳回充值单")
    @PostMapping("/recharges/{rechargeNo}/reject")
    public R<MerchantRechargeOrder> rejectRecharge(@PathVariable String rechargeNo,
                                                   @RequestBody(required = false) SettlementDTOs.RejectRequest request) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok("充值单已驳回", accountService.rejectRecharge(rechargeNo,
                request == null ? null : request.getReason(), StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "生成/重算月度结算草稿")
    @PostMapping("/merchants/{merchantId}/batches/generate")
    public R<MerchantSettlementBatch> generate(@PathVariable Long merchantId,
                                               @RequestParam int year, @RequestParam int month) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok("结算草稿已生成", settlementService.generateMonthly(merchantId, YearMonth.of(year, month),
                StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "确认结算批次")
    @PostMapping("/batches/{batchId}/confirm")
    public R<MerchantSettlementBatch> confirmBatch(
            @PathVariable Long batchId,
            @RequestBody(required = false) SettlementDTOs.BatchConfirmRequest request) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok("结算批次已确认", settlementService.confirm(batchId, StpUtil.getLoginIdAsLong(),
                request == null ? null : request.getConfirmedBy()));
    }

    @Operation(summary = "查询店铺结算批次")
    @GetMapping("/merchants/{merchantId}/batches")
    public R<List<MerchantSettlementBatch>> batches(@PathVariable Long merchantId,
                                                    @RequestParam(defaultValue = "24") int limit) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok(settlementService.listBatches(merchantId, limit));
    }

    @Operation(summary = "查询结算逐笔明细（跨店，按批次归属）")
    @GetMapping("/batches/{batchId}/items")
    public R<List<MerchantSettlementItem>> items(@PathVariable Long batchId,
                                                 @RequestParam(required = false) String orderSn,
                                                 @RequestParam(required = false) Integer goodsType,
                                                 @RequestParam(required = false) String refundNo,
                                                 @RequestParam(required = false) Long paymentEventId) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok(settlementService.searchItems(batchId, null, orderSn, goodsType, refundNo, paymentEventId));
    }

    @Operation(summary = "查询店铺服务费账户")
    @GetMapping("/merchants/{merchantId}/account")
    public R<MerchantAccount> account(@PathVariable Long merchantId) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok(accountService.view(merchantId));
    }

    @Operation(summary = "查询账期建议（不自动生效）")
    @GetMapping("/merchants/{merchantId}/policy/suggestion")
    public R<Map<String, Object>> policySuggestion(@PathVariable Long merchantId) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok(accountService.suggestGraceDays(merchantId));
    }

    @Operation(summary = "确认店铺信用额度与账期")
    @PostMapping("/merchants/{merchantId}/policy")
    public R<MerchantAccount> policy(@PathVariable Long merchantId,
                                     @RequestBody SettlementDTOs.PolicyRequest request) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok("账期策略已确认", accountService.configurePolicy(merchantId,
                request.getCreditLimit(), request.getGraceDays(), StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "查询收款状态审计")
    @GetMapping("/merchants/{merchantId}/collection-audits")
    public R<List<MerchantCollectionStatusAudit>> collectionAudits(@PathVariable Long merchantId,
                                                                   @RequestParam(defaultValue = "50") int limit) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok(accountService.auditOf(merchantId, limit));
    }

    @Operation(summary = "手动触发欠费停收巡检")
    @PostMapping("/arrears/evaluate")
    public R<Map<String, Object>> evaluateArrears(@RequestParam(defaultValue = "200") int limit) {
        StpUtil.checkRole("SUPERADMIN");
        int changed = accountService.evaluateDueArrears(limit);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("changed", changed);
        return R.ok(result);
    }

    @Operation(summary = "导入渠道对账流水")
    @PostMapping("/channel-statements/import")
    public R<Map<String, Object>> importStatements(
            @RequestBody SettlementDTOs.ChannelStatementImportRequest request) {
        StpUtil.checkRole("SUPERADMIN");
        List<PaymentChannelStatement> statements = new ArrayList<>();
        if (request != null && request.getStatements() != null) {
            for (SettlementDTOs.ChannelStatementItem item : request.getStatements()) {
                PaymentChannelStatement statement = new PaymentChannelStatement();
                statement.setChannel(item.getChannel());
                statement.setChannelTradeNo(item.getChannelTradeNo());
                statement.setOutTradeNo(item.getOutTradeNo());
                statement.setOrderSn(item.getOrderSn());
                statement.setMerchantId(item.getMerchantId());
                statement.setAmount(item.getAmount());
                statement.setTradeStatus(item.getTradeStatus());
                statement.setTradeTime(item.getTradeTime());
                statements.add(statement);
            }
        }
        return R.ok(reconciliationService.importStatements(statements, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "执行店铺对账")
    @PostMapping("/merchants/{merchantId}/reconcile")
    public R<Map<String, Object>> reconcile(
            @PathVariable Long merchantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        StpUtil.checkRole("SUPERADMIN");
        LocalDate start = from == null ? YearMonth.now().atDay(1) : from;
        LocalDate end = to == null ? YearMonth.now().plusMonths(1).atDay(1) : to;
        return R.ok(reconciliationService.reconcile(merchantId, start.atStartOfDay(), end.atStartOfDay()));
    }

    @Operation(summary = "查询对账差异队列")
    @GetMapping("/differences")
    public R<List<ReconciliationDifference>> differences(@RequestParam(required = false) Long merchantId,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "100") int limit) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok(reconciliationService.list(merchantId, status, limit));
    }

    @Operation(summary = "重试对账差异")
    @PostMapping("/differences/{id}/retry")
    public R<ReconciliationDifference> retryDifference(@PathVariable Long id) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok("已重试", reconciliationService.retry(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "人工处理对账差异")
    @PostMapping("/differences/{id}/resolve")
    public R<ReconciliationDifference> resolveDifference(@PathVariable Long id,
                                                         @RequestBody SettlementDTOs.ResolveRequest request) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok("差异已处理", reconciliationService.resolve(id, StpUtil.getLoginIdAsLong(),
                request.getNote(), request.isIgnore()));
    }

    @Operation(summary = "导出结算单 CSV")
    @GetMapping("/batches/{batchId}/export")
    public ResponseEntity<byte[]> exportSettlement(@PathVariable Long batchId,
                                                   @RequestParam(required = false) String orderSn,
                                                   @RequestParam(required = false) Integer goodsType,
                                                   @RequestParam(required = false) String refundNo,
                                                   @RequestParam(required = false) Long paymentEventId) {
        StpUtil.checkRole("SUPERADMIN");
        String csv = reconciliationService.settlementCsv(batchId, null, orderSn, goodsType, refundNo,
                paymentEventId);
        return csvResponse("settlement-" + batchId + ".csv", csv);
    }

    @Operation(summary = "导出服务费账本 CSV")
    @GetMapping("/merchants/{merchantId}/ledger/export")
    public ResponseEntity<byte[]> exportLedger(@PathVariable Long merchantId,
                                               @RequestParam(required = false)
                                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false)
                                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        StpUtil.checkRole("SUPERADMIN");
        LocalDate start = from == null ? YearMonth.now().atDay(1) : from;
        LocalDate end = to == null ? YearMonth.now().plusMonths(1).atDay(1) : to;
        String csv = reconciliationService.ledgerCsv(merchantId, start.atStartOfDay(), end.atStartOfDay());
        return csvResponse("ledger-" + merchantId + ".csv", csv);
    }

    private ResponseEntity<byte[]> csvResponse(String filename, String csv) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return ResponseEntity.ok().headers(headers).body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
