/*
 * 文件：ProductSkuMapper.java
 * 用途：SKU查询及带商品/商户归属条件的原子库存增减。
 * 维护注意：扣库存必须检查影响行数，不能用读改写save替代deductStock。stockQuantity是扣除预占后的可售数量；增减与对应预占/发卡操作必须在同一事务内。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.ProductSku;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSku> {

    @org.apache.ibatis.annotations.Update("UPDATE product_sku SET stock_quantity = stock_quantity - #{quantity} "
            + "WHERE id = #{id} AND product_id = #{productId} AND merchant_id = #{merchantId} "
            + "AND #{quantity} > 0 AND stock_quantity >= #{quantity}")
    int deductStock(@org.apache.ibatis.annotations.Param("id") Long id,
                    @org.apache.ibatis.annotations.Param("productId") Long productId,
                    @org.apache.ibatis.annotations.Param("merchantId") Long merchantId,
                    @org.apache.ibatis.annotations.Param("quantity") int quantity);

    @org.apache.ibatis.annotations.Update("UPDATE product_sku SET stock_quantity = stock_quantity + #{quantity} "
            + "WHERE id = #{id} AND merchant_id = #{merchantId} AND #{quantity} > 0")
    int addStock(@org.apache.ibatis.annotations.Param("id") Long id,
                 @org.apache.ibatis.annotations.Param("merchantId") Long merchantId,
                 @org.apache.ibatis.annotations.Param("quantity") int quantity);

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
