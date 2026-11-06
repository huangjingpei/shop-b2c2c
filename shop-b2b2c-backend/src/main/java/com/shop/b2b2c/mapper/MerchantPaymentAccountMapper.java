/*
 * 文件：MerchantPaymentAccountMapper.java
 * 用途：MerchantPaymentAccount领域的MyBatis基础持久化接口。
 * 维护注意：渠道账户基础查询；银行卡和进件字段应按权限读取，不可直接作为公开响应。 普通save不是并发控制，资金/库存更新须专用事务与条件SQL。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantPaymentAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Optional;

@Mapper
public interface MerchantPaymentAccountMapper extends BaseMapper<MerchantPaymentAccount> {

    default Optional<MerchantPaymentAccount> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default MerchantPaymentAccount save(MerchantPaymentAccount entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default Optional<MerchantPaymentAccount> findByMerchantIdAndChannel(Long merchantId, String channel) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<MerchantPaymentAccount>()
                .eq(MerchantPaymentAccount::getMerchantId, merchantId)
                .eq(MerchantPaymentAccount::getChannel, channel)));
    }

    default Optional<MerchantPaymentAccount> findByMerchantId(Long merchantId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<MerchantPaymentAccount>()
                .eq(MerchantPaymentAccount::getMerchantId, merchantId)));
    }

    /**
     * T40 阶段二：读取当前有效的分账接收方绑定，复用 T39 预留列；未绑定/未生效一律不返回。
     */
    @Select("SELECT * FROM merchant_payment_account WHERE merchant_id = #{merchantId} AND channel = #{channel} "
            + "AND binding_status = 'BOUND' AND channel_merchant_id IS NOT NULL "
            + "AND effective_from IS NOT NULL AND effective_from <= #{now} "
            + "AND (effective_to IS NULL OR effective_to >= #{now})")
    MerchantPaymentAccount findEffectiveBound(@Param("merchantId") Long merchantId,
                                             @Param("channel") String channel,
                                             @Param("now") LocalDateTime now);

    /**
     * T40 阶段二：仅允许从 UNBOUND/BINDING 推进绑定结果；调用方必须是真实渠道回调/查询结果。
     */
    @Update("UPDATE merchant_payment_account SET binding_status = #{bindingStatus}, "
            + "channel_merchant_id = #{channelMerchantId}, bound_at = #{boundAt}, "
            + "effective_from = #{effectiveFrom}, raw_result = #{rawResult} "
            + "WHERE id = #{id} AND binding_status IN ('UNBOUND', 'BINDING')")
    int advanceBinding(@Param("id") Long id, @Param("bindingStatus") String bindingStatus,
                       @Param("channelMerchantId") String channelMerchantId,
                       @Param("boundAt") LocalDateTime boundAt,
                       @Param("effectiveFrom") LocalDateTime effectiveFrom,
                       @Param("rawResult") String rawResult);
}
