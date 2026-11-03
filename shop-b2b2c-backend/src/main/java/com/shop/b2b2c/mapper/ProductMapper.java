/*
 * 文件：ProductMapper.java
 * 用途：Product领域的MyBatis基础持久化接口。
 * 维护注意：公开列表单SQL组合筛选、营业/上架过滤及SKU归属聚合，保留数组契约，尚未分页。普通save不是并发控制，资金/库存更新须专用事务与条件SQL。
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

    @org.apache.ibatis.annotations.Select("""
        <script>
        SELECT p.id, p.title, p.sub_title, p.goods_type, p.main_image, p.min_price,
               p.merchant_id, s.store_name, s.store_code, s.logo_url AS store_logo,
               COALESCE(SUM(k.stock_quantity), 0) AS total_stock
        FROM product p
        JOIN merchant_store s ON s.id = p.merchant_id AND s.status = 1
        LEFT JOIN product_sku k ON k.product_id = p.id AND k.merchant_id = p.merchant_id
        WHERE p.status = 1
        <if test="categoryId != null">AND p.category_id = #{categoryId}</if>
        <if test="merchantId != null">AND p.merchant_id = #{merchantId}</if>
        GROUP BY p.id, p.title, p.sub_title, p.goods_type, p.main_image, p.min_price,
                 p.merchant_id, s.store_name, s.store_code, s.logo_url, p.sort_order
        ORDER BY p.sort_order ASC, p.id ASC
        </script>
        """)
    List<com.shop.b2b2c.dto.PublicProductView> findPublicProducts(
            @org.apache.ibatis.annotations.Param("categoryId") Long categoryId,
            @org.apache.ibatis.annotations.Param("merchantId") Long merchantId);

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
