package com.shop.b2b2c.repository;

import com.shop.b2b2c.entity.Complaint;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {
    List<Complaint> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
    List<Complaint> findByBuyerIdOrderByCreatedAtDesc(String buyerId);
    List<Complaint> findByOrderId(Long orderId);
    Optional<Complaint> findByOrderSn(String orderSn);
    List<Complaint> findByStatusOrderByCreatedAtDesc(String status);
    List<Complaint> findAllByOrderByCreatedAtDesc();
}
