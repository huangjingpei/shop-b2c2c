/*
 * 文件：CardAssetMapper.java
 * 用途：本店导入互斥、无指纹卡密去重读取、作废CAS及作废记录、补发记录的持久化。
 * 维护注意：所有新导入必须先锁商户，再锁商品/SKU；卡密明文只在服务内解密比对，禁止日志输出。T14：无指纹行按商户单次查询，仅在内存解密比对，不回写指纹；解密失败的行只标记 fingerprint_unavailable=1 并计数。作废先验证SKU预占和数量一致性，状态CAS与扣库存/记录须同事务；记录没有修改/删除接口。补发记录以源卡ID自然幂等、新卡ID唯一，源卡保持已售状态。
 */
package com.shop.b2b2c.mapper;
import com.shop.b2b2c.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface CardAssetMapper {
    @Select("SELECT id FROM merchant_store WHERE id=#{merchant} FOR UPDATE")
    Long lockMerchant(@Param("merchant") Long merchant);
    @Select("SELECT id, card_content FROM product_virtual_card WHERE merchant_id=#{merchant} AND card_fingerprint IS NULL ORDER BY id")
    List<ProductVirtualCard> findUnfingerprinted(@Param("merchant") Long merchant);
    @Update("UPDATE product_virtual_card SET fingerprint_unavailable=#{unavailable} "
            + "WHERE id=#{id} AND merchant_id=#{merchant} AND card_fingerprint IS NULL")
    int markFingerprintState(@Param("merchant") Long merchant, @Param("id") Long id, @Param("unavailable") Integer unavailable);
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
    /** F12 补发授权：按源卡ID+商户读取补发记录；源卡ID即主键，天然幂等。 */
    @Select("SELECT * FROM card_reissue WHERE source_card_id=#{id} AND merchant_id=#{merchant}")
    CardReissue reissueRecord(@Param("merchant") Long merchant, @Param("id") Long id);
    /** F12 补发记录写入：与认领新卡、扣减库存、明文审计同事务；重复源卡或新卡由主键/唯一键拒绝。 */
    @Insert("INSERT INTO card_reissue(source_card_id,new_card_id,merchant_id,product_id,sku_id,order_sn,actor_id,reason,before_stock,after_stock) "
            + "VALUES(#{sourceCardId},#{newCardId},#{merchantId},#{productId},#{skuId},#{orderSn},#{actorId},#{reason},#{beforeStock},#{afterStock})")
    int insertReissue(CardReissue record);
    /**
     * F12 存量一致性核对：按SKU聚合账面库存与各状态卡数、HELD预占与异常指纹计数，只读不修正。
     * 预占子查询每SKU至多一行，不会放大卡行计数；只覆盖本店卡密商品(goods_type=2)的SKU。
     */
    @Select("SELECT s.id AS skuId, s.product_id AS productId, s.stock_quantity AS stockQuantity, "
            + "COALESCE(SUM(CASE WHEN c.status=0 THEN 1 ELSE 0 END),0) AS availableCards, "
            + "COALESCE(SUM(CASE WHEN c.status=1 THEN 1 ELSE 0 END),0) AS soldCards, "
            + "COALESCE(SUM(CASE WHEN c.status=2 THEN 1 ELSE 0 END),0) AS voidedCards, "
            + "COALESCE(SUM(c.fingerprint_unavailable),0) AS fingerprintUnavailable, "
            + "COALESCE(h.heldQuantity,0) AS heldQuantity "
            + "FROM product_sku s "
            + "JOIN product p ON p.id=s.product_id AND p.merchant_id=s.merchant_id AND p.goods_type=2 "
            + "LEFT JOIN product_virtual_card c ON c.sku_id=s.id AND c.product_id=s.product_id AND c.merchant_id=s.merchant_id "
            + "LEFT JOIN (SELECT sku_id, SUM(quantity) AS heldQuantity FROM inventory_reservation "
            + "WHERE merchant_id=#{merchant} AND status='HELD' GROUP BY sku_id) h ON h.sku_id=s.id "
            + "WHERE s.merchant_id=#{merchant} "
            + "GROUP BY s.id, s.product_id, s.stock_quantity, h.heldQuantity ORDER BY s.id")
    java.util.List<com.shop.b2b2c.dto.CardConsistencyRow> consistencyRows(@Param("merchant") Long merchant);
}
