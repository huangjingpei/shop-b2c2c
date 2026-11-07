/*
 * 文件：ProductEditingMapper.java
 * 用途：为商品维护提供带租户条件的行锁查询、字段白名单更新及SKU增删SQL。
 * 维护注意：锁顺序为商品、按ID排序的SKU，必须置于Spring事务；售价更新不能写stock_quantity，避免覆盖预占/发卡库存。所有写入同时校验商品、SKU和商户归属，不调用整实体save。SKU编码同店唯一由唯一键uk_merchant_sku_code兜底；删除SKU前必须先在服务层确认零库存且无任何引用记录，外键仅作并发兜底。detail_images只接收服务层规范化后的JSON串（null表示清空），不做二次校验。
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
            + "main_image=#{edit.mainImage},service_desc=#{edit.serviceDesc},detail_images=#{detailImagesJson},min_price=#{minimum},updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND merchant_id=#{merchant}")
    int updateMetadata(@Param("id") Long id, @Param("merchant") Long merchant, @Param("edit") ProductEditingDTOs.Update edit,
                       @Param("detailImagesJson") String detailImagesJson, @Param("minimum") BigDecimal minimum);

    @Update("UPDATE product_sku SET price=#{price} WHERE id=#{sku} AND product_id=#{id} AND merchant_id=#{merchant}")
    int updatePrice(@Param("id") Long id, @Param("merchant") Long merchant, @Param("sku") Long sku, @Param("price") BigDecimal price);

    @Update("UPDATE product SET status=#{status},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND merchant_id=#{merchant} AND status IN (0,1)")
    int updateStatus(@Param("id") Long id, @Param("merchant") Long merchant, @Param("status") int status);

    @Select("SELECT COUNT(*) FROM product_sku WHERE merchant_id=#{merchant} AND sku_code=#{code}")
    long countSkuCode(@Param("merchant") Long merchant, @Param("code") String code);

    @Insert("INSERT INTO product_sku(merchant_id,product_id,sku_code,spec_data,price,stock_quantity) "
            + "VALUES(#{merchant},#{id},#{sku.skuCode},#{sku.specData},#{sku.price},#{sku.stockQuantity})")
    @Options(useGeneratedKeys = true, keyProperty = "sku.id", keyColumn = "id")
    int insertSku(@Param("id") Long id, @Param("merchant") Long merchant, @Param("sku") ProductSku sku);

    /** 汇总引用该SKU的全部历史记录行数（订单明细/卡密/预占/库存调整/作废/导入批次/补发/明文审计），>0即禁止删除。 */
    @Select("SELECT (SELECT COUNT(*) FROM trade_order_item WHERE sku_id=#{sku})"
            + " + (SELECT COUNT(*) FROM product_virtual_card WHERE sku_id=#{sku})"
            + " + (SELECT COUNT(*) FROM inventory_reservation WHERE sku_id=#{sku})"
            + " + (SELECT COUNT(*) FROM inventory_adjustment WHERE sku_id=#{sku})"
            + " + (SELECT COUNT(*) FROM virtual_card_void WHERE sku_id=#{sku})"
            + " + (SELECT COUNT(*) FROM card_import_batch WHERE sku_id=#{sku})"
            + " + (SELECT COUNT(*) FROM card_reissue WHERE sku_id=#{sku})"
            + " + (SELECT COUNT(*) FROM card_access_audit WHERE sku_id=#{sku})")
    long countSkuReferences(@Param("sku") Long sku);

    @Delete("DELETE FROM product_sku WHERE id=#{sku} AND product_id=#{id} AND merchant_id=#{merchant}")
    int deleteSku(@Param("id") Long id, @Param("merchant") Long merchant, @Param("sku") Long sku);

    @Update("UPDATE product_sku SET spec_data=#{spec} WHERE id=#{sku} AND product_id=#{id} AND merchant_id=#{merchant}")
    int updateSpec(@Param("id") Long id, @Param("merchant") Long merchant, @Param("sku") Long sku, @Param("spec") String spec);

    @Update("UPDATE product SET min_price=#{minimum},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND merchant_id=#{merchant}")
    int updateMinPrice(@Param("id") Long id, @Param("merchant") Long merchant, @Param("minimum") BigDecimal minimum);
}
