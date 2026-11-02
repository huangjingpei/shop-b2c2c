package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.ProductVirtualCard;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ProductVirtualCardMapper extends BaseMapper<ProductVirtualCard> {

    default Optional<ProductVirtualCard> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default ProductVirtualCard save(ProductVirtualCard entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default List<ProductVirtualCard> findBySkuIdAndStatus(Long skuId, Integer status) {
        return selectList(new LambdaQueryWrapper<ProductVirtualCard>()
                .eq(ProductVirtualCard::getSkuId, skuId)
                .eq(ProductVirtualCard::getStatus, status));
    }

    default List<ProductVirtualCard> findByMerchantId(Long merchantId) {
        return selectList(new LambdaQueryWrapper<ProductVirtualCard>()
                .eq(ProductVirtualCard::getMerchantId, merchantId));
    }

    default List<ProductVirtualCard> findByOrderSn(String orderSn) {
        return selectList(new LambdaQueryWrapper<ProductVirtualCard>()
                .eq(ProductVirtualCard::getOrderSn, orderSn));
    }

    default long countBySkuIdAndStatus(Long skuId, Integer status) {
        return selectCount(new LambdaQueryWrapper<ProductVirtualCard>()
                .eq(ProductVirtualCard::getSkuId, skuId)
                .eq(ProductVirtualCard::getStatus, status));
    }
}
