/*
 * 文件：InventoryReservationService.java
 * 用途：库存预占释放的唯一实现：预占 HELD→RELEASED 并回补 SKU 库存，供代付取消、订单取消与超时关单复用。
 * 维护注意：
 *  1. 本类不开启事务；调用方必须在订单行锁/事务内调用，保证“订单状态变更 + 预占释放 + 库存回补”原子。
 *  2. transition 使用条件更新，重复释放返回 false 且不得重复回补库存；回补 SKU 失败必须抛异常让事务回滚。
 *  3. 预占不存在（普通订单直接下单）返回 false，调用方仍可关闭订单，不得把缺少预占视为失败。
 *  4. 不在此处写订单状态或支付事件；禁止跨订单释放。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.InventoryReservation;
import com.shop.b2b2c.mapper.InventoryReservationMapper;
import com.shop.b2b2c.mapper.ProductSkuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryReservationService {

    private final InventoryReservationMapper reservations;
    private final ProductSkuMapper skus;

    /** 按订单释放；无预占或已释放返回 false（幂等）。 */
    public boolean release(Long orderId) {
        if (orderId == null) {
            return false;
        }
        return release(reservations.find(orderId));
    }

    public boolean release(InventoryReservation reservation) {
        if (reservation == null || reservations.transition(reservation.getOrderId(), "RELEASED") != 1) {
            return false;
        }
        if (skus.addStock(reservation.getSkuId(), reservation.getMerchantId(), reservation.getQuantity()) != 1) {
            throw new BusinessException("预占库存释放失败");
        }
        return true;
    }
}
