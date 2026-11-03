/*
 * 文件：InventoryAdjustmentMapper.java
 * 用途：租户限定的SKU行锁、人工库存CAS增减、只追加记录和游标查询。
 * 维护注意：调整服务以READ_COMMITTED持有SKU锁后读取HELD总量，不能锁预占行造成与释放流程反向锁死。记录无更新/删除接口；SKU更新不改售价和规格，必须与记录/幂等结果同事务提交。
 */
package com.shop.b2b2c.mapper;

import com.shop.b2b2c.entity.InventoryAdjustment;
import com.shop.b2b2c.entity.ProductSku;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface InventoryAdjustmentMapper {
    @Select("SELECT * FROM product_sku WHERE id=#{sku} AND product_id=#{product} AND merchant_id=#{merchant} FOR UPDATE")
    ProductSku lockSku(@Param("merchant") Long merchant, @Param("product") Long product, @Param("sku") Long sku);

    @Select("SELECT COALESCE(SUM(quantity),0) FROM inventory_reservation WHERE sku_id=#{sku} AND merchant_id=#{merchant} AND status='HELD'")
    long heldQuantity(@Param("merchant") Long merchant, @Param("sku") Long sku);

    @Update("UPDATE product_sku SET stock_quantity=stock_quantity+#{delta} WHERE id=#{sku} AND product_id=#{product} "
            + "AND merchant_id=#{merchant} AND stock_quantity=#{before} AND stock_quantity+#{delta} BETWEEN 0 AND 2147483647")
    int adjust(@Param("merchant") Long merchant, @Param("product") Long product, @Param("sku") Long sku,
               @Param("before") int before, @Param("delta") int delta);

    @Insert("INSERT INTO inventory_adjustment(merchant_id,product_id,sku_id,actor_id,request_key,delta,before_stock,after_stock,reason) "
            + "VALUES(#{merchantId},#{productId},#{skuId},#{actorId},#{requestKey},#{delta},#{beforeStock},#{afterStock},#{reason})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InventoryAdjustment row);

    @Select("SELECT * FROM inventory_adjustment WHERE id=#{id} AND merchant_id=#{merchant}")
    InventoryAdjustment find(@Param("merchant") Long merchant, @Param("id") Long id);

    @Select("<script>SELECT * FROM inventory_adjustment WHERE merchant_id=#{merchant} "
            + "<if test='sku != null'>AND sku_id=#{sku} </if><if test='before != null'>AND id &lt; #{before} </if>"
            + "ORDER BY id DESC LIMIT #{limit}</script>")
    List<InventoryAdjustment> list(@Param("merchant") Long merchant, @Param("sku") Long sku,
                                   @Param("before") Long before, @Param("limit") int limit);
}
