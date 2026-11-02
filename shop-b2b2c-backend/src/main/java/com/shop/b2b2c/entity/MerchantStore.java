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

    // ==================== 增量商业变现与合规直收扩展 ====================
    @Column(name = "monthly_fee", nullable = false)
    private BigDecimal monthlyFee = BigDecimal.ZERO; // 月度技术服务费 (前期=0)

    @Column(name = "fee_free_until")
    private LocalDateTime feeFreeUntil; // 免费试用截止日期

    @Column(name = "fee_status", nullable = false)
    private String feeStatus = "NORMAL"; // NORMAL(正常), TRIAL(免费期), OVERDUE(欠费只读限制)

    @Column(name = "payment_channel_type", nullable = false)
    private String paymentChannelType = "C"; // B(商户自主直收-免EDI二清), C(Mock沙箱-研发期), A(电商收付通-成熟期)

    @Column(name = "custom_pay_qrcode")
    private String customPayQrcode; // 商家自主微信/支付宝收款二维码图片URL

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
