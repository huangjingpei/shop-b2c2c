/*
 * 文件：MerchantSettlementAccountMapper.java
 * 用途：收款账户登记、核验、默认账户切换与行锁查询。
 * 维护注意：
 * 1. lockById 使用 SELECT ... FOR UPDATE，核验/停用必须在锁内校验状态，防止并发重复核验。
 * 2. disableDefault 只停用当前默认的已验证账户（置 DISABLED 并记录失效时间），历史行保留可追溯。
 * 3. 查询不返回账号明文；接口展示使用 accountNoMasked。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantSettlementAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MerchantSettlementAccountMapper extends BaseMapper<MerchantSettlementAccount> {

    @Select("SELECT * FROM merchant_settlement_account WHERE id = #{id} FOR UPDATE")
    MerchantSettlementAccount lockById(@Param("id") Long id);

    @Select("SELECT * FROM merchant_settlement_account WHERE merchant_id = #{merchantId} "
            + "AND account_fingerprint = #{fingerprint} LIMIT 1")
    MerchantSettlementAccount findByMerchantIdAndFingerprint(@Param("merchantId") Long merchantId,
                                                             @Param("fingerprint") String fingerprint);

    @Select("SELECT * FROM merchant_settlement_account WHERE merchant_id = #{merchantId} "
            + "ORDER BY is_default DESC, id DESC")
    List<MerchantSettlementAccount> findByMerchantId(@Param("merchantId") Long merchantId);

    @Select("SELECT COUNT(*) FROM merchant_settlement_account WHERE merchant_id = #{merchantId}")
    int countByMerchantId(@Param("merchantId") Long merchantId);

    @Select("SELECT * FROM merchant_settlement_account WHERE merchant_id = #{merchantId} "
            + "AND status = 'VERIFIED' AND is_default = 1 ORDER BY id DESC LIMIT 1")
    MerchantSettlementAccount findDefaultVerified(@Param("merchantId") Long merchantId);

    @Update("UPDATE merchant_settlement_account SET is_default = 0, status = 'DISABLED', "
            + "effective_to = #{now}, updated_at = #{now}, version = version + 1 "
            + "WHERE merchant_id = #{merchantId} AND status = 'VERIFIED' AND is_default = 1 AND id <> #{exceptId}")
    int disableOtherDefaults(@Param("merchantId") Long merchantId, @Param("exceptId") Long exceptId,
                             @Param("now") LocalDateTime now);

    @Update("UPDATE merchant_settlement_account SET is_default = 0, updated_at = #{now}, version = version + 1 "
            + "WHERE id = #{id}")
    int clearDefault(@Param("id") Long id, @Param("now") LocalDateTime now);

    default MerchantSettlementAccount save(MerchantSettlementAccount entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }
}
