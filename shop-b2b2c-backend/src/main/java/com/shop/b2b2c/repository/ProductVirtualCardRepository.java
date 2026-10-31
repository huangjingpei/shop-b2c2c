package com.shop.b2b2c.repository;

import com.shop.b2b2c.entity.ProductVirtualCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface ProductVirtualCardRepository extends JpaRepository<ProductVirtualCard, Long> {
    List<ProductVirtualCard> findBySkuIdAndStatus(Long skuId, Integer status);
    List<ProductVirtualCard> findByMerchantId(Long merchantId);
    List<ProductVirtualCard> findByOrderSn(String orderSn);
    long countBySkuIdAndStatus(Long skuId, Integer status);
}
