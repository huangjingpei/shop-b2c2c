/*
 * 文件：MerchantStore.java
 * 用途：商户经营身份、展示资料、费用与收款模式模型。
 * 维护注意：含经营者身份及内部费用，公开接口必须转PublicStoreView；状态用于交易资格，服务费编码复用ServiceFeeStatus，状态标记不代表自动扣费/停服。既有配置用定向SQL，显式清空日期仅限服务费操作，不能依赖全实体保存。
 * T32 新增 payChannel（真实支付渠道 WECHAT/ALIPAY）：新订单按此写入 trade_order.pay_channel；payment_channel_type 的 A/B/C 仅保留为迁移来源。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.shop.b2b2c.common.ServiceFeeStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("merchant_store")
public class MerchantStore {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String storeCode;
    private String storeName;
    private String logoUrl;
    private String notice;
    private Integer merchantType = 1; // 1-个人, 2-个体工商户, 3-企业
    private String ownerName;
    private String idCardNumber;
    private String contactPhone;
    private Integer status = 1; // 0-待审, 1-正常, 2-打烊, 3-封禁
    private BigDecimal commissionRate = new BigDecimal("3.00");

    // ==================== 增量商业变现与合规直收扩展 ====================
    private BigDecimal monthlyFee = BigDecimal.ZERO; // 月度技术服务费 (前期=0)
    private LocalDateTime feeFreeUntil; // 免费试用截止日期，显式清空由服务费专用SQL处理，不能依赖整实体null回写 (R23/R09)
    private String feeStatus = ServiceFeeStatus.NORMAL; // NORMAL, TRIAL, OVERDUE, EXEMPTED, ARREARS
    private String paymentChannelType = "C"; // B(商户自主直收-免EDI二清), C(Mock沙箱-研发期), A(电商收付通-成熟期)
    private String payChannel = "WECHAT"; // T32 新增：真实支付渠道 WECHAT/ALIPAY，与 A/B/C 一列双语义解耦
    private String customPayQrcode; // 商家自主微信/支付宝收款二维码图片URL

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
