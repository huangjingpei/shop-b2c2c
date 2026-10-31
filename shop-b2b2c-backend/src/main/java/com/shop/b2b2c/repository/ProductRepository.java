package com.shop.b2b2c.repository;

import com.shop.b2b2c.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByStatusOrderBySortOrderAsc(Integer status);
    List<Product> findByCategoryIdAndStatusOrderBySortOrderAsc(Long categoryId, Integer status);
    List<Product> findByMerchantId(Long merchantId);
    List<Product> findByMerchantIdAndStatus(Long merchantId, Integer status);
}
