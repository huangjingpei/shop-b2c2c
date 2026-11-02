package com.shop.b2b2c.mapper;

import com.shop.b2b2c.entity.InventoryReservation;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface InventoryReservationMapper {
    @Select("SELECT * FROM inventory_reservation WHERE order_id=#{id}")
    InventoryReservation find(@Param("id") Long id);

    @Insert("INSERT INTO inventory_reservation(order_id,sku_id,product_id,merchant_id,quantity,owner_token,status,expires_at) "
            + "VALUES(#{orderId},#{skuId},#{productId},#{merchantId},#{quantity},#{ownerToken},'HELD',#{expiresAt})")
    int insert(InventoryReservation reservation);

    @Delete("DELETE FROM inventory_reservation WHERE order_id=#{id} AND status='RELEASED'")
    int deleteReleased(@Param("id") Long id);

    @Update("UPDATE inventory_reservation SET status=#{status} WHERE order_id=#{id} AND status='HELD'")
    int transition(@Param("id") Long id, @Param("status") String status);

    @Select("SELECT order_id FROM inventory_reservation WHERE status='HELD' AND expires_at <= CURRENT_TIMESTAMP ORDER BY order_id LIMIT 100")
    List<Long> expiredIds();
}
