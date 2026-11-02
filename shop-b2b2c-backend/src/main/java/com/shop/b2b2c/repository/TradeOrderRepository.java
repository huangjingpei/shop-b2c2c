package com.shop.b2b2c.repository;

import com.shop.b2b2c.entity.TradeOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {
    Optional<TradeOrder> findByOrderSn(String orderSn);
    Optional<TradeOrder> findByOrderSnAndBuyerContact(String orderSn, String buyerContact);
    Optional<TradeOrder> findByPeerPayToken(String peerPayToken);
    List<TradeOrder> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
    List<TradeOrder> findAllByOrderByCreatedAtDesc();
}
