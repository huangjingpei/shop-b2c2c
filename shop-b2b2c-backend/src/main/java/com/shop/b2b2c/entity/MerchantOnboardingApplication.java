/*
 * 文件：MerchantOnboardingApplication.java
 * 用途：商家入驻申请实体，承载实名主体、证件密文/指纹、审核状态与版本号。
 * 维护注意：
 * 1. idCardCipher/licenseNoCipher 只存 CardCipherService 产出的密文，禁止在实体或接口中回传明文；
 * 2. idCardFingerprint 是主体去重指纹（个人取身份证、企业取营业执照号），不是可展示的证件号；
 * 3. status 只能按 MerchantOnboardingService 的状态机推进，version 参与补件与并发保护；
 * 4. 审核通过前 merchantId 为空，禁止绕过审核直接写店铺关联；
 * 5. accessTokenHash 只存申请人查询凭证的 SHA-256，明文只在创建响应返回一次；
 * 6. initialPasswordCipher 保存自动开店初始密码密文，供申请人在进度查询中一次性查看，明文不得落库或回传列表；
 * 7. initialPassword 是审核响应用的瞬时字段（TableField exist=false），不参与任何 SQL 持久化。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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

    /** 申请人填写的店铺名称；审核通过自动开店时作为 merchant_store.store_name。 */
    private String storeName;

    /** 个人店实人认证口令：申请人录视频时朗读的随机数字，由人工审核比对；不是密钥。 */
    private String faceVerifyCode;

    private String idCardCipher;

    private String idCardFingerprint;

    private String licenseNoCipher;

    private String licenseUrl;

    private String legalPerson;

    private String contactPhone;

    private String contactEmail;

    /** 申请人查询凭证的 SHA-256；明文只在创建响应返回一次，不落库。 */
    private String accessTokenHash;

    /** 自动开店初始密码密文（AES-GCM），仅进度查询一次性展示。 */
    private String initialPasswordCipher;

    private LocalDateTime initialPasswordRevealedAt;

    private String status;

    private String verificationMethod;

    private String verificationSource;

    /** 驳回/补件原因码（文档 5.5 字典）；OTHER 时 rejectReason 必填。 */
    private String rejectCode;

    private String rejectReason;

    /** 补件后重新提交次数；仅 SUPPLEMENT_REQUIRED -> SUBMITTED 时 +1。 */
    private Integer resubmitCount;

    /** 审核阶段 NONE/PRIMARY/SECONDARY/PASSED；双人复核开启时初审通过后置 SECONDARY 待复审。 */
    private String reviewStage;

    /** 初审人；双人复核开启时复审人必须与其不同。 */
    private Long firstReviewerId;

    /** 开通前是否必须完成必需协议签署（O3/O4）。 */
    private Boolean agreementRequired;

    /** 全部必需协议签署完成时间。 */
    private LocalDateTime agreedAt;

    /** 店铺开通时间（进入 OPENED）。 */
    private LocalDateTime openedAt;

    /** 逾期撤回/取消原因（系统 EXPIRE 或人工撤回）。 */
    private String cancelReason;

    private LocalDateTime submittedAt;

    private LocalDateTime reviewedAt;

    private Long reviewerId;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 审核通过响应中一次性返回的初始密码；不写入数据库。 */
    @TableField(exist = false)
    private String initialPassword;

    /** 审核通过响应中返回的新店铺编码；不写入数据库，避免重复列存储。 */
    @TableField(exist = false)
    private String storeCode;
}
