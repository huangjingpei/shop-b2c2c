/*
 * 文件：MerchantStoreStatusService.java
 * 用途：O6 店铺经营状态机：打烊/恢复/冻结/解封/关店/注销的统一入口，全部动作 CAS 更新 + 审计同事务写入。
 * 维护注意：
 * 1. 状态语义：0-待开通 1-营业 2-打烊 3-冻结 4-已注销(终态)；已注销不可迁移到任何状态（4.4 终态）。
 *    合法迁移：PAUSE 1->2；RESUME 2->1；FREEZE 1/2->3；UNFREEZE 3->1；CLOSE/CANCEL 1/2->4。
 *    OPEN（0->1）由 MerchantOnboardingService 开通闸门负责，不在本类处理。
 * 2. 并发只成功一次：行锁加载 + updateStatusCas 条件更新，影响 0 行一律 409，禁止静默成功。
 * 3. 冻结/解封/关店/注销必须带原因码（STORE_REASON_CODES，附录 A.5），OTHER 时 reason 必填；
 *    解封必须双人复核（FinanceApprovalGuard 统一开关，缺复核人严格模式 428、同人 403），
 *    关店/注销传 confirmedBy 时同样强制换人复核。
 * 4. 关店前置条件（任一不满足 409，错误信息带计数）：无未完结订单(PAID/SHIPPED)、无处理中退款、
 *    无 DRAFT 批次且无欠费、无在售商品；通过后同事务停用店铺全部商家账号（只停不删）。
 * 5. 冻结停新交易由 TradeGuards.requirePurchasable/MerchantStoreGuard 的 status==1 口径天然生效；
 *    结算 HOLD 由 SettlementService.confirmBatch 对冻结店铺 409 实现；退款/售后不受本状态机阻断。
 * 6. 系统任务（如 O9 资质过期自动冻结）operatorId 传 null，审计留痕为系统动作。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.ManagementGuards;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.MerchantStoreStatusAudit;
import com.shop.b2b2c.mapper.MerchantSettlementBatchMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.MerchantStoreStatusAuditMapper;
import com.shop.b2b2c.mapper.ProductMapper;
import com.shop.b2b2c.mapper.SysUserMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantStoreStatusService {

    public static final String ACTION_PAUSE = "PAUSE";
    public static final String ACTION_RESUME = "RESUME";
    public static final String ACTION_FREEZE = "FREEZE";
    public static final String ACTION_UNFREEZE = "UNFREEZE";
    public static final String ACTION_CLOSE = "CLOSE";
    public static final String ACTION_CANCEL = "CANCEL";

    /** 冻结/解封/关店/注销原因码字典（开发文档附录 A.5）；新增原因码必须先更新文档。 */
    public static final Set<String> STORE_REASON_CODES = Set.of(
            "VIOLATION", "RISK_COMPLAINT", "ARREARS", "EXPIRED_LICENSE", "MERCHANT_REQUEST",
            "QUALIFICATION_EXPIRED", "STRATEGY_ADJUST", "ILLEGAL_OPERATION", "REPEATED_VIOLATION",
            "SUSPECTED_RISK", "OTHER");
    public static final String REASON_OTHER = "OTHER";
    /** O9 资质过期超过 30 天自动冻结的系统原因码。 */
    public static final String REASON_EXPIRED_LICENSE = "EXPIRED_LICENSE";

    private static final Set<String> ACTIONS = Set.of(
            ACTION_PAUSE, ACTION_RESUME, ACTION_FREEZE, ACTION_UNFREEZE, ACTION_CLOSE, ACTION_CANCEL);

    private static final int STATUS_PENDING_OPEN = 0;
    private static final int STATUS_OPEN = 1;
    private static final int STATUS_PAUSED = 2;
    private static final int STATUS_FROZEN = 3;
    private static final int STATUS_CANCELLED = 4;

    private final MerchantStoreMapper storeMapper;
    private final MerchantStoreStatusAuditMapper statusAuditMapper;
    private final TradeOrderMapper orderMapper;
    private final TradeRefundMapper refundMapper;
    private final MerchantSettlementBatchMapper batchMapper;
    private final ProductMapper productMapper;
    private final SysUserMapper userMapper;
    /** 双人复核统一开关与复核人校验；解封（及带复核人的关店/注销）经它收口，不得各写一套。 */
    private final FinanceApprovalGuard approvalGuard;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> applyAction(Long merchantId, String action, String reasonCode, String reason,
                                           Long operatorId, Long confirmedBy) {
        if (action == null || !ACTIONS.contains(action)) {
            throw new BusinessException(400, "不支持的店铺状态操作: " + action);
        }
        MerchantStore store = storeMapper.lockById(merchantId);
        if (store == null) {
            throw new BusinessException(404, "店铺不存在");
        }
        int from = store.getStatus() == null ? STATUS_PENDING_OPEN : store.getStatus();
        if (from == STATUS_CANCELLED) {
            throw new BusinessException(409, "店铺已注销（终态），不可再变更状态");
        }

        String normalizedReasonCode = (reasonCode == null || reasonCode.isBlank()) ? null : reasonCode.trim();
        String normalizedReason = (reason == null || reason.isBlank()) ? null : reason.trim();
        ManagementGuards.text(normalizedReason, 512, false);

        Long reviewerId = null;
        String approvalMode = null;
        int to;
        List<Integer> expected;
        switch (action) {
            case ACTION_PAUSE -> {
                expected = List.of(STATUS_OPEN);
                to = STATUS_PAUSED;
            }
            case ACTION_RESUME -> {
                expected = List.of(STATUS_PAUSED);
                to = STATUS_OPEN;
            }
            case ACTION_FREEZE -> {
                requireReasonCode(normalizedReasonCode, normalizedReason, action);
                expected = List.of(STATUS_OPEN, STATUS_PAUSED);
                to = STATUS_FROZEN;
            }
            case ACTION_UNFREEZE -> {
                requireReasonCode(normalizedReasonCode, normalizedReason, action);
                // 解封强制双人复核：严格模式缺复核人 428、同人 403；宽松模式落 SAME_OPERATOR 并 WARN。
                approvalMode = approvalGuard.verify("店铺解封", operatorId, confirmedBy);
                reviewerId = approvalGuard.resolveReviewer(operatorId, confirmedBy);
                expected = List.of(STATUS_FROZEN);
                to = STATUS_OPEN;
            }
            case ACTION_CLOSE, ACTION_CANCEL -> {
                requireReasonCode(normalizedReasonCode, normalizedReason, action);
                if (confirmedBy != null) {
                    // 关店/注销建议双人：传入复核人即强制换人校验；不传不强制（与开发文档矩阵一致）。
                    approvalMode = approvalGuard.verify("店铺注销", operatorId, confirmedBy);
                    reviewerId = approvalGuard.resolveReviewer(operatorId, confirmedBy);
                }
                assertClosePreconditions(merchantId);
                expected = List.of(STATUS_OPEN, STATUS_PAUSED);
                to = STATUS_CANCELLED;
            }
            default -> throw new BusinessException(400, "不支持的店铺状态操作: " + action);
        }

        int updated = storeMapper.updateStatusCas(merchantId, expected, to);
        if (updated == 0) {
            throw new BusinessException(409, "店铺状态已变化，操作冲突，请刷新后重试");
        }
        if (ACTION_CLOSE.equals(action) || ACTION_CANCEL.equals(action)) {
            int disabled = userMapper.disableByMerchantId(merchantId);
            log.info("店铺注销停用商家账号 merchantId={} disabledAccounts={}", merchantId, disabled);
        }

        MerchantStoreStatusAudit audit = new MerchantStoreStatusAudit();
        audit.setMerchantId(merchantId);
        audit.setAction(action);
        audit.setFromStatus(from);
        audit.setToStatus(to);
        audit.setReasonCode(normalizedReasonCode == null ? REASON_OTHER : normalizedReasonCode);
        audit.setReason(normalizedReason);
        audit.setOperatorId(operatorId);
        audit.setConfirmedBy(reviewerId);
        audit.setApprovalMode(approvalMode);
        statusAuditMapper.insert(audit);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("merchantId", merchantId);
        view.put("action", action);
        view.put("fromStatus", from);
        view.put("toStatus", to);
        view.put("reasonCode", audit.getReasonCode());
        view.put("approvalMode", approvalMode);
        return view;
    }

    /** 冻结/解封/关店/注销必须带字典内原因码；OTHER 时必须写原因。 */
    private void requireReasonCode(String reasonCode, String reason, String action) {
        if (reasonCode == null || !STORE_REASON_CODES.contains(reasonCode)) {
            throw new BusinessException(400, action + " 必须携带合法原因码（" + STORE_REASON_CODES + "）");
        }
        if (REASON_OTHER.equals(reasonCode) && (reason == null || reason.isBlank())) {
            throw new BusinessException(400, "原因码为 OTHER 时必须填写具体原因");
        }
    }

    /** 关店前置条件：未完结订单/处理中退款/未确认批次或欠费/在售商品，任一存在即拒绝并给出计数。 */
    private void assertClosePreconditions(Long merchantId) {
        int ongoingOrders = orderMapper.countOngoingByMerchantId(merchantId);
        if (ongoingOrders > 0) {
            throw new BusinessException(409, "存在未完结订单（" + ongoingOrders + " 笔），完成或关闭订单后才能关店");
        }
        int processingRefunds = refundMapper.countProcessingByMerchantId(merchantId);
        if (processingRefunds > 0) {
            throw new BusinessException(409, "存在处理中退款（" + processingRefunds + " 笔），退款完结后才能关店");
        }
        int blockingBatches = batchMapper.countBlockingByMerchantId(merchantId);
        if (blockingBatches > 0) {
            throw new BusinessException(409, "存在未确认结算批次或未结清欠费（" + blockingBatches + " 条），结清后才能关店");
        }
        long onShelf = productMapper.countByMerchantIdAndStatus(merchantId, 1);
        if (onShelf > 0) {
            throw new BusinessException(409, "存在在售商品（" + onShelf + " 件），全部下架后才能关店");
        }
    }
}
