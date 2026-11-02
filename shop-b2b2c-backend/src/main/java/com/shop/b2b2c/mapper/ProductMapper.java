package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.Product;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    default Optional<Product> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default Product save(Product entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default List<Product> findByStatusOrderBySortOrderAsc(Integer status) {
        return selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, status)
                .orderByAsc(Product::getSortOrder));
    }

    default List<Product> findByCategoryIdAndStatusOrderBySortOrderAsc(Long categoryId, Integer status) {
        return selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getCategoryId, categoryId)
                .eq(Product::getStatus, status)
                .orderByAsc(Product::getSortOrder));
    }

    default List<Product> findByMerchantId(Long merchantId) {
        return selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getMerchantId, merchantId));
    }

    default List<Product> findByMerchantIdAndStatus(Long merchantId, Integer status) {
        return selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getMerchantId, merchantId)
                .eq(Product::getStatus, status));
    }
}
