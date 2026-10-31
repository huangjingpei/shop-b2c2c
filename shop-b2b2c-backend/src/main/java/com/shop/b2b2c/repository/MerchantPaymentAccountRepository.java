package com.shop.b2b2c.repository;

import com.shop.b2b2c.entity.MerchantPaymentAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MerchantPaymentAccountRepository extends JpaRepository<MerchantPaymentAccount, Long> {
    Optional<MerchantPaymentAccount> findByMerchantIdAndChannel(Long merchantId, String channel);
    Optional<MerchantPaymentAccount> findByMerchantId(Long merchantId);
}
