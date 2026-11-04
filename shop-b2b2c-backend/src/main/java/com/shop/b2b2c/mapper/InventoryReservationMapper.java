/*
 * 文件：InventoryReservationMapper.java
 * 用途：预占流水插入、到期扫描与HELD状态条件迁移。
 * 维护注意：调用方必须先锁订单行；状态迁移影响行数决定是否允许加库存或核销。查出的所有者凭证只供内部授权，不直接返回公开接口。
 */
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

    @Select("SELECT order_id FROM inventory_reservation WHERE status='HELD' AND expires_at <= CURRENT_TIMESTAMP AND order_id > #{cursor} ORDER BY order_id LIMIT #{limit}")
    List<Long> expiredIdsAfter(@Param("cursor") Long cursor, @Param("limit") int limit);

    default List<Long> expiredIds() {
        return expiredIdsAfter(0L, 100);
    }
}
