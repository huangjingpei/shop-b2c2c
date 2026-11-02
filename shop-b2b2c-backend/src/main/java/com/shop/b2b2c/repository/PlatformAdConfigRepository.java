package com.shop.b2b2c.repository;

import com.shop.b2b2c.entity.PlatformAdConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PlatformAdConfigRepository extends JpaRepository<PlatformAdConfig, Long> {
    List<PlatformAdConfig> findByAdLocationAndIsEnabledOrderBySortOrderDesc(String adLocation, Integer isEnabled);
    Optional<PlatformAdConfig> findFirstByAdLocationAndIsEnabledOrderBySortOrderDesc(String adLocation, Integer isEnabled);
}
