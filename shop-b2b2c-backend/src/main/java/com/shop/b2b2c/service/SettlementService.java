/*
 * 文件：SettlementService.java
 * 用途：服务费结算批次与逐笔明细（T34）：按月生成、草稿重算、确认锁定、明细查询。
 * 维护注意：
 *  1. 结算周期按自然月（账期 7/21/30 天配置已在账户侧保留，T35 接入止收后按账期出账）。
 *  2. 订单明细按订单创建时间归属账期；退款冲正按退款成功时间归属账期，形成独立红字行。
 *  3. DRAFT 批次可重算覆盖，CONFIRMED 后禁止重算；批次合计必须由明细行汇总，不得手工写入。
 *  4. 明细金额来自订单级快照；旧订单无快照时以 commission_amount 兜底，并在文档标注为历史订单口径。
 *  5. 财务基线 18：确认接口的复核人由 FinanceApprovalGuard 判定，严格缺复核人 428、同人 403；
 *     宽松模式未传按操作人处理并落 SAME_OPERATOR；prepared_by 仍独立记录制单人。
 *  6. O6 结算 HOLD：店铺冻结（merchant_store.status=3）期间禁止确认批次（409），
 *     草稿可继续生成但不得进入资金确认；关店前置条件由 MerchantStoreStatusService 校验。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantSettlementBatch;
import com.shop.b2b2c.entity.MerchantSettlementItem;
import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeRefund;
import com.shop.b2b2c.mapper.MerchantAccountLedgerMapper;
import com.shop.b2b2c.mapper.MerchantSettlementBatchMapper;
import com.shop.b2b2c.mapper.MerchantSettlementItemMapper;
import com.shop.b2b2c.mapper.PaymentEventMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SettlementService {

    private final MerchantSettlementBatchMapper batchMapper;
    private final MerchantSettlementItemMapper itemMapper;
    private final MerchantAccountLedgerMapper ledgerMapper;
    private final TradeOrderMapper orderMapper;
    private final TradeRefundMapper refundMapper;
    private final PaymentEventMapper paymentEventMapper;
    private final ServiceFeeSnapshotService snapshotService;
    private final FinanceApprovalGuard approvalGuard;
    private final com.shop.b2b2c.mapper.MerchantStoreMapper storeMapper;

    /**
     * 生成或重算某店某月结算草稿。已确认批次直接拒绝，避免改写历史账单。
     */
    @Transactional
    public MerchantSettlementBatch generateMonthly(Long merchantId, YearMonth period, Long operatorId) {
        LocalDate periodStart = period.atDay(1);
        LocalDate periodEnd = period.plusMonths(1).atDay(1);
        MerchantSettlementBatch batch = batchMapper.findByPeriod(merchantId, periodStart, periodEnd).orElse(null);
        if (batch != null && "CONFIRMED".equals(batch.getStatus())) {
            throw new BusinessException(409, "已确认的结算批次不能重算");
        }
        if (batch == null) {
            batch = new MerchantSettlementBatch();
            batch.setBatchNo("STL" + merchantId + "-" + period.format(DateTimeFormatter.ofPattern("yyyyMM")));
            batch.setMerchantId(merchantId);
            batch.setPeriodStart(periodStart);
            batch.setPeriodEnd(periodEnd);
            batch.setSettlementType("SERVICE_FEE");
            batch.setStatus("DRAFT");
            batch.setPreparedBy(operatorId);
            batchMapper.insert(batch);
        } else {
            itemMapper.deleteByBatchId(batch.getId());
        }

        LocalDateTime from = periodStart.atStartOfDay();
        LocalDateTime to = periodEnd.atStartOfDay();

        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal refundBase = BigDecimal.ZERO;
        BigDecimal feeTotal = BigDecimal.ZERO;
        BigDecimal adjustment = BigDecimal.ZERO;

        List<TradeOrder> orders = orderMapper.findFeeBearingByMerchantAndPeriod(merchantId, from, to);
        for (TradeOrder order : orders) {
            BigDecimal base = order.getFeeBaseAmount() != null ? order.getFeeBaseAmount() : order.getTotalAmount();
            BigDecimal fee = snapshotService.resolveFeeAmount(order);
            BigDecimal refunded = order.getFeeRefundedAmount() == null ? BigDecimal.ZERO : order.getFeeRefundedAmount();
            PaymentEvent event = paymentEventMapper.findFirstSuccessByOrderSn(order.getOrderSn());

            MerchantSettlementItem item = new MerchantSettlementItem();
            item.setBatchId(batch.getId());
            item.setMerchantId(merchantId);
            item.setItemType("ORDER_FEE");
            item.setOrderId(order.getId());
            item.setOrderSn(order.getOrderSn());
            item.setGoodsType(order.getGoodsType() == null ? 1 : order.getGoodsType());
            item.setPaymentEventId(event == null ? null : event.getId());
            item.setRefundId(0L);
            item.setBillableBase(base.setScale(2, RoundingMode.HALF_UP));
            item.setFeeRateBps(snapshotService.resolveRateBps(order));
            item.setFeeAmount(fee);
            item.setFeeRefundedAmount(refunded);
            item.setNetFee(fee.subtract(refunded));
            itemMapper.insert(item);

            gross = gross.add(item.getBillableBase());
            feeTotal = feeTotal.add(fee);
        }

        List<TradeRefund> refunds = refundMapper.findSuccessfulOrderRefundsByPeriod(merchantId, from, to);
        for (TradeRefund refund : refunds) {
            TradeOrder order = orderMapper.findById(refund.getOrderId()).orElse(null);
            if (order == null) {
                continue;
            }
            BigDecimal red = refund.getFeeRefundAmount().negate().setScale(2, RoundingMode.HALF_UP);
            MerchantSettlementItem item = new MerchantSettlementItem();
            item.setBatchId(batch.getId());
            item.setMerchantId(merchantId);
            item.setItemType("REFUND_REVERSAL");
            item.setOrderId(order.getId());
            item.setOrderSn(order.getOrderSn());
            item.setGoodsType(order.getGoodsType() == null ? 1 : order.getGoodsType());
            item.setPaymentEventId(null);
            item.setRefundId(refund.getId());
            item.setRefundNo(refund.getRefundNo());
            item.setBillableBase(refund.getRefundAmount() == null
                    ? BigDecimal.ZERO : refund.getRefundAmount().setScale(2, RoundingMode.HALF_UP));
            item.setFeeRateBps(snapshotService.resolveRateBps(order));
            item.setFeeAmount(red);
            item.setFeeRefundedAmount(refund.getFeeRefundAmount().setScale(2, RoundingMode.HALF_UP));
            item.setNetFee(red);
            itemMapper.insert(item);

            refundBase = refundBase.add(item.getBillableBase());
            adjustment = adjustment.add(red);
        }

        BigDecimal paid = ledgerMapper.sumChargedByPeriod(merchantId, from, to);
        BigDecimal arrears = ledgerMapper.sumArrearsByPeriod(merchantId, from, to);

        batch.setGrossAmount(scale(gross));
        batch.setRefundBaseAmount(scale(refundBase));
        batch.setFeeAmount(scale(feeTotal));
        batch.setAdjustmentAmount(scale(adjustment));
        batch.setNetFeeAmount(scale(feeTotal.add(adjustment)));
        batch.setPaidAmount(scale(paid));
        batch.setArrearsAmount(scale(arrears));
        batch.setStatus("DRAFT");
        batch.setPreparedBy(operatorId);
        batch.setVersion((batch.getVersion() == null ? 0 : batch.getVersion()) + 1);
        batchMapper.updateById(batch);
        return batch;
    }

    @Transactional
    public MerchantSettlementBatch confirm(Long batchId, Long operatorId, Long reviewerId) {
        MerchantSettlementBatch batch = batchMapper.lockById(batchId);
        if (batch == null) {
            throw new BusinessException(404, "结算批次不存在");
        }
        if (!"DRAFT".equals(batch.getStatus())) {
            throw new BusinessException(409, "结算批次已确认");
        }
        // O6 结算 HOLD：店铺冻结期间暂停批次确认，解封后恢复；草稿生成不受限。
        com.shop.b2b2c.entity.MerchantStore store = storeMapper.selectById(batch.getMerchantId());
        if (store != null && Integer.valueOf(3).equals(store.getStatus())) {
            throw new BusinessException(409, "店铺已冻结，结算批次暂停确认（HOLD），解封后恢复");
        }
        // 财务基线 18：双人复核开关打开时必须由操作人显式选择不同复核人；缺复核人返回 428，同人 403。
        // 开发/试点关闭时允许同人并落 SAME_OPERATOR；prepared_by 仍独立记录制单人。
        String approvalMode = approvalGuard.verify("结算确认", operatorId, reviewerId);
        Long confirmedBy = approvalGuard.resolveReviewer(operatorId, reviewerId);
        batch.setStatus("CONFIRMED");
        batch.setConfirmedBy(confirmedBy);
        batch.setApprovalMode(approvalMode);
        batch.setConfirmedAt(LocalDateTime.now());
        batch.setVersion((batch.getVersion() == null ? 0 : batch.getVersion()) + 1);
        batchMapper.updateById(batch);
        return batch;
    }

    public List<MerchantSettlementBatch> listBatches(Long merchantId, int limit) {
        return batchMapper.findByMerchant(merchantId, Math.min(Math.max(limit, 1), 100));
    }

    public MerchantSettlementBatch requireBatch(Long batchId, Long merchantId) {
        MerchantSettlementBatch batch = batchMapper.selectById(batchId);
        if (batch == null || (merchantId != null && !merchantId.equals(batch.getMerchantId()))) {
            throw new BusinessException(404, "结算批次不存在");
        }
        return batch;
    }

    public List<MerchantSettlementItem> searchItems(Long batchId, Long merchantId, String orderSn,
                                                    Integer goodsType, String refundNo, Long paymentEventId) {
        requireBatch(batchId, merchantId);
        return itemMapper.search(batchId, orderSn, goodsType, refundNo, paymentEventId);
    }

    private BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
