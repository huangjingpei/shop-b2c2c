package com.shop.b2b2c.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "merchant_payment_account")
public class MerchantPaymentAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(nullable = false)
    private String channel; // WECHAT, ALIPAY

    @Column(name = "sub_mchid", nullable = false)
    private String subMchid;

    @Column(name = "bank_account_name", nullable = false)
    private String bankAccountName;

    @Column(name = "bank_account_number", nullable = false)
    private String bankAccountNumber;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @Column(name = "apply_status", nullable = false)
    private String applyStatus = "PASSED"; // AUDITING, PASSED, REJECTED

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
