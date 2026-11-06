/*
 * 文件：FinanceApprovalGuard.java
 * 用途：财务双人复核开关（财务基线 18）统一判定：调价、结算确认、充值确认、开店初始费率共用同一口径。
 * 维护注意：
 *  1. 开关 shop.finance.dual-approval-required 由 FINANCE_DUAL_APPROVAL_REQUIRED 注入；开发/试点默认 false，生产部署脚本显式 true。
 *  2. 关闭时允许同人操作，但调用方必须把返回的 SAME_OPERATOR 持久化到业务记录，并已由本类写 WARN 审计日志。
 *  3. 打开时未选复核人返回 428（明确缺参，不再是泛化 403），同人返回 403；不同人始终放行并返回 DUAL_APPROVAL。
 *  4. 调用方必须通过 resolveReviewer 取得实际落库的复核人：未传时按操作人处理（仅开关关闭时允许）。
 *  5. 不得在业务层自建第二套“单管理员”判断；所有财务复核都必须经过本类。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FinanceApprovalGuard {

    public static final String MODE_SAME_OPERATOR = "SAME_OPERATOR";
    public static final String MODE_DUAL_APPROVAL = "DUAL_APPROVAL";

    /** 严格模式缺复核人的明确业务码；HTTP 由 GlobalExceptionHandler 映射为 428 Precondition Required。 */
    public static final int CODE_MISSING_REVIEWER = 428;

    private final boolean dualApprovalRequired;

    public FinanceApprovalGuard(
            @Value("${shop.finance.dual-approval-required:false}") boolean dualApprovalRequired) {
        this.dualApprovalRequired = dualApprovalRequired;
    }

    public boolean isDualApprovalRequired() {
        return dualApprovalRequired;
    }

    /**
     * 校验操作人与复核人关系，并返回应持久化的复核模式。
     *
     * @param action     业务动作，用于审计日志
     * @param operatorId 操作人/申请人/制单人
     * @param reviewerId 复核人/确认人
     * @return SAME_OPERATOR（仅开关关闭时）或 DUAL_APPROVAL
     */
    public String verify(String action, Long operatorId, Long reviewerId) {
        if (operatorId == null) {
            throw new BusinessException(400, "必须记录操作人");
        }
        if (reviewerId == null) {
            if (dualApprovalRequired) {
                // 生产严格模式：缺复核人是缺参，不是同人，必须返回可区分的业务码供前端提示。
                throw new BusinessException(CODE_MISSING_REVIEWER,
                        action + "：已开启双人复核，必须选择复核人（confirmedBy）");
            }
            log.warn("财务缺少复核人 action={} operatorId={} mode={} hint=未传按操作人处理，生产禁止单管理员模式",
                    action, operatorId, MODE_SAME_OPERATOR);
            return MODE_SAME_OPERATOR;
        }
        if (!operatorId.equals(reviewerId)) {
            return MODE_DUAL_APPROVAL;
        }
        if (dualApprovalRequired) {
            throw new BusinessException(403, action + "：操作人与复核人不能为同一人（已开启双人复核）");
        }
        log.warn("财务同人复核 action={} operatorId={} mode={} hint=生产环境禁止单管理员模式",
                action, operatorId, MODE_SAME_OPERATOR);
        return MODE_SAME_OPERATOR;
    }

    /**
     * 返回应持久化到业务记录的复核人：未传时按操作人处理。
     * 严格模式下未传会在 verify 阶段被拒绝，因此本方法只用于校验通过后的落库。
     */
    public Long resolveReviewer(Long operatorId, Long reviewerId) {
        return reviewerId == null ? operatorId : reviewerId;
    }
}
