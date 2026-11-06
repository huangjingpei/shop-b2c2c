/*
 * 文件：MerchantFeePolicyServiceTest.java
 * 用途：T39 费率政策区间、推广 0 费率、降费即时/调高次日、审计前后值与存量迁移的契约用例。
 * 维护注意：
 *  1. 使用 Mockito 隔离，不启动 Spring、不连接数据库；时间断言基于本机自然日，跨零点运行属正常边界。
 *  2. 0% 必须走 PROMOTION 独立类型并携带推广原因，不得并入 1%~10% 正常区间。
 *  3. 调高必须排到次日 00:00，降费即时；若实现改回即时调高，本用例必须失败。
 *  4. 真实 MySQL 生效区间唯一性与并发行锁仍由用户手动验证。
 *  5. G31 起严格模式缺复核人返回 428（缺参），同人返回 403；宽松模式未传按操作人处理并落 SAME_OPERATOR。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantFeeAudit;
import com.shop.b2b2c.entity.MerchantFeePolicy;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.SysUser;
import com.shop.b2b2c.mapper.MerchantFeeAuditMapper;
import com.shop.b2b2c.mapper.MerchantFeePolicyMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.SysUserMapper;
import com.shop.b2b2c.service.FinanceApprovalGuard;
import com.shop.b2b2c.service.MerchantFeePolicyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantFeePolicyServiceTest {

    private final MerchantFeePolicyMapper policyMapper = mock(MerchantFeePolicyMapper.class);
    private final MerchantFeeAuditMapper auditMapper = mock(MerchantFeeAuditMapper.class);
    private final MerchantStoreMapper storeMapper = mock(MerchantStoreMapper.class);
    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final MerchantFeePolicyService service = new MerchantFeePolicyService(
            policyMapper, auditMapper, storeMapper, guard(false));

    /** 用例里的复核人 8L 统一按“启用 SUPERADMIN”返回，聚焦费率规则本身。 */
    private FinanceApprovalGuard guard(boolean dualApprovalRequired) {
        SysUser reviewer = new SysUser();
        reviewer.setId(8L);
        reviewer.setRoleCode("SUPERADMIN");
        reviewer.setIsActive(true);
        when(userMapper.selectById(8L)).thenReturn(reviewer);
        return new FinanceApprovalGuard(dualApprovalRequired, userMapper);
    }

    private MerchantFeePolicyService strictService() {
        return new MerchantFeePolicyService(policyMapper, auditMapper, storeMapper,
                guard(true));
    }

    @Test
    void initialNormalPolicyIsCreatedAndMirrorsStoreRate() {
        when(policyMapper.insert(any())).thenAnswer(invocation -> {
            MerchantFeePolicy policy = invocation.getArgument(0);
            policy.setId(42L);
            return 1;
        });

        MerchantFeePolicy policy = service.createInitialPolicy(1L, 300, null, "开店初始费率", 7L);

        assertEquals("NORMAL", policy.getRateType());
        assertEquals(300, policy.getRateBps());
        assertEquals("ONBOARDING", policy.getSource());
        // 开关关闭时允许单管理员同人复核，但必须落 SAME_OPERATOR 标记。
        assertEquals(FinanceApprovalGuard.MODE_SAME_OPERATOR, policy.getApprovalMode());
        verify(auditMapper).insert(any(MerchantFeeAudit.class));
        verify(storeMapper).updateCommissionOnly(1L, new BigDecimal("3.00"));
    }

    @Test
    void zeroRateRequiresPromotionTypeAndReason() {
        when(policyMapper.insert(any())).thenAnswer(invocation -> {
            MerchantFeePolicy policy = invocation.getArgument(0);
            policy.setId(43L);
            return 1;
        });

        MerchantFeePolicy promotion = service.createInitialPolicy(1L, 0, "开业三个月推广", "推广期", 7L);
        assertEquals("PROMOTION", promotion.getRateType());
        assertEquals(0, promotion.getRateBps());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInitialPolicy(1L, 0, " ", "推广期", 7L));
        assertEquals(400, ex.getCode());

        BusinessException normalZero = assertThrows(BusinessException.class,
                () -> service.createInitialPolicy(1L, 0, null, "普通费率", 7L));
        assertEquals(400, normalZero.getCode());
    }

    @Test
    void decreaseTakesEffectImmediately() {
        MerchantFeePolicy current = currentPolicy(1L, 500, "NORMAL");
        when(policyMapper.findEffective(eq(1L), any())).thenReturn(current);
        when(policyMapper.findScheduledAfter(eq(1L), any())).thenReturn(List.of());
        when(policyMapper.insert(any())).thenAnswer(invocation -> {
            MerchantFeePolicy policy = invocation.getArgument(0);
            policy.setId(44L);
            return 1;
        });

        MerchantFeePolicyService.AdjustForm form = adjustForm("NORMAL", 300, null, "下调费率");
        LocalDateTime before = LocalDateTime.now();
        MerchantFeePolicy policy = service.adjust(1L, form, 7L);

        assertEquals(300, policy.getRateBps());
        assertTrue(!policy.getEffectiveFrom().isAfter(LocalDateTime.now().plusSeconds(1)));
        assertTrue(!policy.getEffectiveFrom().isBefore(before.minusSeconds(1)));
        assertEquals(policy.getEffectiveFrom(), current.getEffectiveTo());
        verify(storeMapper).updateCommissionOnly(1L, new BigDecimal("3.00"));

        ArgumentCaptor<MerchantFeeAudit> audit = ArgumentCaptor.forClass(MerchantFeeAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals(500, audit.getValue().getFromRateBps());
        assertEquals(300, audit.getValue().getToRateBps());
        assertEquals("下调费率", audit.getValue().getReason());
    }

    @Test
    void increaseSchedulesNextNaturalDayMidnight() {
        MerchantFeePolicy current = currentPolicy(1L, 300, "NORMAL");
        when(policyMapper.findEffective(eq(1L), any())).thenReturn(current);
        when(policyMapper.findScheduledAfter(eq(1L), any())).thenReturn(List.of());
        when(policyMapper.insert(any())).thenAnswer(invocation -> {
            MerchantFeePolicy policy = invocation.getArgument(0);
            policy.setId(45L);
            return 1;
        });

        MerchantFeePolicyService.AdjustForm form = adjustForm("NORMAL", 500, null, "上调费率");
        MerchantFeePolicy policy = service.adjust(1L, form, 7L);

        assertEquals(LocalDate.now().plusDays(1).atStartOfDay(), policy.getEffectiveFrom());
        assertEquals(policy.getEffectiveFrom(), current.getEffectiveTo());
        // 调高未即时生效，展示镜像仍保持旧费率。
        verify(storeMapper, never()).updateCommissionOnly(any(), any());
    }

    @Test
    void promotionAdjustmentIsImmediateAndKeepsIndependentType() {
        MerchantFeePolicy current = currentPolicy(1L, 300, "NORMAL");
        when(policyMapper.findEffective(eq(1L), any())).thenReturn(current);
        when(policyMapper.findScheduledAfter(eq(1L), any())).thenReturn(List.of());
        when(policyMapper.insert(any())).thenAnswer(invocation -> {
            MerchantFeePolicy policy = invocation.getArgument(0);
            policy.setId(46L);
            return 1;
        });

        MerchantFeePolicyService.AdjustForm form =
                adjustForm(null, 0, "双十一推广", "设为推广费率");
        MerchantFeePolicy policy = service.adjust(1L, form, 7L);

        assertEquals("PROMOTION", policy.getRateType());
        assertEquals(0, policy.getRateBps());
        assertEquals("双十一推广", policy.getPromotionReason());
        verify(storeMapper).updateCommissionOnly(1L, new BigDecimal("0.00"));
    }

    @Test
    void invalidOrUnchangedRateIsRejected() {
        MerchantFeePolicy current = currentPolicy(1L, 300, "NORMAL");
        when(policyMapper.findEffective(eq(1L), any())).thenReturn(current);

        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.adjust(1L, adjustForm("NORMAL", 99, null, "越界"), 7L)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.adjust(1L, adjustForm("NORMAL", 1001, null, "越界"), 7L)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.adjust(1L, adjustForm("NORMAL", 300, null, " "), 7L)).getCode());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.adjust(1L, adjustForm("NORMAL", 300, null, "无变化"), 7L)).getCode());
        verify(policyMapper, never()).insert(any());
    }

    @Test
    void adjustmentRequiresDistinctOperatorAndReviewerUnderStrictMode() {
        MerchantFeePolicy current = currentPolicy(1L, 300, "NORMAL");
        when(policyMapper.findEffective(eq(1L), any())).thenReturn(current);
        MerchantFeePolicyService strict = strictService();

        MerchantFeePolicyService.AdjustForm samePerson = adjustForm("NORMAL", 500, null, "上调费率");
        samePerson.setConfirmedBy(7L);
        assertEquals(403, assertThrows(BusinessException.class,
                () -> strict.adjust(1L, samePerson, 7L)).getCode());

        // 未传复核人属于缺参：严格模式必须返回明确业务码 428，而不是泛化 403。
        MerchantFeePolicyService.AdjustForm missingReviewer = adjustForm("NORMAL", 500, null, "上调费率");
        missingReviewer.setConfirmedBy(null);
        BusinessException missing = assertThrows(BusinessException.class,
                () -> strict.adjust(1L, missingReviewer, 7L));
        assertEquals(FinanceApprovalGuard.CODE_MISSING_REVIEWER, missing.getCode());
        assertTrue(missing.getMessage().contains("必须选择复核人"));

        // 显式选择其他管理员必须放行并标记 DUAL_APPROVAL。
        when(policyMapper.findScheduledAfter(eq(1L), any())).thenReturn(List.of());
        when(policyMapper.insert(any())).thenAnswer(invocation -> {
            MerchantFeePolicy policy = invocation.getArgument(0);
            policy.setId(47L);
            return 1;
        });
        MerchantFeePolicyService.AdjustForm otherReviewer = adjustForm("NORMAL", 500, null, "上调费率");
        otherReviewer.setConfirmedBy(8L);
        MerchantFeePolicy approved = strict.adjust(1L, otherReviewer, 7L);
        assertEquals(FinanceApprovalGuard.MODE_DUAL_APPROVAL, approved.getApprovalMode());
        assertEquals(8L, approved.getConfirmedBy());
    }

    @Test
    void sameOperatorAdjustmentAllowedWhenDualApprovalDisabled() {
        MerchantFeePolicy current = currentPolicy(1L, 500, "NORMAL");
        when(policyMapper.findEffective(eq(1L), any())).thenReturn(current);
        when(policyMapper.findScheduledAfter(eq(1L), any())).thenReturn(List.of());
        when(policyMapper.insert(any())).thenAnswer(invocation -> {
            MerchantFeePolicy policy = invocation.getArgument(0);
            policy.setId(48L);
            return 1;
        });

        MerchantFeePolicyService.AdjustForm form = adjustForm("NORMAL", 300, null, "下调费率");
        form.setConfirmedBy(7L);
        MerchantFeePolicy policy = service.adjust(1L, form, 7L);

        assertEquals(FinanceApprovalGuard.MODE_SAME_OPERATOR, policy.getApprovalMode());
        ArgumentCaptor<MerchantFeeAudit> audit = ArgumentCaptor.forClass(MerchantFeeAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals(FinanceApprovalGuard.MODE_SAME_OPERATOR, audit.getValue().getApprovalMode());
    }

    @Test
    void omittedReviewerFollowsOperatorWhenDualApprovalDisabled() {
        // 后台佣金弹窗只传费率时未带复核人；开关关闭必须按同人复核放行并落标记，而不是 400。
        MerchantFeePolicy current = currentPolicy(1L, 500, "NORMAL");
        when(policyMapper.findEffective(eq(1L), any())).thenReturn(current);
        when(policyMapper.findScheduledAfter(eq(1L), any())).thenReturn(List.of());
        when(policyMapper.insert(any())).thenAnswer(invocation -> {
            MerchantFeePolicy policy = invocation.getArgument(0);
            policy.setId(50L);
            return 1;
        });

        MerchantFeePolicyService.AdjustForm form = adjustForm("NORMAL", 300, null, "下调费率");
        form.setConfirmedBy(null);
        MerchantFeePolicy policy = service.adjust(1L, form, 7L);

        assertEquals(7L, policy.getConfirmedBy(), "复核人缺省时应按操作人记录");
        assertEquals(FinanceApprovalGuard.MODE_SAME_OPERATOR, policy.getApprovalMode());
    }

    @Test
    void strictModeInitialPolicyRequiresDistinctReviewer() {
        when(policyMapper.insert(any())).thenAnswer(invocation -> {
            MerchantFeePolicy policy = invocation.getArgument(0);
            policy.setId(49L);
            return 1;
        });

        // 5 参入口未显式传复核人：严格模式按缺参返回 428。
        BusinessException missing = assertThrows(BusinessException.class,
                () -> strictService().createInitialPolicy(1L, 300, null, "开店初始费率", 7L));
        assertEquals(FinanceApprovalGuard.CODE_MISSING_REVIEWER, missing.getCode());

        // 显式传同一操作人：属于同人复核，仍返回 403。
        assertEquals(403, assertThrows(BusinessException.class,
                () -> strictService().createInitialPolicy(1L, 300, null, "开店初始费率", 7L, 7L)).getCode());

        MerchantFeePolicy policy = strictService().createInitialPolicy(1L, 300, null, "开店初始费率", 7L, 8L);
        assertEquals(FinanceApprovalGuard.MODE_DUAL_APPROVAL, policy.getApprovalMode());
        assertEquals(8L, policy.getConfirmedBy());
    }

    @Test
    void missingPolicyBootstrapsFromLegacyCommissionRate() {
        MerchantStore store = new MerchantStore();
        store.setCommissionRate(new BigDecimal("3.00"));
        when(policyMapper.findEffective(eq(1L), any())).thenReturn(null);
        when(storeMapper.findById(1L)).thenReturn(Optional.of(store));
        when(policyMapper.insert(any())).thenAnswer(invocation -> {
            MerchantFeePolicy policy = invocation.getArgument(0);
            policy.setId(47L);
            return 1;
        });

        MerchantFeePolicy policy = service.getEffectivePolicy(1L);

        assertEquals(300, policy.getRateBps());
        assertEquals("NORMAL", policy.getRateType());
        assertEquals("MIGRATION", policy.getSource());
        assertNotNull(policy.getEffectiveFrom());
    }

    @Test
    void legacyRateOutsideRangeIsRejectedInsteadOfSilentlyCoerced() {
        MerchantStore store = new MerchantStore();
        store.setCommissionRate(new BigDecimal("11.00"));
        when(policyMapper.findEffective(eq(1L), any())).thenReturn(null);
        when(storeMapper.findById(1L)).thenReturn(Optional.of(store));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getEffectivePolicy(1L));

        assertEquals(400, ex.getCode());
        verify(policyMapper, never()).insert(any());
    }

    private MerchantFeePolicy currentPolicy(Long merchantId, int rateBps, String rateType) {
        MerchantFeePolicy policy = new MerchantFeePolicy();
        policy.setId(10L);
        policy.setMerchantId(merchantId);
        policy.setRateType(rateType);
        policy.setRateBps(rateBps);
        policy.setEffectiveFrom(LocalDateTime.now().minusDays(1));
        return policy;
    }

    private MerchantFeePolicyService.AdjustForm adjustForm(String rateType, Integer rateBps,
                                                           String promotionReason, String reason) {
        MerchantFeePolicyService.AdjustForm form = new MerchantFeePolicyService.AdjustForm();
        form.setRateType(rateType);
        form.setRateBps(rateBps);
        form.setPromotionReason(promotionReason);
        form.setReason(reason);
        form.setConfirmedBy(8L);
        return form;
    }
}
