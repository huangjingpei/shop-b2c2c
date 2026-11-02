package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("merchant_payment_account")
public class MerchantPaymentAccount {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;
    private String channel; // WECHAT, ALIPAY
    private String subMchid;
    private String bankAccountName;
    private String bankAccountNumber;
    private String bankName;
    private String applyStatus = "PASSED"; // AUDITING, PASSED, REJECTED
    private String rejectReason;
    private LocalDateTime createdAt;
}
