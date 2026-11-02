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
