/*
 * 文件：MerchantAccount.java
 * 用途：商家服务费账户模型（T34），保存可用余额、欠费累计、欠费额度与宽限期。
 * 维护注意：
 *  1. 余额/欠费只能通过 MerchantAccountService 的账本入账方法变更；禁止直接 set 后保存绕过账本。
 *  2. arrearsDueAt 仅在存在欠费时有意义；graceDays 为空表示管理员尚未确认账期，按即时到期处理。
 *  3. version 用于并发更新；账户行更新前必须通过 mapper 的 FOR UPDATE 查询加锁。
 *  4. T35 停收只停新单收款（collectionStopped），不影响已支付订单履约与退款；停收/恢复必须写审计。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("merchant_account")
public class MerchantAccount {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;
    private BigDecimal balanceAvailable = BigDecimal.ZERO;
    private BigDecimal balanceFrozen = BigDecimal.ZERO;
    private BigDecimal arrears = BigDecimal.ZERO;
    private BigDecimal creditLimit = new BigDecimal("500.00");
    private Integer graceDays; // 7/21/30；null 表示未确认
    private LocalDateTime arrearsDueAt;
    private String currency = "CNY";
    private Boolean collectionStopped = false;
    private String stopReason;
    private LocalDateTime stoppedAt;
    private LocalDateTime recoveredAt;
    private Integer version = 0;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
