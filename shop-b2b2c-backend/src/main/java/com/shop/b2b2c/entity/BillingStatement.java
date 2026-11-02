/*
 * 文件：BillingStatement.java
 * 用途：商户日/月对账汇总的持久化模型。
 * 维护注意：目前仅有模型与基础映射，未形成账单生成和渠道对账闭环；金额与状态不能当作真实结算结果。改字段须同步SQL、迁移和测试结构。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("billing_statement")
public class BillingStatement {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String storeName;
    private LocalDate periodDate; // 对账归属日期 (YYYY-MM-DD)
    private String periodType = "DAILY"; // DAILY(日对账), MONTHLY(月对账)
    private BigDecimal gmv = BigDecimal.ZERO; // 当日/月总交易金额 GMV
    private BigDecimal peerPayGmv = BigDecimal.ZERO; // 代付渠道成交总额 (微信/支付宝代付)
    private BigDecimal directPayGmv = BigDecimal.ZERO; // 买家自主支付成交总额
    private Integer ordersCnt = 0; // 累计完成订单笔数
    private BigDecimal refundAmt = BigDecimal.ZERO; // 退款总金额 (原路返回代付人)
    private BigDecimal feeAmt = BigDecimal.ZERO; // 平台软件技术服务费扣收额
    private String status = "SETTLED"; // PENDING(核算中), SETTLED(已结算出账), DISPUTED(有争议)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
