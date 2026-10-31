package com.shop.b2b2c.repository;

import com.shop.b2b2c.entity.TradeOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TradeOrderItemRepository extends JpaRepository<TradeOrderItem, Long> {
    List<TradeOrderItem> findByOrderId(Long orderId);
    List<TradeOrderItem> findByOrderSn(String orderSn);
}
