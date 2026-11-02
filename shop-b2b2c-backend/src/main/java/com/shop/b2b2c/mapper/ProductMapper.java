/*
 * 文件：ProductMapper.java
 * 用途：Product领域的MyBatis基础持久化接口。
 * 维护注意：商品基础筛选；公开读取须校验在售与营业状态，组合过滤/分页不能仅依赖方法名推断已支持。 普通save不是并发控制，资金/库存更新须专用事务与条件SQL。
 */
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
