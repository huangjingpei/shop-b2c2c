/*
 * 文件：MerchantPayoutVerification.java
 * 用途：O5 收款账户小额打款验证单：平台向待核验账户打随机小额，商家回填金额证明账户归属。
 * 维护注意：
 * 1. amount 只由服务端生成（0.01-0.99，受 chk_payout_amount 约束），仅平台侧可见；
 *    商家端与公开接口禁止返回 amount，防止验证失去意义。
 * 2. 同一账户最多 3 次尝试（uk_payout_account_attempt + chk_payout_attempt）；
 *    第 3 次失败由服务层把账户置 REJECTED（原因码 PAYOUT_ATTEMPTS_EXCEEDED）。
 * 3. status 只允许 PENDING/SUCCESS/FAILED/EXPIRED；EXPIRED 由过期任务推进，可重新发起（新 attempt_no）。
 * 4. 发起人与确认人不得为同一人（服务层校验，同人 403）；金额不可由客户端指定。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("merchant_payout_verification")
public class MerchantPayoutVerification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    private Long merchantId;

    /** 第几次验证尝试，1-3。 */
    private Integer attemptNo;

    /** 服务端生成的校验金额；仅平台可见，禁止透传商家端。 */
    private BigDecimal amount;

    private String currency = "CNY";

    /** PENDING/SUCCESS/FAILED/EXPIRED。 */
    private String status = "PENDING";

    /** 线下转账凭证号；通道接入前使用。 */
    private String transferVoucherNo;

    private Long requestedBy;

    private LocalDateTime requestedAt;

    private LocalDateTime expiresAt;

    private LocalDateTime confirmedAt;

    private Long confirmedBy;

    /** AMOUNT_MISMATCH/EXPIRED/ATTEMPTS_EXCEEDED。 */
    private String failureCode;

    private LocalDateTime createdAt;
}
