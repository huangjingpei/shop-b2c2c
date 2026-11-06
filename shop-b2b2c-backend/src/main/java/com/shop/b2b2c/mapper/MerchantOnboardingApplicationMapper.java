/*
 * 文件：MerchantOnboardingApplicationMapper.java
 * 用途：入驻申请持久化与状态机所需的行锁、主体去重、按店铺查询已审核申请。
 * 维护注意：
 * 1. lockById 使用 SELECT ... FOR UPDATE，审核/补件/提交必须经此获取最新状态后再判定，避免并发覆盖；
 * 2. countActiveByFingerprint 统计未终结申请（DRAFT/SUBMITTED/REVIEWING/SUPPLEMENT_REQUIRED/APPROVED/OPENED），
 *    已驳回或撤回的主体允许重新申请；OPENED 必须计入，否则同一主体可在开通后再开第二家店；
 * 3. findApprovedByMerchantId 必须同时覆盖 APPROVED 与 OPENED：开通后登记收款账户、查询初始费率都依赖它；
 * 4. 查询不返回并展示证件密文以外的明文，密文解密只在服务层按需做脱敏展示。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MerchantOnboardingApplicationMapper extends BaseMapper<MerchantOnboardingApplication> {

    @Select("SELECT * FROM merchant_onboarding_application WHERE id = #{id} FOR UPDATE")
    MerchantOnboardingApplication lockById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM merchant_onboarding_application "
            + "WHERE subject_type = #{subjectType} AND id_card_fingerprint = #{fingerprint} "
            + "AND id <> COALESCE(#{excludeId}, -1) "
            + "AND status IN ('DRAFT','SUBMITTED','REVIEWING','SUPPLEMENT_REQUIRED','APPROVED','OPENED')")
    int countActiveByFingerprint(@Param("subjectType") String subjectType,
                                 @Param("fingerprint") String fingerprint,
                                 @Param("excludeId") Long excludeId);

    @Select("SELECT * FROM merchant_onboarding_application "
            + "WHERE merchant_id = #{merchantId} AND status IN ('APPROVED','OPENED') "
            + "ORDER BY reviewed_at DESC, id DESC LIMIT 1")
    MerchantOnboardingApplication findApprovedByMerchantId(@Param("merchantId") Long merchantId);
}
