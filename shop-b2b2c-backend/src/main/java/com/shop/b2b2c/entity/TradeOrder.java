/*
 * 文件：TradeOrder.java
 * 用途：交易订单主表模型，保存金额、交易/代付状态及交付快照。
 * 维护注意：支付、取消、超时及履约写路径须共用订单行锁与事务；不能仅凭普通save实现幂等。peerStatus默认INIT并复用PeerStatus编码；未知旧值不自动转换或视作已预占，状态字段保持String兼容API。
 * T33：refundedAmount/feeRefundedAmount 含处理中预占，不能据此单独把订单视为已退款；订单是否 REFUNDED 由 trade_refund 成功记录累计判断。
 * T34：新增订单级服务费快照字段；下单时按商家当前费率固化，支付成功后按快照扣费，后续费率调整不得回溯已下单订单。
 */
package com.shop.b2b2c.entity;

import com.shop.b2b2c.common.PeerStatus;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("trade_order")
public class TradeOrder {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderSn;
    private Long merchantId;
    private String buyerContact; // 手机号或邮箱，免密查单核心依据
    private Integer goodsType = 1; // 1-实物商品, 2-自动卡密, 3-定制服务
    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private BigDecimal commissionRate;
    private BigDecimal commissionAmount;
    private BigDecimal merchantSettleAmount;
    private BigDecimal refundedAmount; // T33 已退款商品金额（含处理中预占）
    private BigDecimal feeRefundedAmount; // T33 已冲正/预占技术服务费
    private String feeStatus = "PENDING"; // T33/T34 PENDING/ACCRUED/ARREARS/PARTIAL_REVERSED/REVERSED
    // ==================== T34 服务费订单级快照 ====================
    private BigDecimal goodsAmount;
    private BigDecimal merchantDiscountAmount;
    private BigDecimal platformDiscountAmount;
    private BigDecimal shippingAmount;
    private BigDecimal orderTaxAmount;
    private Long feePolicyId;
    private Integer feeRateBps;
    private String feeRateType;
    private BigDecimal feeBaseAmount;
    private BigDecimal feeStandardAmount;
    private BigDecimal feeAmount;
    private BigDecimal feeWaivedAmount;
    private BigDecimal feeNetAmount;
    private BigDecimal feeTaxAmount;
    private BigDecimal feeTaxRate;
    private String orderStatus = "UNPAID"; // UNPAID, PAID, SHIPPED, COMPLETED, REFUNDED, CLOSED
    private String sharingStatus = "PENDING"; // PENDING, PROCESSING, FINISHED, FAILED
    private String paymentChannel = "WECHAT";
    private String collectionMode = "MERCHANT_DIRECT"; // T32 新增：MERCHANT_DIRECT / PLATFORM_COLLECT
    private String payChannel = "WECHAT"; // T32 新增：真实支付渠道 WECHAT/ALIPAY，替代 A/B/C 一列双语义
    private String payTradeNo;
    private String logisticsCompany;
    private String trackingNumber;
    private LocalDateTime deliveryTime;
    private LocalDateTime finishTime;
    private String queryToken;

    // ==================== 增量代付与两段式锁扩展 ====================
    private String peerPayToken; // 代付唯一防撞短码
    private Integer peerPayType = 0; // 0-自付, 1-买家求助代付, 2-卖家代客推单
    private String peerPayerId; // 实际代付付款人标识 (OpenID/UID/流水号)
    private String peerStatus = PeerStatus.INIT; // INIT, RESERVED_LOCK, PAID, EXPIRED, CANCELLED
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lockExpireTime; // 15分钟原子预占超时时刻，取消或超时时支持显式清空为null
    private String peerMessage; // 代付人定制求助留言
    private String posterUrl; // 9:16 微信带参分享买单海报 Base64

    // ==================== 线上服务交付凭证扩展 ====================
    private String serviceVoucher; // 卖家填写的交付凭证 (交付截图URL/账号密码/网盘链接等)

    private LocalDateTime createdAt;
}
