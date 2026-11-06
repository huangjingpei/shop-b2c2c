/*
 * 文件：MerchantOnboardingApplication.java
 * 用途：商家入驻申请实体，承载实名主体、证件密文/指纹、审核状态与版本号。
 * 维护注意：
 * 1. idCardCipher/licenseNoCipher 只存 CardCipherService 产出的密文，禁止在实体或接口中回传明文；
 * 2. idCardFingerprint 是主体去重指纹（个人取身份证、企业取营业执照号），不是可展示的证件号；
 * 3. status 只能按 MerchantOnboardingService 的状态机推进，version 参与补件与并发保护；
 * 4. 审核通过前 merchantId 为空，禁止绕过审核直接写店铺关联。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant_onboarding_application")
public class MerchantOnboardingApplication {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;

    private String subjectType;

    private String subjectName;

    private String idCardCipher;

    private String idCardFingerprint;

    private String licenseNoCipher;

    private String licenseUrl;

    private String legalPerson;

    private String contactPhone;

    private String contactEmail;

    private String status;

    private String verificationMethod;

    private String verificationSource;

    private String rejectReason;

    private LocalDateTime submittedAt;

    private LocalDateTime reviewedAt;

    private Long reviewerId;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
