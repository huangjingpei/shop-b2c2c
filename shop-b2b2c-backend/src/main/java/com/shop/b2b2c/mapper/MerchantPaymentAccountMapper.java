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
}
