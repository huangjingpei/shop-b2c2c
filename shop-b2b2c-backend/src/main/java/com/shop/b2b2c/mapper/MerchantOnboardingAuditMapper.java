/*
 * 文件：MerchantOnboardingAuditMapper.java
 * 用途：入驻审核审计的追加与按申请查询。
 * 维护注意：审计表只插入不更新；查询按创建时间正序，便于还原审核时间线；不得在审计中保存证件/账号明文。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantOnboardingAudit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MerchantOnboardingAuditMapper extends BaseMapper<MerchantOnboardingAudit> {

    @Select("SELECT * FROM merchant_onboarding_audit WHERE application_id = #{applicationId} ORDER BY created_at ASC, id ASC")
    List<MerchantOnboardingAudit> findByApplicationId(@Param("applicationId") Long applicationId);
}
