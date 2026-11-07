/*
 * 文件：MerchantStore.java
 * 用途：商户经营身份、展示资料、费用与收款模式模型。
 * 维护注意：含经营者身份及内部费用，公开接口必须转PublicStoreView；状态用于交易资格，服务费编码复用ServiceFeeStatus，状态标记不代表自动扣费/停服。既有配置用定向SQL，显式清空日期仅限服务费操作，不能依赖全实体保存。
 * T32 新增 payChannel（真实支付渠道 WECHAT/ALIPAY）：新订单按此写入 trade_order.pay_channel；payment_channel_type 的 A/B/C 仅保留为迁移来源。
 * O9 新增 idCardExpiry/licenseExpiry（LocalDate，可空）：审核通过时从入驻申请快照的证件有效期，
 * 是资质到期提醒、限制上新、逾期30天自动冻结的唯一数据源；商家更新有效期只写本表两列。
 * F07 新增 shippingFee（可空兜底为0）：实物订单每单固定运费，0=包邮；只计入实付与商家应收，不进入服务费基数。
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
    /** F07 实物订单每单固定运费（元），0 表示包邮；仅实物订单计入实付，不计入服务费基数。 */
    private BigDecimal shippingFee;

    // ==================== 增量商业变现与合规直收扩展 ====================
    private BigDecimal monthlyFee = BigDecimal.ZERO; // 月度技术服务费 (前期=0)
    private LocalDateTime feeFreeUntil; // 免费试用截止日期，显式清空由服务费专用SQL处理，不能依赖整实体null回写 (R23/R09)
    private String feeStatus = ServiceFeeStatus.NORMAL; // NORMAL, TRIAL, OVERDUE, EXEMPTED, ARREARS
    private String paymentChannelType = "C"; // B(商户自主直收-免EDI二清), C(Mock沙箱-研发期), A(电商收付通-成熟期)
    private String payChannel = "WECHAT"; // T32 新增：真实支付渠道 WECHAT/ALIPAY，与 A/B/C 一列双语义解耦
    private String customPayQrcode; // 商家自主微信/支付宝收款二维码图片URL

    // ==================== O9 资质有效期快照 ====================
    /** 身份证有效期快照：审核通过时从入驻申请快照；为空表示未采集/长期，不参与提醒与冻结。 */
    private java.time.LocalDate idCardExpiry;
    /** 营业执照有效期快照：审核通过时从入驻申请快照；个人店可空。 */
    private java.time.LocalDate licenseExpiry;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
