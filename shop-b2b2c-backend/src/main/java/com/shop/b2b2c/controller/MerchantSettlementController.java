/*
 * 文件：MerchantSettlementController.java
 * 用途：商家服务费账户、账本、充值申请、结算批次查询与账单 CSV 导出入口（T34/T35）。
 * 维护注意：
 *  1. 路径位于 /api/v1/merchant/**，由 SaTokenConfigure 统一拦截登录与商家角色。
 *  2. 充值申请只允许店长/超管；普通员工只能查看账户、账本与结算单。
 *  3. 当前商户一律取会话店铺，不接受客户端传 merchantId；跨店查询只走管理员接口。
 *  4. 批次明细/导出查询复用结算服务与对账服务做归属校验，禁止用 batchId 直接跨店读取。
 *  5. T35 导出复用 ReconciliationService 的 CSV 方法，只读本店数据；CSV 转义与公式注入防护在服务层统一处理。
 */
package com.shop.b2b2c.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.dto.SettlementDTOs;
import com.shop.b2b2c.entity.MerchantAccount;
import com.shop.b2b2c.entity.MerchantAccountLedger;
import com.shop.b2b2c.entity.MerchantRechargeOrder;
import com.shop.b2b2c.entity.MerchantSettlementBatch;
import com.shop.b2b2c.entity.MerchantSettlementItem;
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
import java.util.List;

@Tag(name = "商家服务费与结算 (Merchant Settlement)")
@RestController
@RequestMapping("/api/v1/merchant/settlement")
@RequiredArgsConstructor
public class MerchantSettlementController {

    private final MerchantAccountService accountService;
    private final SettlementService settlementService;
    private final ReconciliationService reconciliationService;

    @Operation(summary = "查询服务费账户余额与欠费")
    @GetMapping("/account")
    public R<MerchantAccount> account() {
        return R.ok(accountService.view(requireMerchantId()));
    }

    @Operation(summary = "查询服务费账本")
    @GetMapping("/account/ledger")
    public R<List<MerchantAccountLedger>> ledger(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate start = from == null ? YearMonth.now().atDay(1) : from;
        LocalDate end = to == null ? YearMonth.now().plusMonths(1).atDay(1) : to;
        return R.ok(accountService.ledgerOf(requireMerchantId(), start.atStartOfDay(), end.atStartOfDay()));
    }

    @Operation(summary = "提交线下转账充值申请（仅店长/超管）")
    @PostMapping("/recharges")
    public R<MerchantRechargeOrder> createRecharge(@RequestBody SettlementDTOs.RechargeRequest request) {
        requireFinanceRole();
        return R.ok("充值申请已提交", accountService.createRecharge(requireMerchantId(), request.getAmount(),
                request.getVoucherNo(), request.getPayerName(), request.getVoucherProofUrl()));
    }

    @Operation(summary = "查询充值单")
    @GetMapping("/recharges")
    public R<List<MerchantRechargeOrder>> recharges() {
        return R.ok(accountService.rechargesOf(requireMerchantId()));
    }

    @Operation(summary = "查询结算批次")
    @GetMapping("/batches")
    public R<List<MerchantSettlementBatch>> batches(@RequestParam(defaultValue = "24") int limit) {
        return R.ok(settlementService.listBatches(requireMerchantId(), limit));
    }

    @Operation(summary = "查询结算批次详情")
    @GetMapping("/batches/{batchId}")
    public R<MerchantSettlementBatch> batch(@PathVariable Long batchId) {
        return R.ok(settlementService.requireBatch(batchId, requireMerchantId()));
    }

    @Operation(summary = "查询结算逐笔明细")
    @GetMapping("/batches/{batchId}/items")
    public R<List<MerchantSettlementItem>> items(@PathVariable Long batchId,
                                                 @RequestParam(required = false) String orderSn,
                                                 @RequestParam(required = false) Integer goodsType,
                                                 @RequestParam(required = false) String refundNo,
                                                 @RequestParam(required = false) Long paymentEventId) {
        return R.ok(settlementService.searchItems(batchId, requireMerchantId(), orderSn, goodsType,
                refundNo, paymentEventId));
    }

    @Operation(summary = "导出本店结算单 CSV")
    @GetMapping("/batches/{batchId}/export")
    public ResponseEntity<byte[]> exportBatch(@PathVariable Long batchId,
                                              @RequestParam(required = false) String orderSn,
                                              @RequestParam(required = false) Integer goodsType,
                                              @RequestParam(required = false) String refundNo,
                                              @RequestParam(required = false) Long paymentEventId) {
        String csv = reconciliationService.settlementCsv(batchId, requireMerchantId(), orderSn, goodsType,
                refundNo, paymentEventId);
        return csvResponse("settlement-" + batchId + ".csv", csv);
    }

    @Operation(summary = "导出本店服务费账本 CSV")
    @GetMapping("/account/ledger/export")
    public ResponseEntity<byte[]> exportLedger(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long merchantId = requireMerchantId();
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

    private Long requireMerchantId() {
        return SecurityUtils.requireCurrentMerchantId();
    }

    private void requireFinanceRole() {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
    }
}
