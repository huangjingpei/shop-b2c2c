package com.shop.b2b2c.repository;

import com.shop.b2b2c.entity.ProductSku;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProductSkuRepository extends JpaRepository<ProductSku, Long> {
    List<ProductSku> findByProductId(Long productId);
    Optional<ProductSku> findBySkuCode(String skuCode);
}
