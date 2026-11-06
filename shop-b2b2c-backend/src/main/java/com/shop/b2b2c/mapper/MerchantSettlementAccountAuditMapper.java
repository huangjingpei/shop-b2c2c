/*
 * 文件：MerchantSettlementAccountAuditMapper.java
 * 用途：收款账户审计追加与按账户查询。
 * 维护注意：只插入不更新；查询按时间正序，便于还原“谁在何时核验/驳回/停用”。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantSettlementAccountAudit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MerchantSettlementAccountAuditMapper extends BaseMapper<MerchantSettlementAccountAudit> {

    @Select("SELECT * FROM merchant_settlement_account_audit WHERE account_id = #{accountId} "
            + "ORDER BY created_at ASC, id ASC")
    List<MerchantSettlementAccountAudit> findByAccountId(@Param("accountId") Long accountId);
}
