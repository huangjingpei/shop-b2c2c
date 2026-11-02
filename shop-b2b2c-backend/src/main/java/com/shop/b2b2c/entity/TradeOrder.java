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
    private String buyerContact; // 手机号或邮箱，免密查单核心依据

    @Column(name = "goods_type", nullable = false)
    private Integer goodsType = 1; // 1-实物商品, 2-线上服务/虚拟卡密

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

    // ==================== 增量代付与两段式锁扩展 ====================
    @Column(name = "peer_pay_token", unique = true)
    private String peerPayToken; // 代付唯一防撞短码

    @Column(name = "peer_pay_type", nullable = false)
    private Integer peerPayType = 0; // 0-自付, 1-买家求助代付, 2-卖家代客推单

    @Column(name = "peer_payer_id")
    private String peerPayerId; // 实际代付付款人标识 (OpenID/UID/流水号)

    @Column(name = "peer_status", nullable = false)
    private String peerStatus = "INIT"; // INIT(意向生成), RESERVED_LOCK(两段式已锁库存), PAID(已支付), EXPIRED(超时释放), CANCELLED(取消)

    @Column(name = "peer_message")
    private String peerMessage; // 买家个性求助留言 (如: 江湖救急，帮我买个单呗~)

    @Column(name = "poster_url", columnDefinition = "MEDIUMTEXT")
    private String posterUrl; // 生成的带参代付海报图片访问路径或Base64

    @Column(name = "service_voucher")
    private String serviceVoucher; // 线上服务/虚拟履约凭证 (卡密/网盘/交付报告)

    @Column(name = "lock_expire_time")
    private LocalDateTime lockExpireTime; // 两段式库存预扣到期时间 (默认调起支付15分钟)

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
