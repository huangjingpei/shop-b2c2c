/*
 * 文件：MerchantFeeAuditMapper.java
 * 用途：费率政策审计追加与按商户查询。
 * 维护注意：只插入不修改；按创建时间正序还原调价时间线。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantFeeAudit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MerchantFeeAuditMapper extends BaseMapper<MerchantFeeAudit> {

    @Select("SELECT * FROM merchant_fee_audit WHERE merchant_id = #{merchantId} "
            + "ORDER BY created_at ASC, id ASC")
    List<MerchantFeeAudit> findByMerchantId(@Param("merchantId") Long merchantId);
}
