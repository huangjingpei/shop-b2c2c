package com.shop.b2b2c.repository;

import com.shop.b2b2c.entity.BillingStatement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BillingStatementRepository extends JpaRepository<BillingStatement, Long> {
    List<BillingStatement> findByShopIdOrderByPeriodDateDesc(Long shopId);
    Optional<BillingStatement> findByShopIdAndPeriodDateAndPeriodType(Long shopId, LocalDate periodDate, String periodType);
    List<BillingStatement> findByPeriodDateAndPeriodTypeOrderByGmvDesc(LocalDate periodDate, String periodType);
    List<BillingStatement> findAllByOrderByPeriodDateDesc();
}
