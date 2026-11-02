/*
 * 文件：MerchantStoreMapper.java
 * 用途：MerchantStore领域的MyBatis基础持久化接口。
 * 维护注意：店铺基础查询；普通商家必须限定会话店铺，公开展示使用白名单，状态变更影响交易资格。 普通save不是并发控制，资金/库存更新须专用事务与条件SQL。
 */
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
