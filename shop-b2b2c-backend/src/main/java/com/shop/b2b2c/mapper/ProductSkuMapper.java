package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.ProductSku;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSku> {

    default Optional<ProductSku> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default ProductSku save(ProductSku entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default List<ProductSku> findByProductId(Long productId) {
        return selectList(new LambdaQueryWrapper<ProductSku>()
                .eq(ProductSku::getProductId, productId));
    }

    default Optional<ProductSku> findBySkuCode(String skuCode) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<ProductSku>()
                .eq(ProductSku::getSkuCode, skuCode)));
    }
}
