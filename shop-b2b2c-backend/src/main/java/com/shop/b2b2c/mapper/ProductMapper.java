/*
 * 文件：ProductMapper.java
 * 用途：Product领域的MyBatis基础持久化接口。
 * 维护注意：公开列表单SQL组合筛选、营业/上架过滤及SKU归属聚合，保留数组契约，尚未分页。普通save不是并发控制，资金/库存更新须专用事务与条件SQL。
 * T30：公开列表新增关键词 q 与商品类型过滤；关键词只在服务端按标题/副标题做包含匹配（LOCATE，不使用 IN 拼接），
 *       调用方必须先 trim 并限制长度；goodsType 白名单 1/2/3 由控制器校验，SQL 不再接受任意值。
 * 频道多类目：categoryIds 由控制器校验为正整数列表后以 IN 参数化传入，与单个 categoryId 互斥，不得直接拼接字符串。
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
        <if test="categoryIds != null and categoryIds.size() > 0">
            AND p.category_id IN
            <foreach collection="categoryIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
        </if>
        <if test="merchantId != null">AND p.merchant_id = #{merchantId}</if>
        <if test="goodsType != null">AND p.goods_type = #{goodsType}</if>
        <if test="keyword != null">AND (LOCATE(LOWER(#{keyword}), LOWER(p.title)) > 0
            OR LOCATE(LOWER(#{keyword}), LOWER(COALESCE(p.sub_title, ''))) > 0)</if>
        GROUP BY p.id, p.title, p.sub_title, p.goods_type, p.main_image, p.min_price,
                 p.merchant_id, s.store_name, s.store_code, s.logo_url, p.sort_order
        ORDER BY p.sort_order ASC, p.id ASC
        </script>
        """)
    List<com.shop.b2b2c.dto.PublicProductView> findPublicProducts(
            @org.apache.ibatis.annotations.Param("categoryId") Long categoryId,
            @org.apache.ibatis.annotations.Param("categoryIds") List<Long> categoryIds,
            @org.apache.ibatis.annotations.Param("merchantId") Long merchantId,
            @org.apache.ibatis.annotations.Param("keyword") String keyword,
            @org.apache.ibatis.annotations.Param("goodsType") Integer goodsType);

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

    /** O6 关店前置条件：统计店铺在售商品数；>0 时禁止关店（须先全部下架）。 */
    default long countByMerchantIdAndStatus(Long merchantId, Integer status) {
        return selectCount(new LambdaQueryWrapper<Product>()
                .eq(Product::getMerchantId, merchantId)
                .eq(Product::getStatus, status));
    }
}
