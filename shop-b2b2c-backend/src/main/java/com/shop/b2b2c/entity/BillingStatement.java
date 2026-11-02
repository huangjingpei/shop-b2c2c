package com.shop.b2b2c.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "billing_statement", uniqueConstraints = {
    @UniqueConstraint(name = "uk_shop_period", columnNames = {"shop_id", "period_date", "period_type"})
})
public class BillingStatement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false)
    private Long shopId;

    @Column(name = "store_name", nullable = false)
    private String storeName;

    @Column(name = "period_date", nullable = false)
    private LocalDate periodDate; // 对账归属日期 (YYYY-MM-DD)

    @Column(name = "period_type", nullable = false)
    private String periodType = "DAILY"; // DAILY(日对账), MONTHLY(月对账)

    @Column(name = "gmv", nullable = false)
    private BigDecimal gmv = BigDecimal.ZERO; // 当日/月总交易金额 GMV

    @Column(name = "peer_pay_gmv", nullable = false)
    private BigDecimal peerPayGmv = BigDecimal.ZERO; // 代付渠道成交总额 (微信/支付宝代付)

    @Column(name = "direct_pay_gmv", nullable = false)
    private BigDecimal directPayGmv = BigDecimal.ZERO; // 买家自主支付成交总额

    @Column(name = "orders_cnt", nullable = false)
    private Integer ordersCnt = 0; // 累计完成订单笔数

    @Column(name = "refund_amt", nullable = false)
    private BigDecimal refundAmt = BigDecimal.ZERO; // 退款总金额 (原路返回代付人)

    @Column(name = "fee_amt", nullable = false)
    private BigDecimal feeAmt = BigDecimal.ZERO; // 平台软件技术服务费扣收额

    @Column(name = "status", nullable = false)
    private String status = "SETTLED"; // PENDING(核算中), SETTLED(已结算出账), DISPUTED(有争议)

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
