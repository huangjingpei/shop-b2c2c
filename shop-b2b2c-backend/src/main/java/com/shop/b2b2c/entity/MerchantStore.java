package com.shop.b2b2c.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "merchant_store")
public class MerchantStore {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_code", nullable = false, unique = true)
    private String storeCode;

    @Column(name = "store_name", nullable = false)
    private String storeName;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "notice")
    private String notice;

    @Column(name = "merchant_type")
    private Integer merchantType = 1; // 1-个人, 2-个体工商户, 3-企业

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(name = "id_card_number", nullable = false)
    private String idCardNumber;

    @Column(name = "contact_phone", nullable = false)
    private String contactPhone;

    @Column(name = "status")
    private Integer status = 1; // 0-待审, 1-正常, 2-打烊, 3-封禁

    @Column(name = "commission_rate")
    private BigDecimal commissionRate = new BigDecimal("3.00");

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
