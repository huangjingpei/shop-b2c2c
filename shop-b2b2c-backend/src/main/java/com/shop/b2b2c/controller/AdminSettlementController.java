/*
 * 文件：AdminSettlementController.java
 * 用途：平台超管的充值确认/驳回、结算批次生成与确认、逐笔明细查询入口（T34）。
 * 维护注意：
 *  1. 路径位于 /api/v1/admin/**，由 SaTokenConfigure 统一要求 SUPERADMIN，控制器内仍显式校验一次。
 *  2. 充值确认以人工核对后的金额入账；确认接口幂等由充值单号+账本幂等键保证。
 *  3. 批次生成只生成/重算 DRAFT，确认后不可再改；跨店查询必须显式传 merchantId。
 */
package com.shop.b2b2c.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.dto.SettlementDTOs;
import com.shop.b2b2c.entity.MerchantRechargeOrder;
import com.shop.b2b2c.entity.MerchantSettlementBatch;
import com.shop.b2b2c.entity.MerchantSettlementItem;
import com.shop.b2b2c.service.MerchantAccountService;
import com.shop.b2b2c.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;

@Tag(name = "平台服务费结算 (Admin Settlement)")
@RestController
@RequestMapping("/api/v1/admin/settlement")
@RequiredArgsConstructor
public class AdminSettlementController {

    private final MerchantAccountService accountService;
    private final SettlementService settlementService;

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
                request == null ? null : request.getConfirmedAmount(), StpUtil.getLoginIdAsLong()));
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
        return R.ok("结算草稿已生成", settlementService.generateMonthly(merchantId, YearMonth.of(year, month)));
    }

    @Operation(summary = "确认结算批次")
    @PostMapping("/batches/{batchId}/confirm")
    public R<MerchantSettlementBatch> confirmBatch(@PathVariable Long batchId) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok("结算批次已确认", settlementService.confirm(batchId, StpUtil.getLoginIdAsLong()));
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
}
