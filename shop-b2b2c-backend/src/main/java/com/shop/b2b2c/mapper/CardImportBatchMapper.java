/*
 * 文件：CardImportBatchMapper.java
 * 用途：只追加导入批次/卡片关联，按商户限定查询批次和无正文成员。
 * 维护注意：写入必须参与资产导入事务，单卡只能关联一个批次。明细联表再次核对卡片商户/商品/SKU，避免异常关联泄露别店数据；不提供修改/删除或全量卡密查询。
 */
package com.shop.b2b2c.mapper;
import com.shop.b2b2c.entity.CardImportBatch;
import com.shop.b2b2c.dto.CardImportDTOs.Item;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface CardImportBatchMapper {
    @Insert("INSERT INTO card_import_batch(merchant_id,product_id,sku_id,actor_id,imported_count,before_stock,after_stock) "
            + "VALUES(#{merchantId},#{productId},#{skuId},#{actorId},#{importedCount},#{beforeStock},#{afterStock})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CardImportBatch batch);
    @Insert("INSERT INTO card_import_item(card_id,batch_id) VALUES(#{card},#{batch})")
    int link(@Param("batch") Long batch, @Param("card") Long card);
    @Select("SELECT * FROM card_import_batch WHERE id=#{id} AND merchant_id=#{merchant}")
    CardImportBatch find(@Param("merchant") Long merchant, @Param("id") Long id);
    @Select("<script>SELECT * FROM card_import_batch WHERE merchant_id=#{merchant} "
            + "<if test='sku != null'>AND sku_id=#{sku} </if><if test='before != null'>AND id &lt; #{before} </if>"
            + "ORDER BY id DESC LIMIT #{limit}</script>")
    List<CardImportBatch> list(@Param("merchant") Long merchant, @Param("sku") Long sku,
                              @Param("before") Long before, @Param("limit") int limit);
    @Select("<script>SELECT c.id AS card_id,c.status,c.order_sn FROM card_import_item i "
            + "JOIN card_import_batch b ON b.id=i.batch_id JOIN product_virtual_card c ON c.id=i.card_id "
            + "AND c.merchant_id=b.merchant_id AND c.product_id=b.product_id AND c.sku_id=b.sku_id "
            + "WHERE b.id=#{batch} AND b.merchant_id=#{merchant} "
            + "<if test='before != null'>AND c.id &lt; #{before} </if>ORDER BY c.id DESC LIMIT #{limit}</script>")
    List<Item> members(@Param("merchant") Long merchant, @Param("batch") Long batch,
                       @Param("before") Long before, @Param("limit") int limit);
}
