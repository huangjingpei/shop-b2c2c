/*
 * 文件：ReconciliationService.java
 * 用途：T35/T+1 渠道流水导入、订单/支付事件/退款/账本/结算/平台代收对账、长短款分类、差异队列处理与 CSV 导出。
 * 维护注意：
 *  1. 对账只读业务数据并写差异，不修改订单、支付事件、退款、账本或已确认结算单；差异不得自动平衡。
 *  2. 差异键稳定唯一，重复对账不重复入队；已 RESOLVED/IGNORED 的差异不再被自动改写。
 *  3. 渠道流水缺失只标记差异，不据此扣款或补记收入；重复付款沿用 T33 补偿流程。
 *  4. 金额统一 BigDecimal、scale=2，比较用 compareTo；分类由 ReconciliationClassifier 统一计算。
 *  5. T+1 以 Asia/Shanghai 业务日切日：渠道 trade_time、支付事件/平台代收 created_at、退款 success_at；
 *     两侧存在但日期不同记 TIME_CROSS_DAY，不按单边账处理。success_at 不可变，禁止改用 updated_at。
 *  6. 退款侧与支付事件侧共用“该渠道当日有流水”门禁：0 条流水时不批量生成单边账，交给跑批 PARTIAL。
 *  7. 差异处理动作全部写 reconciliation_difference_audit；自动重试同一执行日只允许一次，且刷新不覆盖已定业务日。
 *  8. 导出使用管理员/本店归属校验后的数据，CSV 转义逗号、引号与换行，避免公式注入。
 *  9. 时间记录统一使用 Asia/Shanghai 墙上时间，不依赖 JVM 默认时区。
 * 10. 非成功支付事件不得静默，且必须按 funds_received 三值分开：=1 且状态在七状态集合内才计入缺失渠道；
 *     =0 与 NULL 都不预期流水，但分别生成 REJECTED / REVIEW 差异；悬挂超过 5 分钟的 PROCESSING
 *     生成 REVIEW 差异；未悬挂 PROCESSING 计入 processingPending 汇总。
 * 11. funds_received=1 的事件按 G34 成因拆成独立差异类型，便于人工按类型筛选/统计/分发：
 *     PAYMENT_EVENT_REFUNDED（渠道已收款后已退款，键 EV|id|REFUNDED）与
 *     PAYMENT_EVENT_CHANNEL_CONFLICT（渠道答复与已收款记录冲突，键 EV|id|CHANNEL_CONFLICT）；
 *     其余拒绝仍为 PAYMENT_EVENT_REJECTED（键 EV|id|{eventStatus}）。两类新差异都以事件转 SUCCESS 自动关闭。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantAccountLedger;
import com.shop.b2b2c.entity.MerchantSettlementBatch;
import com.shop.b2b2c.entity.MerchantSettlementItem;
import com.shop.b2b2c.entity.PaymentChannelStatement;
import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.entity.PlatformCollectionSettlement;
import com.shop.b2b2c.entity.ReconciliationDifference;
import com.shop.b2b2c.entity.ReconciliationDifferenceAudit;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeRefund;
import com.shop.b2b2c.mapper.MerchantAccountLedgerMapper;
import com.shop.b2b2c.mapper.MerchantSettlementBatchMapper;
import com.shop.b2b2c.mapper.MerchantSettlementItemMapper;
import com.shop.b2b2c.mapper.PaymentChannelStatementMapper;
import com.shop.b2b2c.mapper.PaymentEventMapper;
import com.shop.b2b2c.mapper.PlatformCollectionSettlementMapper;
import com.shop.b2b2c.mapper.ReconciliationDifferenceAuditMapper;
import com.shop.b2b2c.mapper.ReconciliationDifferenceMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import com.shop.b2b2c.service.payment.PaymentEventReconcileService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    private final ReconciliationDifferenceAuditMapper auditMapper;
    private final TradeRefundMapper refundMapper;
    private final PlatformCollectionSettlementMapper platformSettlementMapper;

    public static final String CHANNEL_WITHOUT_EVENT = "CHANNEL_WITHOUT_EVENT";
    public static final String EVENT_WITHOUT_STATEMENT = "EVENT_WITHOUT_STATEMENT";
    public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    public static final String EVENT_STATUS_MISMATCH = "EVENT_STATUS_MISMATCH";
    public static final String ORDER_MISMATCH = "ORDER_MISMATCH";
    public static final String ORDER_MISSING = "ORDER_MISSING";
    public static final String FEE_LEDGER_MISSING = "FEE_LEDGER_MISSING";
    public static final String FEE_AMOUNT_MISMATCH = "FEE_AMOUNT_MISMATCH";
    public static final String DUPLICATE_PAYMENT = "DUPLICATE_PAYMENT";
    public static final String SETTLEMENT_ITEM_MISMATCH = "SETTLEMENT_ITEM_MISMATCH";
    public static final String REFUND_WITHOUT_STATEMENT = "REFUND_WITHOUT_STATEMENT";
    public static final String REFUND_AMOUNT_MISMATCH = "REFUND_AMOUNT_MISMATCH";
    public static final String PAYMENT_EVENT_REJECTED = "PAYMENT_EVENT_REJECTED";
    public static final String PAYMENT_EVENT_REVIEW = "PAYMENT_EVENT_REVIEW";
    /** 已收款后已退款：G34 渠道退款（REFUNDED，或支付宝 TRADE_CLOSED 且带退款金额证据）。 */
    public static final String PAYMENT_EVENT_REFUNDED = "PAYMENT_EVENT_REFUNDED";
    /** 渠道答复与已收款记录冲突：funds_received=1 但渠道回 CLOSED/FAILED/NOT_EXIST 等明确未支付。 */
    public static final String PAYMENT_EVENT_CHANNEL_CONFLICT = "PAYMENT_EVENT_CHANNEL_CONFLICT";
    public static final String REFUND_STATEMENT_WITHOUT_REFUND = "REFUND_STATEMENT_WITHOUT_REFUND";
    public static final String REFUND_FEE_LEDGER_MISSING = "REFUND_FEE_LEDGER_MISSING";
    public static final String REFUND_FEE_AMOUNT_MISMATCH = "REFUND_FEE_AMOUNT_MISMATCH";
    public static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";
    public static final String TIME_CROSS_DAY = "TIME_CROSS_DAY";
    public static final String PLATFORM_SETTLEMENT_MISMATCH = "PLATFORM_SETTLEMENT_MISMATCH";
    public static final String PLATFORM_SETTLEMENT_BATCH_MISSING = "PLATFORM_SETTLEMENT_BATCH_MISSING";

    /**
     * 预期会在本商户渠道账户产生流水的支付事件状态（第四~五轮口径，覆盖第三轮“仅三种状态”的错误判断）。
     * 仅“状态在集合内且 funds_received=1”才进入缺失渠道判定：
     * SUCCESS、DUPLICATE_PAYMENT、LATE_PAYMENT 与收到回调后 reject(...) 写入的拒绝状态均属已收款；
     * FULFILLMENT_FAILED 一码多义（回调后履约失败=1 / G34 渠道已收款后已退款=1 / G34 渠道明确未支付请求 0 /
     * G34 查单前置校验失败=NULL），由 funds_received 区分；REFUNDED 属“钱到过账又退回”，按 1 进入差集。
     * 注意：新事件在 claimEvent 已占位为 1，markFulfillmentFailed 的 CASE 会保留 1，故“明确未支付→0”对新事件不可达，
     * 仅对 funds_received IS NULL 的未标注行/历史或异常行生效；这类行遇到明确未支付答复会形成 CHANNEL_CONFLICT。
     * funds_received=0（明确无资金往来，仅未标注/历史异常行）与 NULL（未标注）都不预期流水，但必须各自生成可见差异，不能静默。
     * 不计入：PROCESSING（结果未确认，单独按悬挂阈值给差异信号）与未知/人工队列状态。
     */
    private static final Set<String> STATEMENT_EXPECTED_EVENT_STATUSES = Set.of(
            "SUCCESS", DUPLICATE_PAYMENT, "LATE_PAYMENT", "MERCHANT_MISMATCH",
            "AMOUNT_MISMATCH", "CURRENCY_MISMATCH", "FULFILLMENT_FAILED");

    /** 陈旧 PROCESSING 判定阈值：与 G34 补偿扫描默认阈值一致，避免两套口径。 */
    private static final Duration PROCESSING_STALE_AFTER = Duration.ofMinutes(5);

    /** 财务基线：同一差异自动重试上限；达到上限仍不一致必须转人工，不得无限重试。 */
    public static final int MAX_RETRY_COUNT = 3;
    public static final long SYSTEM_OPERATOR_ID = 0L;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

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
            statement.setCurrency(normalizeCurrency(source.getCurrency()));
            statement.setTradeStatus(source.getTradeStatus() == null ? "SUCCESS" : source.getTradeStatus());
            statement.setTradeTime(source.getTradeTime() == null
                    ? LocalDateTime.now(BUSINESS_ZONE) : source.getTradeTime());
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
     * 对账：渠道流水 ↔ 支付事件 ↔ 订单 ↔ 服务费账本 ↔ 退款 ↔ 结算 ↔ 平台代收；差异只入队。
     * 三参入口保留给手工按区间对账；T+1 跑批使用四参入口记录操作人。
     */
    @Transactional
    public Map<String, Object> reconcile(Long merchantId, LocalDateTime from, LocalDateTime to) {
        return reconcile(merchantId, from, to, null);
    }

    /**
     * T+1 巡检入口：按商户统计 success_at 缺失的成功退款。
     * 调用方应在每店每轮跑批只调用一次，不能放进“店铺 × 业务日”循环重复统计。
     */
    public long countSuccessWithoutSuccessAt(Long merchantId) {
        return refundMapper.countSuccessWithoutSuccessAt(merchantId);
    }

    @Transactional
    public Map<String, Object> reconcile(Long merchantId, LocalDateTime from, LocalDateTime to, Long operatorId) {
        Counts counts = new Counts();
        LocalDate businessDate = from.toLocalDate();
        List<PaymentChannelStatement> statements = statementMapper.findByMerchantAndPeriod(merchantId, from, to);
        Set<String> pairedStatements = new HashSet<>();
        Set<String> channelsWithStatements = new HashSet<>();
        Set<String> channelsWithLocalDocs = new HashSet<>();
        Set<String> channelsWithMatchedStatements = new HashSet<>();
        for (PaymentChannelStatement statement : statements) {
            if (statement.getChannel() != null && !statement.getChannel().isBlank()) {
                channelsWithStatements.add(statement.getChannel());
            }
        }
        for (PaymentChannelStatement statement : statements) {
            if (!"SUCCESS".equals(statement.getTradeStatus())) {
                continue;
            }
            pairedStatements.add(pairKey(statement.getChannel(), statement.getChannelTradeNo()));
            PaymentEvent event = eventMapper.findByChannelAndTradeNo(statement.getChannel(),
                    statement.getChannelTradeNo());
            if (event == null) {
                addDifference("CH|" + statement.getChannel() + "|" + statement.getChannelTradeNo(), merchantId,
                        statement.getChannel(), CHANNEL_WITHOUT_EVENT, "STATEMENT", statement.getId(),
                        statement.getOrderSn(), statement.getChannelTradeNo(), statement.getAmount(), null,
                        "渠道流水无对应支付事件", ReconciliationClassifier.ONE_SIDED_CHANNEL,
                        businessDate, operatorId, counts);
                continue;
            }
            compareStatementAndEvent(merchantId, statement, event, businessDate, operatorId, counts);
        }

        List<PaymentEvent> events = eventMapper.findByMerchantAndPeriod(merchantId, from, to);
        for (PaymentEvent event : events) {
            // 只有“已收到渠道回调、且渠道侧存在该笔资金往来（funds_received=1，应有对应流水）”的事件才计入缺失渠道判定：
            // SUCCESS / DUPLICATE_PAYMENT / LATE_PAYMENT 与 reject(...) 写入的拒绝状态均在集合内。
            // funds_received=0/NULL 不预期流水（NULL 由 REVIEW 差异兜住），未知状态也不计入，避免永久 PARTIAL。
            if (event.getChannel() != null && !event.getChannel().isBlank()
                    && event.getEventStatus() != null
                    && Integer.valueOf(1).equals(event.getFundsReceived())
                    && STATEMENT_EXPECTED_EVENT_STATUSES.contains(event.getEventStatus())) {
                channelsWithLocalDocs.add(event.getChannel());
            }
            TradeOrder order = orderMapper.findByOrderSn(event.getOrderSn()).orElse(null);
            if (event.getFundsReceived() == null
                    && ("SUCCESS".equals(event.getEventStatus()) || DUPLICATE_PAYMENT.equals(event.getEventStatus()))) {
                // 历史/漏写的成功类事件没有资金标注：不进差集，但必须人工可见，不能按“无需流水”放过。
                addDifference("EV|" + event.getId() + "|FUNDS", merchantId, event.getChannel(),
                        PAYMENT_EVENT_REVIEW, "EVENT", event.getId(), event.getOrderSn(), event.getPayTradeNo(),
                        event.getPayAmount(), null,
                        "支付事件状态 " + event.getEventStatus()
                                + "：资金到账标记未标注（funds_received IS NULL），需人工确认是否已收款",
                        ReconciliationClassifier.INTERNAL, businessDate, operatorId, counts);
            }
            if (DUPLICATE_PAYMENT.equals(event.getEventStatus())) {
                addDifference("DUP|" + event.getId(), merchantId, event.getChannel(), DUPLICATE_PAYMENT, "EVENT",
                        event.getId(), event.getOrderSn(), event.getPayTradeNo(), event.getPayAmount(), null,
                        "重复付款，走 T33 补偿/退款流程", ReconciliationClassifier.INTERNAL,
                        businessDate, operatorId, counts);
                continue;
            }
            if (!"SUCCESS".equals(event.getEventStatus())) {
                // 非成功事件不能静默跳过：渠道已收款后被拒绝的必须留差异，悬挂 PROCESSING 也必须有信号。
                signalNonSuccessEvent(merchantId, event, businessDate, operatorId, counts);
                continue;
            }
            if (order == null) {
                addDifference("EV|" + event.getId() + "|ORDER", merchantId, event.getChannel(), ORDER_MISSING,
                        "EVENT", event.getId(), event.getOrderSn(), event.getPayTradeNo(), event.getPayAmount(),
                        null, "支付事件找不到对应订单", ReconciliationClassifier.ONE_SIDED_LOCAL,
                        businessDate, operatorId, counts);
                continue;
            }
            if (!ReconciliationClassifier.amountEquals(order.getPayAmount(), event.getPayAmount())) {
                addDifference("EV|" + event.getId() + "|AMT", merchantId, event.getChannel(), AMOUNT_MISMATCH,
                        "ORDER", order.getId(), order.getOrderSn(), event.getPayTradeNo(), order.getPayAmount(),
                        event.getPayAmount(), "订单应付金额与支付事件实收金额不一致",
                        ReconciliationClassifier.classify(order.getPayAmount(), null, order.getCreatedAt(),
                                event.getPayAmount(), event.getCurrency(), event.getCreatedAt()),
                        businessDate, operatorId, counts);
            }
            BigDecimal fee = snapshotService.resolveFeeAmount(order);
            Optional<MerchantAccountLedger> chargeLedger =
                    ledgerMapper.findByIdempotencyKey("ORDER_FEE:" + order.getOrderSn());
            if (fee.signum() > 0 && chargeLedger.isEmpty()) {
                addDifference("FEE|" + order.getOrderSn(), merchantId, event.getChannel(), FEE_LEDGER_MISSING,
                        "ORDER", order.getId(), order.getOrderSn(), event.getPayTradeNo(), fee, null,
                        "已支付订单缺少服务费扣费账本", ReconciliationClassifier.ONE_SIDED_LOCAL,
                        businessDate, operatorId, counts);
            } else if (chargeLedger.isPresent()
                    && !ReconciliationClassifier.amountEquals(fee, chargeLedger.get().getAmount())) {
                addDifference("FEE|" + order.getOrderSn() + "|AMT", merchantId, event.getChannel(),
                        FEE_AMOUNT_MISMATCH, "LEDGER", chargeLedger.get().getId(), order.getOrderSn(),
                        event.getPayTradeNo(), fee, chargeLedger.get().getAmount(),
                        "服务费应收与账本扣费金额不一致",
                        ReconciliationClassifier.classifyInternal(fee, chargeLedger.get().getAmount()),
                        businessDate, operatorId, counts);
            }
            String pair = pairKey(event.getChannel(), event.getPayTradeNo());
            if (!pairedStatements.contains(pair)) {
                Optional<PaymentChannelStatement> statement = statementMapper.findByChannelAndTradeNo(
                        event.getChannel(), event.getPayTradeNo());
                if (statement.isPresent()) {
                    // 流水在相邻业务日：不按单边账处理，按跨天归属分类；该渠道视为已导入。
                    channelsWithMatchedStatements.add(event.getChannel());
                    compareStatementAndEvent(merchantId, statement.get(), event, businessDate, operatorId, counts);
                } else if (statementMapper.countByMerchantChannelPeriod(merchantId, event.getChannel(), from, to) > 0) {
                    addDifference("EV|" + event.getId() + "|ST", merchantId, event.getChannel(),
                            EVENT_WITHOUT_STATEMENT, "EVENT", event.getId(), event.getOrderSn(),
                            event.getPayTradeNo(), event.getPayAmount(), null,
                            "已导入渠道流水但缺少本笔流水", ReconciliationClassifier.ONE_SIDED_LOCAL,
                            businessDate, operatorId, counts);
                }
            }
        }

        List<TradeRefund> refunds = refundMapper.findSuccessfulRefundsByPeriod(merchantId, from, to);
        Set<String> pairedRefunds = new HashSet<>();
        for (TradeRefund refund : refunds) {
            if (refund.getChannel() != null && !refund.getChannel().isBlank()) {
                channelsWithLocalDocs.add(refund.getChannel());
            }
            reconcileRefund(merchantId, refund, businessDate, operatorId, counts, pairedRefunds,
                    channelsWithStatements, channelsWithMatchedStatements);
        }
        for (PaymentChannelStatement statement : statements) {
            if (!"REFUND".equals(statement.getTradeStatus())) {
                continue;
            }
            String key = pairKey(statement.getChannel(), statement.getChannelTradeNo());
            if (pairedRefunds.contains(key)) {
                continue;
            }
            Optional<TradeRefund> local = refundMapper.findByChannelRefundNo(statement.getChannelTradeNo());
            if (local.isEmpty()) {
                // 渠道有退款流水但本地无退款：归属日取流水 trade_time，取不到再退回窗口业务日。
                addDifference("REFST|" + statement.getChannel() + "|" + statement.getChannelTradeNo(),
                        merchantId, statement.getChannel(), REFUND_STATEMENT_WITHOUT_REFUND, "STATEMENT",
                        statement.getId(), statement.getOrderSn(), statement.getChannelTradeNo(), null,
                        statement.getAmount(), "渠道退款流水无对应本地退款记录",
                        ReconciliationClassifier.ONE_SIDED_CHANNEL,
                        statement.getTradeTime() == null ? businessDate : statement.getTradeTime().toLocalDate(),
                        operatorId, counts);
            } else {
                compareRefundAndStatement(merchantId, local.get(), statement,
                        refundBusinessDate(local.get(), statement, businessDate), operatorId, counts);
            }
        }

        List<MerchantSettlementBatch> batches = batchMapper.findConfirmedByPeriod(merchantId,
                from.toLocalDate(), to.toLocalDate());
        LocalDate windowStart = from.toLocalDate();
        for (MerchantSettlementBatch batch : batches) {
            // 批次账期为开区间：periodStart=期初、periodEnd=期末次日（月结生成器与手工按月入口都如此）。
            // 只有对账窗口与批次账期完全一致时才校验合计，避免把月结批次盘成单日差异。
            if (batch.getPeriodStart() == null || batch.getPeriodEnd() == null
                    || !batch.getPeriodStart().equals(windowStart)
                    || !batch.getPeriodEnd().equals(to.toLocalDate())) {
                continue;
            }
            List<MerchantSettlementItem> items = itemMapper.findByBatchId(batch.getId());
            BigDecimal feeTotal = ZERO;
            BigDecimal adjustment = ZERO;
            BigDecimal net = ZERO;
            for (MerchantSettlementItem item : items) {
                feeTotal = feeTotal.add(item.getFeeAmount() == null ? ZERO : item.getFeeAmount());
                if ("REFUND_REVERSAL".equals(item.getItemType())) {
                    adjustment = adjustment.add(item.getFeeAmount() == null ? ZERO : item.getFeeAmount());
                }
                net = net.add(item.getNetFee() == null ? ZERO : item.getNetFee());
            }
            if (feeTotal.compareTo(batch.getFeeAmount() == null ? ZERO : batch.getFeeAmount()) != 0
                    || adjustment.compareTo(batch.getAdjustmentAmount() == null
                            ? ZERO : batch.getAdjustmentAmount()) != 0
                    || net.compareTo(batch.getNetFeeAmount() == null ? ZERO : batch.getNetFeeAmount()) != 0) {
                addDifference("STL|" + batch.getId(), merchantId, null, SETTLEMENT_ITEM_MISMATCH, "SETTLEMENT",
                        batch.getId(), null, null, batch.getNetFeeAmount(), net,
                        "结算批次合计与逐笔明细不一致", ReconciliationClassifier.INTERNAL,
                        businessDate, operatorId, counts);
            }
        }

        List<PlatformCollectionSettlement> settlements = platformSettlementMapper
                .findByMerchantAndPeriod(merchantId, from, to);
        for (PlatformCollectionSettlement settlement : settlements) {
            reconcilePlatformSettlement(merchantId, settlement, businessDate, operatorId, counts);
        }

        // 按渠道统计“本地有单据但当期 0 流水”：多渠道只导入一部分时，未导入渠道的单边账被门禁压住，
        // 但必须把缺失渠道返回给跑批标记 PARTIAL，不能因为总流水数不为 0 就静默通过。
        List<String> incompleteChannels = new ArrayList<>();
        for (String channel : channelsWithLocalDocs) {
            if (!channelsWithStatements.contains(channel)
                    && !channelsWithMatchedStatements.contains(channel)) {
                incompleteChannels.add(channel);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("added", counts.added);
        result.put("refreshed", counts.refreshed);
        result.put("skippedClosed", counts.skipped);
        result.put("statements", statements.size());
        result.put("events", events.size());
        result.put("refunds", refunds.size());
        result.put("batches", batches.size());
        result.put("platformSettlements", settlements.size());
        result.put("incompleteChannels", String.join(",", incompleteChannels));
        // 未超悬挂阈值的 PROCESSING 不生成差异（渠道回查仍在进行，逐笔入队会制造噪音），
        // 但必须在跑批汇总里可见，不能静默。悬挂超阈值的 PROCESSING 已转为 PAYMENT_EVENT_REVIEW 差异。
        result.put("processingPending", counts.processingPending);
        result.put("businessDate", businessDate.toString());
        return result;
    }

    /**
     * 非 SUCCESS 支付事件的对账信号（DUPLICATE_PAYMENT 已在主循环单独入队）。
     * 选择“生成差异”而不是只做汇总计数：差异可分派、可审计、可按单据定位，纯计数无法定位是哪一笔。
     * 资金到账三个取值分开处理（见 §13.5）：
     *  - 状态在七状态集合内且 funds_received=1（新事件占位即 1，语义为“渠道侧存在该笔资金往来”）：
     *    普通拒绝生成 PAYMENT_EVENT_REJECTED；渠道已退款生成 PAYMENT_EVENT_REFUNDED（键 EV|id|REFUNDED）；
     *    渠道答复与已收款记录冲突生成 PAYMENT_EVENT_CHANNEL_CONFLICT（键 EV|id|CHANNEL_CONFLICT）；
     *  - 状态在集合内且 funds_received=0：仅未标注行/历史或异常行可能出现，不预期流水，
     *    但仍生成 PAYMENT_EVENT_REJECTED 供人工核对，避免静默；
     *  - 状态在集合内且 funds_received IS NULL：资金未标注，生成 PAYMENT_EVENT_REVIEW，要求人工确认是否已收款；
     *  - PROCESSING 超过 {@link #PROCESSING_STALE_AFTER} 视为悬挂：生成 PAYMENT_EVENT_REVIEW；
     *  - PROCESSING 未超阈值：只计入 processingPending，随跑批汇总返回；
     *  - 其它状态（ORDER_STATUS_INVALID、MANUAL_REVIEW 等）：生成 PAYMENT_EVENT_REVIEW，避免静默。
     */
    private void signalNonSuccessEvent(Long merchantId, PaymentEvent event, LocalDate businessDate,
                                       Long operatorId, Counts counts) {
        String status = event.getEventStatus();
        String tradeNo = event.getPayTradeNo();
        if ("PROCESSING".equals(status)) {
            if (!isStaleProcessing(event)) {
                counts.processingPending++;
                return;
            }
            addDifference("EV|" + event.getId() + "|PROCESSING", merchantId, event.getChannel(),
                    PAYMENT_EVENT_REVIEW, "EVENT", event.getId(), event.getOrderSn(), tradeNo,
                    event.getPayAmount(), null,
                    "支付事件 PROCESSING 悬挂超过" + PROCESSING_STALE_AFTER.toMinutes()
                            + "分钟（业务时区 Asia/Shanghai），渠道结果未回写，需人工核对",
                    ReconciliationClassifier.INTERNAL, businessDate, operatorId, counts);
            return;
        }
        if (status != null && STATEMENT_EXPECTED_EVENT_STATUSES.contains(status)) {
            Integer fundsReceived = event.getFundsReceived();
            if (Integer.valueOf(1).equals(fundsReceived)) {
                if ("FULFILLMENT_FAILED".equals(status) && startsWithReason(event,
                        PaymentEventReconcileService.REASON_REFUNDED)) {
                    // 退款类独立成类型/独立键：人工可按类型筛选分发，不再依赖自由文本。
                    addDifference("EV|" + event.getId() + "|REFUNDED", merchantId, event.getChannel(),
                            PAYMENT_EVENT_REFUNDED, "EVENT", event.getId(), event.getOrderSn(), tradeNo,
                            event.getPayAmount(), null,
                            "支付事件状态 FULFILLMENT_FAILED：渠道已收款后已退款（" + channelStateOf(event)
                                    + "），需核对退款流水与履约结果",
                            ReconciliationClassifier.INTERNAL, businessDate, operatorId, counts);
                    return;
                }
                if ("FULFILLMENT_FAILED".equals(status)
                        && (startsWithReason(event, PaymentEventReconcileService.REASON_DEFINITIVE_NOT_PAID)
                        || startsWithReason(event,
                        PaymentEventReconcileService.REASON_CHANNEL_CLOSED_UNDETERMINED))) {
                    // funds_received=1 但渠道明确未支付/支付宝关闭无法判定：答复与已建事件冲突，独立类型/独立键。
                    addDifference("EV|" + event.getId() + "|CHANNEL_CONFLICT", merchantId, event.getChannel(),
                            PAYMENT_EVENT_CHANNEL_CONFLICT, "EVENT", event.getId(), event.getOrderSn(), tradeNo,
                            event.getPayAmount(), null,
                            "支付事件状态 FULFILLMENT_FAILED：渠道答复与已收款记录冲突（" + channelStateOf(event)
                                    + "），可能为全额退款或渠道状态异常，需核对渠道流水",
                            ReconciliationClassifier.INTERNAL, businessDate, operatorId, counts);
                    return;
                }
                String detail = "FULFILLMENT_FAILED".equals(status)
                        ? "支付事件状态 FULFILLMENT_FAILED：渠道已收款但履约失败，需人工核对资金与履行结果"
                        : "支付事件状态 " + status + "：渠道已收款但履约/校验被拒绝，需人工核对资金与履行结果";
                addDifference("EV|" + event.getId() + "|" + status, merchantId, event.getChannel(),
                        PAYMENT_EVENT_REJECTED, "EVENT", event.getId(), event.getOrderSn(), tradeNo,
                        event.getPayAmount(), null, detail,
                        ReconciliationClassifier.INTERNAL, businessDate, operatorId, counts);
            } else if (Integer.valueOf(0).equals(fundsReceived)) {
                // 0 仅可能是未标注行或历史/异常行：新事件占位即 1，G34 的 CASE 也不会把 1 降成 0。
                // 此类行本来就不该有流水，不进入缺失渠道差集；拒绝事件仍须可见。
                addDifference("EV|" + event.getId() + "|" + status, merchantId, event.getChannel(),
                        PAYMENT_EVENT_REJECTED, "EVENT", event.getId(), event.getOrderSn(), tradeNo,
                        event.getPayAmount(), null,
                        "支付事件状态 " + status + "：渠道明确未收到资金，不预期产生流水；拒绝事件仍需人工核对",
                        ReconciliationClassifier.INTERNAL, businessDate, operatorId, counts);
            } else {
                // NULL 是“未标注、需要人看”，不得当作“无需流水”静默放过。
                addDifference("EV|" + event.getId() + "|" + status, merchantId, event.getChannel(),
                        PAYMENT_EVENT_REVIEW, "EVENT", event.getId(), event.getOrderSn(), tradeNo,
                        event.getPayAmount(), null,
                        "支付事件状态 " + status + "：资金到账标记未标注（funds_received IS NULL），需人工确认是否已收款",
                        ReconciliationClassifier.INTERNAL, businessDate, operatorId, counts);
            }
            return;
        }
        String statusKey = status == null || status.isBlank() ? "UNKNOWN" : status;
        addDifference("EV|" + event.getId() + "|" + statusKey, merchantId, event.getChannel(),
                PAYMENT_EVENT_REVIEW, "EVENT", event.getId(), event.getOrderSn(), tradeNo,
                event.getPayAmount(), null,
                "支付事件状态 " + statusKey + " 未进入预期流水状态，需人工确认真实结果",
                ReconciliationClassifier.INTERNAL, businessDate, operatorId, counts);
    }

    /** G34 写入的失败原因使用固定前缀作为成因契约，T+1 据此选择差异类型，不依赖自由文本匹配。 */
    private boolean startsWithReason(PaymentEvent event, String prefix) {
        return event.getErrorMessage() != null && event.getErrorMessage().startsWith(prefix);
    }

    /** 从 G34 原因“{前缀}: state=XXX”中提取渠道状态，用于差异说明定位；无该片段时返回“渠道状态未知”。 */
    private String channelStateOf(PaymentEvent event) {
        String message = event.getErrorMessage();
        if (message == null) {
            return "渠道状态未知";
        }
        int index = message.indexOf("state=");
        if (index < 0) {
            return "渠道状态未知";
        }
        String state = message.substring(index + "state=".length()).trim();
        return state.isEmpty() ? "渠道状态未知" : "渠道查单 state=" + state;
    }

    /**
     * 陈旧 PROCESSING 判定：created_at 早于当前时间 5 分钟。
     * 阈值与 G34 补偿扫描默认值一致，避免对账侧与补扫侧形成两套“悬挂”口径。
     * created_at 缺失时无法判龄，按未悬挂处理，交给 G34 回查补写。
     */
    private boolean isStaleProcessing(PaymentEvent event) {
        LocalDateTime createdAt = event.getCreatedAt();
        return createdAt != null
                && createdAt.isBefore(LocalDateTime.now(BUSINESS_ZONE).minus(PROCESSING_STALE_AFTER));
    }

    public List<ReconciliationDifference> list(Long merchantId, String status, int limit) {
        return differenceMapper.find(merchantId, status, limit);
    }

    /**
     * 重试差异：仅做只读复核；能从现有证据确认一致时置 RESOLVED，否则保持 RETRY 等待人工。
     * 同一业务日重复跑批/重复点击不重复计数；达到 {@link #MAX_RETRY_COUNT} 转 MANUAL。
     */
    @Transactional
    public ReconciliationDifference retry(Long id, Long operatorId) {
        return retry(id, operatorId, LocalDate.now(BUSINESS_ZONE));
    }

    @Transactional
    public ReconciliationDifference retry(Long id, Long operatorId, LocalDate businessDate) {
        ReconciliationDifference difference = require(id);
        if ("MANUAL".equals(difference.getStatus())) {
            throw new BusinessException(409, "该差异已超过重试上限转人工，请人工处理");
        }
        if ("RESOLVED".equals(difference.getStatus()) || "IGNORED".equals(difference.getStatus())) {
            throw new BusinessException(409, "该差异已关闭，不能重试");
        }
        LocalDate retryDate = businessDate == null ? LocalDate.now(BUSINESS_ZONE) : businessDate;
        if (difference.getLastRetryDate() != null && difference.getLastRetryDate().equals(retryDate)) {
            return difference;
        }
        String fromStatus = difference.getStatus();
        int retryCount = (difference.getRetryCount() == null ? 0 : difference.getRetryCount()) + 1;
        difference.setRetryCount(retryCount);
        difference.setLastRetryDate(retryDate);
        difference.setHandledBy(operatorId);
        difference.setHandledAt(LocalDateTime.now(BUSINESS_ZONE));
        boolean resolved = stillConsistent(difference);
        String action;
        if (resolved) {
            difference.setStatus("RESOLVED");
            difference.setHandleNote("复核一致，自动关闭");
            action = "RESOLVE";
        } else if (retryCount >= MAX_RETRY_COUNT) {
            difference.setStatus("MANUAL");
            difference.setHandleNote("重试" + retryCount + "次仍未一致，已转人工处理");
            action = "ESCALATE";
        } else {
            difference.setStatus("RETRY");
            difference.setHandleNote("已发起复核（第" + retryCount + "/" + MAX_RETRY_COUNT + "次）");
            action = "RETRY";
        }
        differenceMapper.updateById(difference);
        audit(difference, action, fromStatus, difference.getStatus(), operatorId, difference.getHandleNote());
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
        String fromStatus = difference.getStatus();
        difference.setStatus(ignore ? "IGNORED" : "RESOLVED");
        difference.setHandledBy(operatorId);
        difference.setHandledAt(LocalDateTime.now(BUSINESS_ZONE));
        difference.setHandleNote(note.trim());
        differenceMapper.updateById(difference);
        audit(difference, ignore ? "IGNORE" : "RESOLVE", fromStatus, difference.getStatus(), operatorId,
                difference.getHandleNote());
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

    private void compareStatementAndEvent(Long merchantId, PaymentChannelStatement statement, PaymentEvent event,
                                          LocalDate businessDate, Long operatorId, Counts counts) {
        String base = "CH|" + statement.getChannel() + "|" + statement.getChannelTradeNo();
        if (!"SUCCESS".equals(event.getEventStatus())) {
            addDifference(base + "|STATUS", merchantId, statement.getChannel(), EVENT_STATUS_MISMATCH, "EVENT",
                    event.getId(), event.getOrderSn(), statement.getChannelTradeNo(),
                    event.getPayAmount(), statement.getAmount(),
                    "支付事件状态不是 SUCCESS：" + event.getEventStatus(), ReconciliationClassifier.INTERNAL,
                    businessDate, operatorId, counts);
        } else if (ReconciliationClassifier.currencyMismatch(event.getCurrency(), statement.getCurrency())) {
            addDifference(base + "|CUR", merchantId, statement.getChannel(), CURRENCY_MISMATCH, "STATEMENT",
                    statement.getId(), statement.getOrderSn(), statement.getChannelTradeNo(),
                    event.getPayAmount(), statement.getAmount(),
                    "支付事件币种 " + event.getCurrency() + " 与渠道流水币种 " + statement.getCurrency() + " 不一致",
                    ReconciliationClassifier.CURRENCY, businessDate, operatorId, counts);
        } else if (!ReconciliationClassifier.amountEquals(event.getPayAmount(), statement.getAmount())) {
            addDifference(base + "|AMT", merchantId, statement.getChannel(), AMOUNT_MISMATCH, "STATEMENT",
                    statement.getId(), statement.getOrderSn(), statement.getChannelTradeNo(),
                    event.getPayAmount(), statement.getAmount(), "渠道金额与支付事件金额不一致",
                    ReconciliationClassifier.classify(event.getPayAmount(), event.getCurrency(),
                            event.getCreatedAt(), statement.getAmount(), statement.getCurrency(),
                            statement.getTradeTime()),
                    businessDate, operatorId, counts);
        } else if (statement.getOrderSn() != null && event.getOrderSn() != null
                && !statement.getOrderSn().equals(event.getOrderSn())) {
            addDifference(base + "|ORDER", merchantId, statement.getChannel(), ORDER_MISMATCH, "STATEMENT",
                    statement.getId(), statement.getOrderSn(), statement.getChannelTradeNo(), null, null,
                    "渠道流水订单号与支付事件订单号不一致", ReconciliationClassifier.INTERNAL,
                    businessDate, operatorId, counts);
        } else if (ReconciliationClassifier.dateMismatch(event.getCreatedAt(), statement.getTradeTime())) {
            addDifference(base + "|DAY", merchantId, statement.getChannel(), TIME_CROSS_DAY, "STATEMENT",
                    statement.getId(), statement.getOrderSn(), statement.getChannelTradeNo(),
                    event.getPayAmount(), statement.getAmount(),
                    "金额一致但支付事件与渠道流水业务日不同，按渠道交易时间归属",
                    ReconciliationClassifier.TIME_CROSS_DAY, businessDate, operatorId, counts);
        }
    }

    private void reconcileRefund(Long merchantId, TradeRefund refund, LocalDate fallbackDate, Long operatorId,
                                 Counts counts, Set<String> pairedRefunds, Set<String> channelsWithStatements,
                                 Set<String> channelsWithMatchedStatements) {
        String refundNo = refund.getChannelRefundNo();
        LocalDate refundDate = refundBusinessDate(refund, null, fallbackDate);
        // 与支付事件侧一致的流水门禁：只有该渠道当期已导入流水时才判退款单边账；
        // 渠道为空时退化为窗口内是否存在任何流水。0 流水时一律推迟到流水导入后再判，避免整批误报。
        boolean channelImported = refund.getChannel() == null || refund.getChannel().isBlank()
                ? !channelsWithStatements.isEmpty()
                : channelsWithStatements.contains(refund.getChannel());
        boolean refundStatementFound = false;
        if (refund.getChannel() == null || refund.getChannel().isBlank()
                || refundNo == null || refundNo.isBlank()) {
            if (channelImported) {
                addDifference("REF|" + refund.getId() + "|NO", merchantId, refund.getChannel(),
                        REFUND_WITHOUT_STATEMENT, "REFUND", refund.getId(), refund.getOrderSn(), null,
                        refund.getRefundAmount(), null, "退款成功但缺少渠道退款流水号/渠道",
                        ReconciliationClassifier.ONE_SIDED_LOCAL, refundDate, operatorId, counts);
            }
        } else {
            Optional<PaymentChannelStatement> statement =
                    statementMapper.findByChannelAndTradeNo(refund.getChannel(), refundNo);
            if (statement.isEmpty()) {
                if (channelImported) {
                    addDifference("REF|" + refund.getId() + "|ST", merchantId, refund.getChannel(),
                            REFUND_WITHOUT_STATEMENT, "REFUND", refund.getId(), refund.getOrderSn(), refundNo,
                            refund.getRefundAmount(), null, "退款成功但渠道无对应退款流水",
                            ReconciliationClassifier.ONE_SIDED_LOCAL, refundDate, operatorId, counts);
                }
            } else {
                pairedRefunds.add(pairKey(refund.getChannel(), refundNo));
                refundStatementFound = true;
                // 流水落在相邻业务日：该渠道已导入，不能因本窗口无流水而永久 PARTIAL。
                channelsWithMatchedStatements.add(refund.getChannel());
                refundDate = refundBusinessDate(refund, statement.get(), fallbackDate);
                compareRefundAndStatement(merchantId, refund, statement.get(), refundDate, operatorId, counts);
            }
        }
        BigDecimal feeRefund = refund.getFeeRefundAmount() == null ? ZERO : refund.getFeeRefundAmount();
        // 已匹配到跨窗口渠道流水的退款也应校验服务费冲正，不能因本窗口无该渠道流水而跳过。
        if ("ORDER".equals(refund.getSource()) && feeRefund.signum() > 0
                && (channelImported || refundStatementFound)) {
            String feeKey = "REFUND_FEE:" + refund.getRefundNo();
            Optional<MerchantAccountLedger> reversal = ledgerMapper.findByIdempotencyKey(feeKey);
            if (reversal.isEmpty()) {
                addDifference("REFFEE|" + refund.getRefundNo(), merchantId, refund.getChannel(),
                        REFUND_FEE_LEDGER_MISSING, "REFUND", refund.getId(), refund.getOrderSn(),
                        refundNo, feeRefund, null, "退款成功但缺少服务费冲正账本",
                        ReconciliationClassifier.ONE_SIDED_LOCAL, refundDate, operatorId, counts);
            } else if (!ReconciliationClassifier.amountEquals(feeRefund, reversal.get().getAmount())) {
                addDifference("REFFEE|" + refund.getRefundNo() + "|AMT", merchantId, refund.getChannel(),
                        REFUND_FEE_AMOUNT_MISMATCH, "LEDGER", reversal.get().getId(), refund.getOrderSn(),
                        refundNo, feeRefund, reversal.get().getAmount(),
                        "退款服务费冲正与账本金额不一致",
                        ReconciliationClassifier.classifyInternal(feeRefund, reversal.get().getAmount()),
                        refundDate, operatorId, counts);
            }
        }
    }

    private void compareRefundAndStatement(Long merchantId, TradeRefund refund, PaymentChannelStatement statement,
                                           LocalDate businessDate, Long operatorId, Counts counts) {
        String base = "REF|" + refund.getId() + "|" + statement.getChannel() + "|"
                + statement.getChannelTradeNo();
        LocalDateTime localSuccessTime = refund.getSuccessAt();
        if (!ReconciliationClassifier.amountEquals(refund.getRefundAmount(), statement.getAmount())) {
            addDifference(base + "|AMT", merchantId, statement.getChannel(), REFUND_AMOUNT_MISMATCH, "REFUND",
                    refund.getId(), refund.getOrderSn(), statement.getChannelTradeNo(),
                    refund.getRefundAmount(), statement.getAmount(),
                    "退款金额与渠道退款流水金额不一致",
                    ReconciliationClassifier.classify(refund.getRefundAmount(), null, localSuccessTime,
                            statement.getAmount(), statement.getCurrency(), statement.getTradeTime()),
                    businessDate, operatorId, counts);
        } else if (ReconciliationClassifier.dateMismatch(localSuccessTime, statement.getTradeTime())) {
            addDifference(base + "|DAY", merchantId, statement.getChannel(), TIME_CROSS_DAY, "REFUND",
                    refund.getId(), refund.getOrderSn(), statement.getChannelTradeNo(),
                    refund.getRefundAmount(), statement.getAmount(),
                    "金额一致但退款与渠道退款流水业务日不同，按渠道交易时间归属",
                    ReconciliationClassifier.TIME_CROSS_DAY, businessDate, operatorId, counts);
        }
    }

    /** 退款归属业务日：优先渠道退款流水的 trade_time；无流水时退回不可变的 success_at；再退回对账窗口起始日。 */
    private LocalDate refundBusinessDate(TradeRefund refund, PaymentChannelStatement statement,
                                         LocalDate fallbackDate) {
        if (statement != null && statement.getTradeTime() != null) {
            return statement.getTradeTime().toLocalDate();
        }
        if (refund.getSuccessAt() != null) {
            return refund.getSuccessAt().toLocalDate();
        }
        return fallbackDate;
    }

    private void reconcilePlatformSettlement(Long merchantId, PlatformCollectionSettlement settlement,
                                             LocalDate businessDate, Long operatorId, Counts counts) {
        String base = "PS|" + settlement.getId();
        if (settlement.getPaymentEventId() != null) {
            PaymentEvent event = eventMapper.selectById(settlement.getPaymentEventId());
            if (event == null) {
                addDifference(base + "|EV", merchantId, null, PLATFORM_SETTLEMENT_MISMATCH,
                        "PLATFORM_SETTLEMENT", settlement.getId(), settlement.getOrderSn(), null,
                        settlement.getGrossAmount(), null, "平台代收台账关联的支付事件不存在",
                        ReconciliationClassifier.ONE_SIDED_LOCAL, businessDate, operatorId, counts);
            } else if (!ReconciliationClassifier.amountEquals(event.getPayAmount(), settlement.getGrossAmount())) {
                addDifference(base + "|AMT", merchantId, null, PLATFORM_SETTLEMENT_MISMATCH,
                        "PLATFORM_SETTLEMENT", settlement.getId(), settlement.getOrderSn(),
                        event.getPayTradeNo(), event.getPayAmount(), settlement.getGrossAmount(),
                        "平台代收总额与支付事件实收金额不一致",
                        ReconciliationClassifier.classifyInternal(event.getPayAmount(), settlement.getGrossAmount()),
                        businessDate, operatorId, counts);
            }
        }
        if (settlement.getSettleBatchId() != null) {
            MerchantSettlementBatch batch = batchMapper.selectById(settlement.getSettleBatchId());
            if (batch == null || !merchantId.equals(batch.getMerchantId())) {
                addDifference(base + "|BATCH", merchantId, null, PLATFORM_SETTLEMENT_BATCH_MISSING,
                        "PLATFORM_SETTLEMENT", settlement.getId(), settlement.getOrderSn(), null,
                        null, null, "平台代收台账关联的结算批次不存在或不属于本店",
                        ReconciliationClassifier.ONE_SIDED_LOCAL, businessDate, operatorId, counts);
            }
        }
        if ("SETTLED".equals(settlement.getSettlementStatus())) {
            BigDecimal payable = settlement.getMerchantPayableAmount() == null
                    ? ZERO : settlement.getMerchantPayableAmount();
            BigDecimal refunded = settlement.getRefundedAmount() == null ? ZERO : settlement.getRefundedAmount();
            BigDecimal expectedSettled = payable.subtract(refunded).setScale(2, RoundingMode.HALF_UP);
            BigDecimal actualSettled = settlement.getSettledAmount() == null ? ZERO : settlement.getSettledAmount();
            if (!ReconciliationClassifier.amountEquals(expectedSettled, actualSettled)) {
                addDifference(base + "|SETTLED", merchantId, null, PLATFORM_SETTLEMENT_MISMATCH,
                        "PLATFORM_SETTLEMENT", settlement.getId(), settlement.getOrderSn(), null,
                        expectedSettled, actualSettled, "已结算金额与商家应结金额不一致",
                        ReconciliationClassifier.classifyInternal(expectedSettled, actualSettled),
                        businessDate, operatorId, counts);
            }
        } else if ("DIFFERENCE".equals(settlement.getSettlementStatus())) {
            addDifference(base + "|DIFF", merchantId, null, PLATFORM_SETTLEMENT_MISMATCH,
                    "PLATFORM_SETTLEMENT", settlement.getId(), settlement.getOrderSn(), null,
                    settlement.getGrossAmount(), settlement.getSettledAmount(),
                    "平台代收台账已标记差异，待人工处理", ReconciliationClassifier.INTERNAL,
                    businessDate, operatorId, counts);
        }
    }

    /**
     * 轮询复核差异是否可以自动关闭；金额类长/短款、精度、币种、跨天与人工标记差异不自动关闭。
     */
    private boolean stillConsistent(ReconciliationDifference difference) {
        String type = difference.getDiffType();
        if (CHANNEL_WITHOUT_EVENT.equals(type) && difference.getChannel() != null
                && difference.getChannelTradeNo() != null) {
            PaymentEvent event = eventMapper.findByChannelAndTradeNo(difference.getChannel(),
                    difference.getChannelTradeNo());
            return event != null && "SUCCESS".equals(event.getEventStatus())
                    && ReconciliationClassifier.amountEquals(difference.getExpectedAmount(), event.getPayAmount());
        }
        if (EVENT_WITHOUT_STATEMENT.equals(type) && difference.getChannel() != null
                && difference.getChannelTradeNo() != null) {
            PaymentChannelStatement statement = statementMapper.findByChannelAndTradeNo(
                    difference.getChannel(), difference.getChannelTradeNo()).orElse(null);
            return statement != null
                    && ReconciliationClassifier.amountEquals(difference.getExpectedAmount(), statement.getAmount());
        }
        if (FEE_LEDGER_MISSING.equals(type) && difference.getOrderSn() != null) {
            return ledgerMapper.findByIdempotencyKey("ORDER_FEE:" + difference.getOrderSn()).isPresent();
        }
        if (FEE_AMOUNT_MISMATCH.equals(type) && difference.getOrderSn() != null) {
            return ledgerMapper.findByIdempotencyKey("ORDER_FEE:" + difference.getOrderSn())
                    .map(ledger -> ReconciliationClassifier.amountEquals(difference.getExpectedAmount(),
                            ledger.getAmount()))
                    .orElse(false);
        }
        if (ORDER_MISSING.equals(type) && difference.getOrderSn() != null) {
            return orderMapper.findByOrderSn(difference.getOrderSn()).isPresent();
        }
        if (EVENT_STATUS_MISMATCH.equals(type) && difference.getChannel() != null
                && difference.getChannelTradeNo() != null) {
            PaymentEvent event = eventMapper.findByChannelAndTradeNo(difference.getChannel(),
                    difference.getChannelTradeNo());
            return event != null && "SUCCESS".equals(event.getEventStatus())
                    && ReconciliationClassifier.amountEquals(difference.getExpectedAmount(), event.getPayAmount());
        }
        if (ORDER_MISMATCH.equals(type) && difference.getChannel() != null
                && difference.getChannelTradeNo() != null) {
            PaymentChannelStatement statement = statementMapper.findByChannelAndTradeNo(
                    difference.getChannel(), difference.getChannelTradeNo()).orElse(null);
            PaymentEvent event = eventMapper.findByChannelAndTradeNo(difference.getChannel(),
                    difference.getChannelTradeNo());
            return statement != null && event != null
                    && statement.getOrderSn() != null && statement.getOrderSn().equals(event.getOrderSn());
        }
        if (REFUND_WITHOUT_STATEMENT.equals(type) && difference.getChannel() != null
                && difference.getChannelTradeNo() != null) {
            PaymentChannelStatement statement = statementMapper.findByChannelAndTradeNo(
                    difference.getChannel(), difference.getChannelTradeNo()).orElse(null);
            return statement != null
                    && ReconciliationClassifier.amountEquals(difference.getExpectedAmount(), statement.getAmount());
        }
        if (REFUND_STATEMENT_WITHOUT_REFUND.equals(type) && difference.getChannelTradeNo() != null) {
            Optional<TradeRefund> refund = refundMapper.findByChannelRefundNo(difference.getChannelTradeNo());
            return refund.isPresent()
                    && ReconciliationClassifier.amountEquals(refund.get().getRefundAmount(),
                    difference.getActualAmount());
        }
        if (REFUND_FEE_LEDGER_MISSING.equals(type) || REFUND_FEE_AMOUNT_MISMATCH.equals(type)) {
            TradeRefund refund = refundMapper.selectById(difference.getBizId());
            if (refund == null) {
                return false;
            }
            Optional<MerchantAccountLedger> reversal =
                    ledgerMapper.findByIdempotencyKey("REFUND_FEE:" + refund.getRefundNo());
            if (reversal.isEmpty()) {
                return false;
            }
            return !REFUND_FEE_AMOUNT_MISMATCH.equals(type)
                    || ReconciliationClassifier.amountEquals(difference.getExpectedAmount(),
                    reversal.get().getAmount());
        }
        if (PAYMENT_EVENT_REJECTED.equals(type)) {
            // 事件最终回写为 SUCCESS 才说明资金与履约已闭环；否则保持人工队列。
            if (difference.getBizId() == null) {
                return false;
            }
            PaymentEvent event = eventMapper.selectById(difference.getBizId());
            return event != null && "SUCCESS".equals(event.getEventStatus());
        }
        if (PAYMENT_EVENT_REVIEW.equals(type)) {
            // REVIEW 还要求资金标注已补：SUCCESS 但 funds_received 仍为 NULL 的漏写不能自动关闭。
            if (difference.getBizId() == null) {
                return false;
            }
            PaymentEvent event = eventMapper.selectById(difference.getBizId());
            return event != null && "SUCCESS".equals(event.getEventStatus())
                    && event.getFundsReceived() != null;
        }
        if (PAYMENT_EVENT_REFUNDED.equals(type) || PAYMENT_EVENT_CHANNEL_CONFLICT.equals(type)) {
            // 退款/冲突两类差异都以“事件最终回写 SUCCESS”为闭环条件；否则保持差异等待人工核对退款流水。
            if (difference.getBizId() == null) {
                return false;
            }
            PaymentEvent event = eventMapper.selectById(difference.getBizId());
            return event != null && "SUCCESS".equals(event.getEventStatus());
        }
        if (PLATFORM_SETTLEMENT_BATCH_MISSING.equals(type)) {
            PlatformCollectionSettlement settlement = platformSettlementMapper.selectById(difference.getBizId());
            if (settlement == null || settlement.getSettleBatchId() == null) {
                return false;
            }
            MerchantSettlementBatch batch = batchMapper.selectById(settlement.getSettleBatchId());
            return batch != null && difference.getMerchantId().equals(batch.getMerchantId());
        }
        if (PLATFORM_SETTLEMENT_MISMATCH.equals(type)) {
            PlatformCollectionSettlement settlement = platformSettlementMapper.selectById(difference.getBizId());
            return settlement != null && platformSettlementConsistent(settlement);
        }
        return false;
    }

    private boolean platformSettlementConsistent(PlatformCollectionSettlement settlement) {
        if ("DIFFERENCE".equals(settlement.getSettlementStatus())) {
            return false;
        }
        if (settlement.getPaymentEventId() != null) {
            PaymentEvent event = eventMapper.selectById(settlement.getPaymentEventId());
            if (event == null || !ReconciliationClassifier.amountEquals(event.getPayAmount(),
                    settlement.getGrossAmount())) {
                return false;
            }
        }
        if (settlement.getSettleBatchId() != null) {
            MerchantSettlementBatch batch = batchMapper.selectById(settlement.getSettleBatchId());
            if (batch == null || !settlement.getMerchantId().equals(batch.getMerchantId())) {
                return false;
            }
        }
        if ("SETTLED".equals(settlement.getSettlementStatus())) {
            BigDecimal payable = settlement.getMerchantPayableAmount() == null
                    ? ZERO : settlement.getMerchantPayableAmount();
            BigDecimal refunded = settlement.getRefundedAmount() == null ? ZERO : settlement.getRefundedAmount();
            BigDecimal settled = settlement.getSettledAmount() == null ? ZERO : settlement.getSettledAmount();
            return ReconciliationClassifier.amountEquals(payable.subtract(refunded).setScale(2, RoundingMode.HALF_UP),
                    settled);
        }
        return true;
    }

    private ReconciliationDifference require(Long id) {
        ReconciliationDifference difference = differenceMapper.selectById(id);
        if (difference == null) {
            throw new BusinessException(404, "对账差异不存在");
        }
        return difference;
    }

    /**
     * 差异入队：键已存在时不覆盖已关闭记录；未关闭记录只刷新金额、分类与说明，不重复入队。
     */
    private void addDifference(String key, Long merchantId, String channel, String diffType, String bizType,
                               Long bizId, String orderSn, String tradeNo, BigDecimal expected,
                               BigDecimal actual, String detail, String category, LocalDate businessDate,
                               Long operatorId, Counts counts) {
        ReconciliationDifference existing = differenceMapper.findByKey(key).orElse(null);
        if (existing != null) {
            if ("RESOLVED".equals(existing.getStatus()) || "IGNORED".equals(existing.getStatus())) {
                counts.skipped++;
                return;
            }
            existing.setExpectedAmount(expected);
            existing.setActualAmount(actual);
            existing.setDetail(detail);
            existing.setDiffCategory(category);
            // 业务日一旦确定不再覆盖：跨天退款/相邻流水重跑时避免归属日来回翻转。
            if (existing.getBusinessDate() == null) {
                existing.setBusinessDate(businessDate);
            }
            differenceMapper.updateById(existing);
            counts.refreshed++;
            return;
        }
        ReconciliationDifference difference = new ReconciliationDifference();
        difference.setDifferenceKey(key);
        difference.setMerchantId(merchantId);
        difference.setChannel(channel);
        difference.setDiffType(diffType);
        difference.setDiffCategory(category);
        difference.setBizType(bizType);
        difference.setBizId(bizId);
        difference.setOrderSn(orderSn);
        difference.setChannelTradeNo(tradeNo);
        difference.setBusinessDate(businessDate);
        difference.setExpectedAmount(expected);
        difference.setActualAmount(actual);
        difference.setDetail(detail);
        difference.setStatus("DISPUTED");
        try {
            differenceMapper.insert(difference);
            counts.added++;
            audit(difference, "CREATE", null, "DISPUTED", operatorId, detail);
        } catch (DuplicateKeyException ignore) {
            // 并发对账已入队，本次不重复计数
            counts.skipped++;
        }
    }

    private void audit(ReconciliationDifference difference, String action, String fromStatus, String toStatus,
                       Long operatorId, String note) {
        ReconciliationDifferenceAudit entry = new ReconciliationDifferenceAudit();
        entry.setDifferenceId(difference.getId());
        entry.setAction(action);
        entry.setFromStatus(fromStatus);
        entry.setToStatus(toStatus);
        entry.setOperatorId(operatorId);
        entry.setNote(note);
        entry.setCreatedAt(LocalDateTime.now(BUSINESS_ZONE));
        auditMapper.insert(entry);
    }

    private String pairKey(String channel, String tradeNo) {
        return (channel == null ? "" : channel) + "|" + (tradeNo == null ? "" : tradeNo);
    }

    private String normalizeCurrency(String currency) {
        return currency == null || currency.isBlank() ? "CNY" : currency.trim().toUpperCase();
    }

    private String cell(String value) {
        return com.shop.b2b2c.common.CsvEscaper.escape(value);
    }

    private static final class Counts {
        private int added;
        private int refreshed;
        private int skipped;
        /** 未超悬挂阈值的 PROCESSING 笔数：只在跑批汇总可见，不逐笔入队。 */
        private int processingPending;
    }
}
