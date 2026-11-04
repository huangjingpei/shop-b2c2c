/*
 * 文件：CardAssetMapper.java
 * 用途：本店导入互斥、历史卡密去重读取、作废CAS及作废记录持久化。
 * 维护注意：所有新导入必须先锁商户，再锁商品/SKU；历史内容只在服务内生成摘要，禁止日志输出。作废先验证SKU预占和数量一致性，状态CAS与扣库存/记录须同事务；记录没有修改/删除接口。
 */
package com.shop.b2b2c.mapper;
import com.shop.b2b2c.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface CardAssetMapper {
    @Select("SELECT id FROM merchant_store WHERE id=#{merchant} FOR UPDATE")
    Long lockMerchant(@Param("merchant") Long merchant);
    @Select("SELECT card_content FROM product_virtual_card WHERE merchant_id=#{merchant}")
    List<String> historyContents(@Param("merchant") Long merchant);
    @Select("<script>"
            + "SELECT card_fingerprint FROM product_virtual_card WHERE merchant_id=#{merchant} AND card_fingerprint IN "
            + "<foreach collection='fingerprints' item='fp' open='(' separator=',' close=')'>#{fp}</foreach> "
            + "LIMIT 1"
            + "</script>")
    String findExistingFingerprint(@Param("merchant") Long merchant, @Param("fingerprints") java.util.Collection<String> fingerprints);
    @Select("SELECT * FROM product_virtual_card WHERE id=#{id} AND merchant_id=#{merchant}")
    ProductVirtualCard findCard(@Param("merchant") Long merchant, @Param("id") Long id);
    @Select("SELECT * FROM product_virtual_card WHERE id=#{id} AND merchant_id=#{merchant} FOR UPDATE")
    ProductVirtualCard lockCard(@Param("merchant") Long merchant, @Param("id") Long id);
    @Select("SELECT COUNT(*) FROM product_virtual_card WHERE sku_id=#{sku} AND product_id=#{product} AND merchant_id=#{merchant} AND status=0")
    long availableCount(@Param("merchant") Long merchant, @Param("product") Long product, @Param("sku") Long sku);
    @Update("UPDATE product_virtual_card SET status=2 WHERE id=#{id} AND merchant_id=#{merchant} AND status=0 AND order_sn IS NULL")
    int markVoid(@Param("merchant") Long merchant, @Param("id") Long id);
    @Insert("INSERT INTO virtual_card_void(card_id,merchant_id,product_id,sku_id,actor_id,reason,before_stock,after_stock) "
            + "VALUES(#{cardId},#{merchantId},#{productId},#{skuId},#{actorId},#{reason},#{beforeStock},#{afterStock})")
    int insertVoid(CardVoidRecord record);
    @Select("SELECT * FROM virtual_card_void WHERE card_id=#{id} AND merchant_id=#{merchant}")
    CardVoidRecord voidRecord(@Param("merchant") Long merchant, @Param("id") Long id);
}
