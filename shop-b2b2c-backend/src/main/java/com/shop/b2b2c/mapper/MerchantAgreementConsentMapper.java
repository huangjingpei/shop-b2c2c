/*
 * 文件：MerchantAgreementConsentMapper.java
 * 用途：协议同意记录写入与按申请/店铺查询。
 * 维护注意：
 * 1. append-only：只提供 insert 与查询，禁止新增 UPDATE/DELETE 方法；证据不可变是开通闸门的合规前提。
 * 2. 重复签署同一 (application_id, protocol_type, protocol_version) 依赖 uk_consent_app_protocol，
 *    由服务层翻译为幂等成功，不产生重复行。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantAgreementConsent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MerchantAgreementConsentMapper extends BaseMapper<MerchantAgreementConsent> {

    @Select("SELECT * FROM merchant_agreement_consent WHERE application_id = #{applicationId} "
            + "ORDER BY agreed_at ASC, id ASC")
    List<MerchantAgreementConsent> findByApplicationId(@Param("applicationId") Long applicationId);

    @Select("SELECT COUNT(*) FROM merchant_agreement_consent WHERE application_id = #{applicationId} "
            + "AND protocol_type = #{protocolType} AND protocol_version = #{version}")
    int countByApplicationAndTypeAndVersion(@Param("applicationId") Long applicationId,
                                            @Param("protocolType") String protocolType,
                                            @Param("version") String version);
}
