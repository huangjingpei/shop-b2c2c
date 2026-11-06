/*
 * 文件：MerchantOnboardingApplicationMapper.java
 * 用途：入驻申请持久化与状态机所需的行锁、主体去重、按店铺查询已审核申请。
 * 维护注意：
 * 1. lockById 使用 SELECT ... FOR UPDATE，审核/补件/提交必须经此获取最新状态后再判定，避免并发覆盖；
 * 2. findActiveByFingerprint 只统计未终结申请（DRAFT/SUBMITTED/REVIEWING/SUPPLEMENT_REQUIRED/APPROVED），
 *    已驳回或撤回的主体允许重新申请，因此不使用数据库唯一索引；
 * 3. 查询不返回并展示证件密文以外的明文，密文解密只在服务层按需做脱敏展示。
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
            + "AND status IN ('DRAFT','SUBMITTED','REVIEWING','SUPPLEMENT_REQUIRED','APPROVED')")
    int countActiveByFingerprint(@Param("subjectType") String subjectType,
                                 @Param("fingerprint") String fingerprint,
                                 @Param("excludeId") Long excludeId);

    @Select("SELECT * FROM merchant_onboarding_application "
            + "WHERE merchant_id = #{merchantId} AND status = 'APPROVED' "
            + "ORDER BY reviewed_at DESC, id DESC LIMIT 1")
    MerchantOnboardingApplication findApprovedByMerchantId(@Param("merchantId") Long merchantId);
}
