/*
 * 文件：TradeOrder.java
 * 用途：交易订单主表模型，保存金额、交易/代付状态及交付快照。
 * 维护注意：支付、取消、超时及履约写路径须共用订单行锁与事务；不能仅凭普通save实现幂等。peerStatus默认INIT并复用PeerStatus编码；未知旧值不自动转换或视作已预占，状态字段保持String兼容API。
 */
package com.shop.b2b2c.entity;

import com.shop.b2b2c.common.PeerStatus;
import com.baomidou.mybatisplus.annotation.IdType;
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
    private String orderStatus = "UNPAID"; // UNPAID, PAID, SHIPPED, COMPLETED, REFUNDED, CLOSED
    private String sharingStatus = "PENDING"; // PENDING, PROCESSING, FINISHED, FAILED
    private String paymentChannel = "WECHAT";
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
    private LocalDateTime lockExpireTime; // 15分钟原子预占超时时刻
    private String peerMessage; // 代付人定制求助留言
    private String posterUrl; // 9:16 微信带参分享买单海报 Base64

    // ==================== 线上服务交付凭证扩展 ====================
    private String serviceVoucher; // 卖家填写的交付凭证 (交付截图URL/账号密码/网盘链接等)

    private LocalDateTime createdAt;
}
