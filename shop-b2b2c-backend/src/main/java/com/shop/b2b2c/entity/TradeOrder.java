package com.shop.b2b2c.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "trade_order")
public class TradeOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_sn", nullable = false, unique = true)
    private String orderSn;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "buyer_contact", nullable = false)
    private String buyerContact; // 手机号或邮箱，16688 免密查单核心依据

    @Column(name = "goods_type", nullable = false)
    private Integer goodsType = 1; // 1-实物, 2-虚拟

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "pay_amount", nullable = false)
    private BigDecimal payAmount;

    @Column(name = "commission_rate", nullable = false)
    private BigDecimal commissionRate;

    @Column(name = "commission_amount", nullable = false)
    private BigDecimal commissionAmount;

    @Column(name = "merchant_settle_amount", nullable = false)
    private BigDecimal merchantSettleAmount;

    @Column(name = "order_status", nullable = false)
    private String orderStatus = "UNPAID"; // UNPAID, PAID, SHIPPED, COMPLETED, REFUNDED, CLOSED

    @Column(name = "sharing_status", nullable = false)
    private String sharingStatus = "PENDING"; // PENDING, PROCESSING, FINISHED, FAILED

    @Column(name = "payment_channel")
    private String paymentChannel = "WECHAT";

    @Column(name = "pay_trade_no")
    private String payTradeNo;

    @Column(name = "logistics_company")
    private String logisticsCompany;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(name = "delivery_time")
    private LocalDateTime deliveryTime;

    @Column(name = "finish_time")
    private LocalDateTime finishTime;

    @Column(name = "query_token", nullable = false)
    private String queryToken;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
