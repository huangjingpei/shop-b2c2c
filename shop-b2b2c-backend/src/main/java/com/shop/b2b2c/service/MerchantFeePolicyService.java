/*
 * 文件：MerchantFeePolicyService.java
 * 用途：技术服务费率政策的唯一权威来源（T39）：开店初始费率、后台单店调价、生效规则与审计。
 * 维护注意：
 * 1. 费率区间：NORMAL 100–1000 基点（1%–10%）；PROMOTION 固定 0 基点且必须记录推广原因，0% 不并入正常区间。
 * 2. 生效规则：降费/平迁即时生效；调高排到次日 00:00，按自然日切分。调价会关闭当前政策与未生效的未来政策，避免同日两套费率。
 * 3. 订单快照只允许读取本服务返回的政策；merchant_store.commission_rate 仅作展示镜像由本服务同步，禁止再作为计费来源。
 * 4. 每次调价写 merchant_fee_audit（前后类型/基点、原因、操作人、确认人、生效时间），不回溯已生成订单快照。
 *    调价人与复核人必须不同且都不能为空（财务基线 18）。
 * 5. 无政策时按存量 merchant_store.commission_rate 初始化一条 MIGRATION 政策，保证既有开发库可下单；
 *    生产应以入驻政策或后台配置为准，MIGRATION 只作开发期过渡，具体生产策略待用户确认。
 * 6. 默认费率 3.00%（300 基点）沿用既有默认值，具体默认数值仍待用户确认，不得表述为最终业务值。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantFeeAudit;
import com.shop.b2b2c.entity.MerchantFeePolicy;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.MerchantFeeAuditMapper;
import com.shop.b2b2c.mapper.MerchantFeePolicyMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MerchantFeePolicyService {

    public static final String RATE_NORMAL = "NORMAL";
    public static final String RATE_PROMOTION = "PROMOTION";

    public static final String SOURCE_ONBOARDING = "ONBOARDING";
    public static final String SOURCE_ADMIN_ADJUST = "ADMIN_ADJUST";
    public static final String SOURCE_PROMOTION = "PROMOTION";
    public static final String SOURCE_MIGRATION = "MIGRATION";

    /** 既有默认佣金费率；具体默认数值待用户确认。 */
    private static final int DEFAULT_RATE_BPS = 300;
    private static final int NORMAL_MIN_BPS = 100;
    private static final int NORMAL_MAX_BPS = 1000;

    private final MerchantFeePolicyMapper policyMapper;
    private final MerchantFeeAuditMapper auditMapper;
    private final MerchantStoreMapper storeMapper;

    @Data
    public static class AdjustForm {
        private String rateType;
        private Integer rateBps;
        private String promotionReason;
        private String reason;
        private Long confirmedBy;
    }

    /**
     * 订单创建/计费前必须调用：返回当前生效政策。无政策时按存量费率初始化，之后仅以政策为准。
     */
    @Transactional(rollbackFor = Exception.class)
    public MerchantFeePolicy getEffectivePolicy(Long merchantId) {
        LocalDateTime now = LocalDateTime.now();
        MerchantFeePolicy policy = policyMapper.findEffective(merchantId, now);
        if (policy != null) {
            return policy;
        }
        return bootstrapFromLegacyRate(merchantId, now);
    }

    /**
     * 开店审核通过时创建初始政策；0 费率必须携带推广原因。
     */
    @Transactional(rollbackFor = Exception.class)
    public MerchantFeePolicy createInitialPolicy(Long merchantId, Integer rateBps, String promotionReason,
                                                 String reason, Long operatorId) {
        int bps = rateBps == null ? DEFAULT_RATE_BPS : rateBps;
        String rateType = bps == 0 ? RATE_PROMOTION : RATE_NORMAL;
        validateRate(rateType, bps, promotionReason);

        MerchantFeePolicy policy = new MerchantFeePolicy();
        policy.setMerchantId(merchantId);
        policy.setRateType(rateType);
        policy.setRateBps(bps);
        policy.setPromotionReason(rateType.equals(RATE_PROMOTION) ? promotionReason.trim() : null);
        policy.setEffectiveFrom(LocalDateTime.now());
        policy.setSource(rateType.equals(RATE_PROMOTION) ? SOURCE_PROMOTION : SOURCE_ONBOARDING);
        policy.setReason(reason == null ? "开店初始费率" : reason.trim());
        policy.setOperatorId(operatorId);
        policy.setConfirmedBy(operatorId);
        policyMapper.insert(policy);

        writeAudit(policy.getMerchantId(), policy.getId(), null, null, rateType, bps,
                policy.getReason(), operatorId, policy.getConfirmedBy(), policy.getEffectiveFrom());
        syncStoreRate(merchantId, bps);
        return policy;
    }

    /**
     * 后台单店调价。rateType 未显式传入时按 rateBps=0 推断为 PROMOTION，否则为 NORMAL。
     */
    @Transactional(rollbackFor = Exception.class)
    public MerchantFeePolicy adjust(Long merchantId, AdjustForm form, Long operatorId) {
        if (form == null) {
            throw new BusinessException(400, "调价参数不能为空");
        }
        String rateType = form.getRateType();
        Integer bpsValue = form.getRateBps();
        if (rateType == null || rateType.isBlank()) {
            rateType = bpsValue != null && bpsValue == 0 ? RATE_PROMOTION : RATE_NORMAL;
        }
        rateType = rateType.trim().toUpperCase();
        if (bpsValue == null) {
            bpsValue = RATE_PROMOTION.equals(rateType) ? 0 : null;
        }
        validateRate(rateType, bpsValue, form.getPromotionReason());
        if (form.getReason() == null || form.getReason().isBlank()) {
            throw new BusinessException(400, "费率调整必须填写原因");
        }
        // 财务基线 18：调价人与复核人必须不同，且两字段都不能缺省。
        Long confirmedBy = form.getConfirmedBy();
        if (operatorId == null || confirmedBy == null) {
            throw new BusinessException(400, "费率调整必须记录操作人与复核人");
        }
        if (operatorId.equals(confirmedBy)) {
            throw new BusinessException(403, "调价人与复核人不能为同一人");
        }

        LocalDateTime now = LocalDateTime.now();
        MerchantFeePolicy current = getEffectivePolicy(merchantId);
        int newBps = bpsValue;
        if (current.getRateBps() != null && current.getRateBps() == newBps) {
            throw new BusinessException(409, "费率未变化，无需调整");
        }

        boolean increase = newBps > (current.getRateBps() == null ? 0 : current.getRateBps());
        LocalDateTime effectiveFrom = increase
                ? LocalDate.now().plusDays(1).atStartOfDay()
                : now;

        // 关闭当前政策；调高时当前政策持续到次日 00:00，降费时立即失效。
        current.setEffectiveTo(effectiveFrom);
        policyMapper.updateById(current);

        // 关闭尚未生效的自然日预约政策，避免同一自然日出现两套费率。
        for (MerchantFeePolicy scheduled : policyMapper.findScheduledAfter(merchantId, now)) {
            scheduled.setEffectiveTo(now);
            policyMapper.updateById(scheduled);
        }

        MerchantFeePolicy policy = new MerchantFeePolicy();
        policy.setMerchantId(merchantId);
        policy.setRateType(rateType);
        policy.setRateBps(newBps);
        policy.setPromotionReason(RATE_PROMOTION.equals(rateType) ? form.getPromotionReason().trim() : null);
        policy.setEffectiveFrom(effectiveFrom);
        policy.setSource(RATE_PROMOTION.equals(rateType) ? SOURCE_PROMOTION : SOURCE_ADMIN_ADJUST);
        policy.setReason(form.getReason().trim());
        policy.setOperatorId(operatorId);
        policy.setConfirmedBy(confirmedBy);
        policyMapper.insert(policy);

        writeAudit(merchantId, policy.getId(), current.getRateType(), current.getRateBps(),
                rateType, newBps, policy.getReason(), operatorId, confirmedBy, effectiveFrom);

        // 仅在即时生效时同步展示镜像；次日生效的调高由次日首单/查询触发，展示仍显示当前旧费率。
        if (!increase) {
            syncStoreRate(merchantId, newBps);
        }
        return policy;
    }

    public List<MerchantFeePolicy> list(Long merchantId) {
        return policyMapper.findByMerchantId(merchantId);
    }

    public List<MerchantFeeAudit> audits(Long merchantId) {
        return auditMapper.findByMerchantId(merchantId);
    }

    /** 将百分比（0 或 1–10）换算为整数基点；供旧佣金接口兼容调用。 */
    public int percentToBps(BigDecimal percent) {
        if (percent == null) {
            throw new BusinessException(400, "费率不能为空");
        }
        int bps = percent.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP).intValue();
        if (bps != 0 && (bps < NORMAL_MIN_BPS || bps > NORMAL_MAX_BPS)) {
            throw new BusinessException(400, "费率必须为 0%（推广期）或 1%~10%");
        }
        return bps;
    }

    private MerchantFeePolicy bootstrapFromLegacyRate(Long merchantId, LocalDateTime now) {
        MerchantStore store = storeMapper.findById(merchantId)
                .orElseThrow(() -> new BusinessException(404, "店铺不存在，无法确定费率"));
        BigDecimal percent = store.getCommissionRate() == null ? new BigDecimal("3.00") : store.getCommissionRate();
        int bps = percent.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP).intValue();
        String rateType = bps == 0 ? RATE_PROMOTION : RATE_NORMAL;
        validateRate(rateType, bps, rateType.equals(RATE_PROMOTION) ? "存量店铺原佣金为0，按推广费率初始化" : null);

        MerchantFeePolicy policy = new MerchantFeePolicy();
        policy.setMerchantId(merchantId);
        policy.setRateType(rateType);
        policy.setRateBps(bps);
        policy.setPromotionReason(rateType.equals(RATE_PROMOTION) ? "存量店铺原佣金为0，按推广费率初始化" : null);
        policy.setEffectiveFrom(now);
        policy.setSource(SOURCE_MIGRATION);
        policy.setReason("存量店铺按 merchant_store.commission_rate 初始化，后续以政策表为准");
        policyMapper.insert(policy);
        return policy;
    }

    private void validateRate(String rateType, Integer rateBps, String promotionReason) {
        if (!RATE_NORMAL.equals(rateType) && !RATE_PROMOTION.equals(rateType)) {
            throw new BusinessException(400, "费率类型仅支持 NORMAL/PROMOTION");
        }
        if (rateBps == null) {
            throw new BusinessException(400, "费率基点不能为空");
        }
        if (RATE_PROMOTION.equals(rateType)) {
            if (rateBps != 0) {
                throw new BusinessException(400, "推广费率基点必须为 0");
            }
            if (promotionReason == null || promotionReason.isBlank()) {
                throw new BusinessException(400, "0% 推广费率必须填写推广原因");
            }
            return;
        }
        if (rateBps < NORMAL_MIN_BPS || rateBps > NORMAL_MAX_BPS) {
            throw new BusinessException(400, "正常费率须在 100–1000 基点（1%–10%）");
        }
    }

    private void syncStoreRate(Long merchantId, int rateBps) {
        BigDecimal percent = BigDecimal.valueOf(rateBps)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        storeMapper.updateCommissionOnly(merchantId, percent);
    }

    private void writeAudit(Long merchantId, Long policyId, String fromType, Integer fromBps,
                            String toType, Integer toBps, String reason, Long operatorId, Long confirmedBy,
                            LocalDateTime effectiveFrom) {
        MerchantFeeAudit audit = new MerchantFeeAudit();
        audit.setMerchantId(merchantId);
        audit.setPolicyId(policyId);
        audit.setFromRateType(fromType);
        audit.setFromRateBps(fromBps);
        audit.setToRateType(toType);
        audit.setToRateBps(toBps);
        audit.setReason(reason);
        audit.setOperatorId(operatorId);
        audit.setConfirmedBy(confirmedBy);
        audit.setEffectiveFrom(effectiveFrom);
        auditMapper.insert(audit);
    }
}
