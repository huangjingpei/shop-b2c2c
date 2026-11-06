/*
 * 文件：ReconciliationService.java
 * 用途：T35 渠道流水导入、订单/支付事件/账本/结算单对账、差异队列处理与账单 CSV 导出。
 * 维护注意：
 *  1. 对账只读业务数据并写差异，不修改订单、支付事件、账本或已确认结算单；差异不得自动平衡。
 *  2. 差异键稳定唯一，重复对账不重复入队；已 RESOLVED/IGNORED 的差异不再被自动改写。
 *  3. 渠道流水缺失只标记差异，不据此扣款或补记收入；重复付款沿用 T33 补偿流程。
 *  4. 导出使用管理员/本店归属校验后的数据，CSV 转义逗号、引号与换行，避免公式注入。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantAccountLedger;
import com.shop.b2b2c.entity.MerchantSettlementBatch;
import com.shop.b2b2c.entity.MerchantSettlementItem;
import com.shop.b2b2c.entity.PaymentChannelStatement;
import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.entity.ReconciliationDifference;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.mapper.MerchantAccountLedgerMapper;
import com.shop.b2b2c.mapper.MerchantSettlementBatchMapper;
import com.shop.b2b2c.mapper.MerchantSettlementItemMapper;
import com.shop.b2b2c.mapper.PaymentChannelStatementMapper;
import com.shop.b2b2c.mapper.PaymentEventMapper;
import com.shop.b2b2c.mapper.ReconciliationDifferenceMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final PaymentChannelStatementMapper statementMapper;
    private final PaymentEventMapper eventMapper;
    private final TradeOrderMapper orderMapper;
    private final MerchantAccountLedgerMapper ledgerMapper;
    private final MerchantSettlementBatchMapper batchMapper;
    private final MerchantSettlementItemMapper itemMapper;
    private final ReconciliationDifferenceMapper differenceMapper;
    private final ServiceFeeSnapshotService snapshotService;

    public static final String CHANNEL_WITHOUT_EVENT = "CHANNEL_WITHOUT_EVENT";
    public static final String EVENT_WITHOUT_STATEMENT = "EVENT_WITHOUT_STATEMENT";
    public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    public static final String EVENT_STATUS_MISMATCH = "EVENT_STATUS_MISMATCH";
    public static final String ORDER_MISMATCH = "ORDER_MISMATCH";
    public static final String ORDER_MISSING = "ORDER_MISSING";
    public static final String FEE_LEDGER_MISSING = "FEE_LEDGER_MISSING";
    public static final String DUPLICATE_PAYMENT = "DUPLICATE_PAYMENT";
    public static final String SETTLEMENT_ITEM_MISMATCH = "SETTLEMENT_ITEM_MISMATCH";

    /** 财务基线：同一差异自动重试上限；达到上限仍不一致必须转人工，不得无限重试。 */
    public static final int MAX_RETRY_COUNT = 3;

    /**
     * 导入渠道流水。重复 (channel, channelTradeNo) 跳过；不做金额自动纠正。
     */
    @Transactional
    public Map<String, Object> importStatements(List<PaymentChannelStatement> statements, Long operatorId) {
        int imported = 0;
        int skipped = 0;
        if (statements == null) {
            statements = new ArrayList<>();
        }
        for (PaymentChannelStatement source : statements) {
            if (source == null || source.getChannel() == null || source.getChannel().isBlank()
                    || source.getChannelTradeNo() == null || source.getChannelTradeNo().isBlank()) {
                skipped++;
                continue;
            }
            if (source.getMerchantId() == null || source.getAmount() == null
                    || source.getAmount().signum() <= 0) {
                throw new BusinessException(400, "渠道流水必须包含商户、流水号与正数金额");
            }
            if (statementMapper.findByChannelAndTradeNo(source.getChannel(), source.getChannelTradeNo()).isPresent()) {
                skipped++;
                continue;
            }
            PaymentChannelStatement statement = new PaymentChannelStatement();
            statement.setChannel(source.getChannel().trim().toUpperCase());
            statement.setChannelTradeNo(source.getChannelTradeNo().trim());
            statement.setOutTradeNo(source.getOutTradeNo());
            statement.setMerchantId(source.getMerchantId());
            statement.setAmount(source.getAmount().setScale(2, RoundingMode.HALF_UP));
            statement.setTradeStatus(source.getTradeStatus() == null ? "SUCCESS" : source.getTradeStatus());
            statement.setTradeTime(source.getTradeTime() == null ? LocalDateTime.now() : source.getTradeTime());
            statement.setImportedBy(operatorId);
            String orderSn = source.getOrderSn();
            if ((orderSn == null || orderSn.isBlank()) && source.getOutTradeNo() != null) {
                orderSn = orderMapper.findByOrderSn(source.getOutTradeNo()).map(TradeOrder::getOrderSn).orElse(null);
            }
            statement.setOrderSn(orderSn);
            try {
                statementMapper.insert(statement);
                imported++;
            } catch (DuplicateKeyException e) {
                skipped++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("skipped", skipped);
        return result;
    }

    /**
     * 对账：渠道流水 ↔ 支付事件 ↔ 订单 ↔ 服务费账本 ↔ 已确认结算单；差异只入队。
     */
    @Transactional
    public Map<String, Object> reconcile(Long merchantId, LocalDateTime from, LocalDateTime to) {
        // added 记录本次命中的差异条数（已存在未关闭记录只刷新，不重复入队由 differenceKey 唯一约束兜底）。
        int added = 0;
        List<PaymentChannelStatement> statements = statementMapper.findByMerchantAndPeriod(merchantId, from, to);
        for (PaymentChannelStatement statement : statements) {
            if (!"SUCCESS".equals(statement.getTradeStatus())) {
                continue;
            }
            PaymentEvent event = eventMapper.findByChannelAndTradeNo(statement.getChannel(),
                    statement.getChannelTradeNo());
            if (event == null) {
                addDifference("CH|" + statement.getChannel() + "|" + statement.getChannelTradeNo(), merchantId,
                        statement.getChannel(), CHANNEL_WITHOUT_EVENT, "STATEMENT", statement.getId(),
                        statement.getOrderSn(), statement.getChannelTradeNo(), statement.getAmount(), null,
                        "渠道流水无对应支付事件");
                added++;
                continue;
            }
            if (!"SUCCESS".equals(event.getEventStatus())) {
                addDifference("EV|" + event.getId() + "|STATUS", merchantId, statement.getChannel(),
                        EVENT_STATUS_MISMATCH, "EVENT", event.getId(), event.getOrderSn(),
                        statement.getChannelTradeNo(), statement.getAmount(), event.getPayAmount(),
                        "支付事件状态不是 SUCCESS：" + event.getEventStatus());
                added++;
            } else if (!amountEquals(statement.getAmount(), event.getPayAmount())) {
                addDifference("CH|" + statement.getChannel() + "|" + statement.getChannelTradeNo() + "|AMT",
                        merchantId, statement.getChannel(), AMOUNT_MISMATCH, "STATEMENT", statement.getId(),
                        statement.getOrderSn(), statement.getChannelTradeNo(), statement.getAmount(),
                        event.getPayAmount(), "渠道金额与支付事件金额不一致");
                added++;
            } else if (statement.getOrderSn() != null && event.getOrderSn() != null
                    && !statement.getOrderSn().equals(event.getOrderSn())) {
                addDifference("CH|" + statement.getChannel() + "|" + statement.getChannelTradeNo() + "|ORDER",
                        merchantId, statement.getChannel(), ORDER_MISMATCH, "STATEMENT", statement.getId(),
                        statement.getOrderSn(), statement.getChannelTradeNo(), null, null,
                        "渠道流水订单号与支付事件订单号不一致");
                added++;
            }
        }

        List<PaymentEvent> events = eventMapper.findByMerchantAndPeriod(merchantId, from, to);
        for (PaymentEvent event : events) {
            TradeOrder order = orderMapper.findByOrderSn(event.getOrderSn()).orElse(null);
            if (DUPLICATE_PAYMENT.equals(event.getEventStatus())) {
                addDifference("DUP|" + event.getId(), merchantId, event.getChannel(), DUPLICATE_PAYMENT, "EVENT",
                        event.getId(), event.getOrderSn(), event.getPayTradeNo(), event.getPayAmount(), null,
                        "重复付款，走 T33 补偿/退款流程");
                added++;
                continue;
            }
            if (!"SUCCESS".equals(event.getEventStatus())) {
                continue;
            }
            if (order == null) {
                addDifference("EV|" + event.getId() + "|ORDER", merchantId, event.getChannel(), ORDER_MISSING,
                        "EVENT", event.getId(), event.getOrderSn(), event.getPayTradeNo(), event.getPayAmount(),
                        null, "支付事件找不到对应订单");
                added++;
                continue;
            }
            if (!amountEquals(order.getPayAmount(), event.getPayAmount())) {
                addDifference("EV|" + event.getId() + "|AMT", merchantId, event.getChannel(), AMOUNT_MISMATCH,
                        "ORDER", order.getId(), order.getOrderSn(), event.getPayTradeNo(), order.getPayAmount(),
                        event.getPayAmount(), "订单应付金额与支付事件实收金额不一致");
                added++;
            }
            BigDecimal fee = snapshotService.resolveFeeAmount(order);
            if (fee.signum() > 0 && ledgerMapper.findByIdempotencyKey("ORDER_FEE:" + order.getOrderSn()).isEmpty()) {
                addDifference("FEE|" + order.getOrderSn(), merchantId, event.getChannel(), FEE_LEDGER_MISSING,
                        "ORDER", order.getId(), order.getOrderSn(), event.getPayTradeNo(), fee, null,
                        "已支付订单缺少服务费扣费账本");
                added++;
            }
            if (statementMapper.findByChannelAndTradeNo(event.getChannel(), event.getPayTradeNo()).isEmpty()
                    && statementMapper.countByMerchantChannelPeriod(merchantId, event.getChannel(), from, to) > 0) {
                addDifference("EV|" + event.getId() + "|ST", merchantId, event.getChannel(),
                        EVENT_WITHOUT_STATEMENT, "EVENT", event.getId(), event.getOrderSn(),
                        event.getPayTradeNo(), event.getPayAmount(), null, "已导入渠道流水但缺少本笔流水");
                added++;
            }
        }

        List<MerchantSettlementBatch> batches = batchMapper.findConfirmedByPeriod(merchantId,
                from.toLocalDate(), to.toLocalDate());
        for (MerchantSettlementBatch batch : batches) {
            List<MerchantSettlementItem> items = itemMapper.findByBatchId(batch.getId());
            BigDecimal feeTotal = BigDecimal.ZERO;
            BigDecimal adjustment = BigDecimal.ZERO;
            BigDecimal net = BigDecimal.ZERO;
            for (MerchantSettlementItem item : items) {
                feeTotal = feeTotal.add(item.getFeeAmount() == null ? BigDecimal.ZERO : item.getFeeAmount());
                if ("REFUND_REVERSAL".equals(item.getItemType())) {
                    adjustment = adjustment.add(item.getFeeAmount() == null ? BigDecimal.ZERO : item.getFeeAmount());
                }
                net = net.add(item.getNetFee() == null ? BigDecimal.ZERO : item.getNetFee());
            }
            if (feeTotal.compareTo(batch.getFeeAmount() == null ? BigDecimal.ZERO : batch.getFeeAmount()) != 0
                    || adjustment.compareTo(batch.getAdjustmentAmount() == null
                            ? BigDecimal.ZERO : batch.getAdjustmentAmount()) != 0
                    || net.compareTo(batch.getNetFeeAmount() == null ? BigDecimal.ZERO : batch.getNetFeeAmount()) != 0) {
                addDifference("STL|" + batch.getId(), merchantId, null, SETTLEMENT_ITEM_MISMATCH, "SETTLEMENT",
                        batch.getId(), null, null, batch.getNetFeeAmount(), net, "结算批次合计与逐笔明细不一致");
                added++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("added", added);
        result.put("statements", statements.size());
        result.put("events", events.size());
        result.put("batches", batches.size());
        return result;
    }

    public List<ReconciliationDifference> list(Long merchantId, String status, int limit) {
        return differenceMapper.find(merchantId, status, limit);
    }

    /**
     * 重试差异：仅做只读复核；能从现有证据确认一致时置 RESOLVED，否则保持 RETRY 等待人工。
     * 重试次数达到 {@link #MAX_RETRY_COUNT} 仍不一致时转 MANUAL，后续重试直接拒绝，只能人工关闭。
     */
    @Transactional
    public ReconciliationDifference retry(Long id, Long operatorId) {
        ReconciliationDifference difference = require(id);
        if ("MANUAL".equals(difference.getStatus())) {
            throw new BusinessException(409, "该差异已超过重试上限转人工，请人工处理");
        }
        int retryCount = (difference.getRetryCount() == null ? 0 : difference.getRetryCount()) + 1;
        difference.setRetryCount(retryCount);
        difference.setHandledBy(operatorId);
        difference.setHandledAt(LocalDateTime.now());
        boolean resolved = false;
        if (CHANNEL_WITHOUT_EVENT.equals(difference.getDiffType()) && difference.getChannel() != null
                && difference.getChannelTradeNo() != null) {
            PaymentEvent event = eventMapper.findByChannelAndTradeNo(difference.getChannel(),
                    difference.getChannelTradeNo());
            resolved = event != null && "SUCCESS".equals(event.getEventStatus())
                    && amountEquals(difference.getExpectedAmount(), event.getPayAmount());
        } else if (EVENT_WITHOUT_STATEMENT.equals(difference.getDiffType()) && difference.getChannel() != null
                && difference.getChannelTradeNo() != null) {
            PaymentChannelStatement statement = statementMapper.findByChannelAndTradeNo(
                    difference.getChannel(), difference.getChannelTradeNo()).orElse(null);
            resolved = statement != null && amountEquals(difference.getExpectedAmount(), statement.getAmount());
        } else if (FEE_LEDGER_MISSING.equals(difference.getDiffType()) && difference.getOrderSn() != null) {
            resolved = ledgerMapper.findByIdempotencyKey("ORDER_FEE:" + difference.getOrderSn()).isPresent();
        } else if (ORDER_MISSING.equals(difference.getDiffType()) && difference.getOrderSn() != null) {
            resolved = orderMapper.findByOrderSn(difference.getOrderSn()).isPresent();
        }
        if (resolved) {
            difference.setStatus("RESOLVED");
            difference.setHandleNote("复核一致，自动关闭");
        } else if (retryCount >= MAX_RETRY_COUNT) {
            difference.setStatus("MANUAL");
            difference.setHandleNote("重试" + retryCount + "次仍未一致，已转人工处理");
        } else {
            difference.setStatus("RETRY");
            difference.setHandleNote("已发起复核（第" + retryCount + "/" + MAX_RETRY_COUNT + "次）");
        }
        differenceMapper.updateById(difference);
        return difference;
    }

    /**
     * 人工关闭差异；必须填写说明。IGNORE 仅用于确认无需处理的场景。
     */
    @Transactional
    public ReconciliationDifference resolve(Long id, Long operatorId, String note, boolean ignore) {
        if (note == null || note.isBlank()) {
            throw new BusinessException(400, "处理差异必须填写说明");
        }
        ReconciliationDifference difference = require(id);
        difference.setStatus(ignore ? "IGNORED" : "RESOLVED");
        difference.setHandledBy(operatorId);
        difference.setHandledAt(LocalDateTime.now());
        difference.setHandleNote(note.trim());
        differenceMapper.updateById(difference);
        return difference;
    }

    public String settlementCsv(Long batchId, Long merchantId, String orderSn, Integer goodsType,
                                String refundNo, Long paymentEventId) {
        MerchantSettlementBatch batch = batchMapper.selectById(batchId);
        if (batch == null || (merchantId != null && !merchantId.equals(batch.getMerchantId()))) {
            throw new BusinessException(404, "结算批次不存在");
        }
        // merchantId 仅用于归属校验，跨店拒绝在上方完成；明细过滤条件与是否跨店无关。
        List<MerchantSettlementItem> items = itemMapper.search(batchId, orderSn, goodsType, refundNo,
                paymentEventId);
        StringBuilder csv = new StringBuilder();
        csv.append("item_type,order_sn,goods_type,payment_event_id,refund_no,billable_base,fee_rate_bps,fee_amount,fee_refunded_amount,net_fee,created_at\n");
        for (MerchantSettlementItem item : items) {
            csv.append(cell(item.getItemType())).append(',')
                    .append(cell(item.getOrderSn())).append(',')
                    .append(item.getGoodsType() == null ? "" : item.getGoodsType()).append(',')
                    .append(item.getPaymentEventId() == null ? "" : item.getPaymentEventId()).append(',')
                    .append(cell(item.getRefundNo())).append(',')
                    .append(item.getBillableBase()).append(',')
                    .append(item.getFeeRateBps()).append(',')
                    .append(item.getFeeAmount()).append(',')
                    .append(item.getFeeRefundedAmount()).append(',')
                    .append(item.getNetFee()).append(',')
                    .append(item.getCreatedAt() == null ? "" : item.getCreatedAt())
                    .append('\n');
        }
        return csv.toString();
    }

    public String ledgerCsv(Long merchantId, LocalDateTime from, LocalDateTime to) {
        List<MerchantAccountLedger> entries = ledgerMapper.findByMerchantAndPeriod(merchantId, from, to);
        StringBuilder csv = new StringBuilder();
        csv.append("created_at,entry_type,direction,amount,balance_after,arrears_after,biz_type,biz_id,order_sn,refund_no,remark\n");
        for (MerchantAccountLedger entry : entries) {
            csv.append(entry.getCreatedAt() == null ? "" : entry.getCreatedAt()).append(',')
                    .append(cell(entry.getEntryType())).append(',')
                    .append(cell(entry.getDirection())).append(',')
                    .append(entry.getAmount()).append(',')
                    .append(entry.getBalanceAfter()).append(',')
                    .append(entry.getArrearsAfter()).append(',')
                    .append(cell(entry.getBizType())).append(',')
                    .append(entry.getBizId() == null ? "" : entry.getBizId()).append(',')
                    .append(cell(entry.getOrderSn())).append(',')
                    .append(cell(entry.getRefundNo())).append(',')
                    .append(cell(entry.getRemark())).append('\n');
        }
        return csv.toString();
    }

    private ReconciliationDifference require(Long id) {
        ReconciliationDifference difference = differenceMapper.selectById(id);
        if (difference == null) {
            throw new BusinessException(404, "对账差异不存在");
        }
        return difference;
    }

    /**
     * 差异入队：键已存在时不覆盖已关闭记录；未关闭记录只刷新金额与说明。
     */
    private void addDifference(String key, Long merchantId, String channel, String diffType, String bizType,
                               Long bizId, String orderSn, String tradeNo, BigDecimal expected,
                               BigDecimal actual, String detail) {
        ReconciliationDifference existing = differenceMapper.findByKey(key).orElse(null);
        if (existing != null) {
            if ("RESOLVED".equals(existing.getStatus()) || "IGNORED".equals(existing.getStatus())) {
                return;
            }
            existing.setExpectedAmount(expected);
            existing.setActualAmount(actual);
            existing.setDetail(detail);
            differenceMapper.updateById(existing);
            return;
        }
        ReconciliationDifference difference = new ReconciliationDifference();
        difference.setDifferenceKey(key);
        difference.setMerchantId(merchantId);
        difference.setChannel(channel);
        difference.setDiffType(diffType);
        difference.setBizType(bizType);
        difference.setBizId(bizId);
        difference.setOrderSn(orderSn);
        difference.setChannelTradeNo(tradeNo);
        difference.setExpectedAmount(expected);
        difference.setActualAmount(actual);
        difference.setDetail(detail);
        difference.setStatus("DISPUTED");
        try {
            differenceMapper.insert(difference);
        } catch (DuplicateKeyException ignore) {
            // 并发对账已入队
        }
    }

    private boolean amountEquals(BigDecimal left, BigDecimal right) {
        BigDecimal a = left == null ? BigDecimal.ZERO : left;
        BigDecimal b = right == null ? BigDecimal.ZERO : right;
        return a.compareTo(b) == 0;
    }

    private String cell(String value) {
        if (value == null) {
            return "";
        }
        String safe = value.replace("\"", "\"\"");
        if (safe.startsWith("=") || safe.startsWith("+") || safe.startsWith("-") || safe.startsWith("@")) {
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe + "\"";
        }
        return safe;
    }
}
