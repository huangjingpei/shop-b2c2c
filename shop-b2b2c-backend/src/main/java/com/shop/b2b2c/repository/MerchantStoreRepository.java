package com.shop.b2b2c.repository;

import com.shop.b2b2c.entity.MerchantStore;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MerchantStoreRepository extends JpaRepository<MerchantStore, Long> {
    Optional<MerchantStore> findByStoreCode(String storeCode);
}
