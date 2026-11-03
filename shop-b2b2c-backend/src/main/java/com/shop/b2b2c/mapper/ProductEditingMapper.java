/*
 * 文件：ProductEditingMapper.java
 * 用途：为商品维护提供带租户条件的行锁查询及字段白名单更新SQL。
 * 维护注意：锁顺序为商品、按ID排序的SKU，必须置于Spring事务；售价更新不能写stock_quantity，避免覆盖预占/发卡库存。所有写入同时校验商品、SKU和商户归属，不调用整实体save。
 */
package com.shop.b2b2c.mapper;

import com.shop.b2b2c.dto.ProductEditingDTOs;
import com.shop.b2b2c.entity.Product;
import com.shop.b2b2c.entity.ProductSku;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface ProductEditingMapper {
    @Select("SELECT * FROM product WHERE id=#{id} AND merchant_id=#{merchant} FOR UPDATE")
    Product lockProduct(@Param("id") Long id, @Param("merchant") Long merchant);

    @Select("SELECT * FROM product_sku WHERE product_id=#{id} AND merchant_id=#{merchant} ORDER BY id FOR UPDATE")
    List<ProductSku> lockSkus(@Param("id") Long id, @Param("merchant") Long merchant);

    @Update("UPDATE product SET category_id=#{edit.categoryId},title=#{edit.title},sub_title=#{edit.subTitle},"
            + "main_image=#{edit.mainImage},service_desc=#{edit.serviceDesc},min_price=#{minimum},updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND merchant_id=#{merchant}")
    int updateMetadata(@Param("id") Long id, @Param("merchant") Long merchant, @Param("edit") ProductEditingDTOs.Update edit,
                       @Param("minimum") BigDecimal minimum);

    @Update("UPDATE product_sku SET price=#{price} WHERE id=#{sku} AND product_id=#{id} AND merchant_id=#{merchant}")
    int updatePrice(@Param("id") Long id, @Param("merchant") Long merchant, @Param("sku") Long sku, @Param("price") BigDecimal price);

    @Update("UPDATE product SET status=#{status},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND merchant_id=#{merchant} AND status IN (0,1)")
    int updateStatus(@Param("id") Long id, @Param("merchant") Long merchant, @Param("status") int status);
}
