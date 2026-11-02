package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantStore;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface MerchantStoreMapper extends BaseMapper<MerchantStore> {

    default Optional<MerchantStore> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default MerchantStore save(MerchantStore entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default Optional<MerchantStore> findByStoreCode(String storeCode) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<MerchantStore>()
                .eq(MerchantStore::getStoreCode, storeCode)));
    }
}
