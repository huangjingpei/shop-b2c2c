/*
 * 文件：MerchantAgreementConsent.java
 * 用途：协议同意记录实体：签署证据链（类型/版本/哈希快照/主体/IP/UA/时间）。
 * 维护注意：
 * 1. append-only 证据表：禁止 UPDATE/DELETE；重复签署同一版本由 uk_consent_app_protocol 兜底并幂等返回。
 * 2. subjectName 记录签署时的实名主体/店铺名快照，不随申请单后续变更而改写。
 * 3. consentMethod 阶段一固定 CHECKBOX；接入第三方电子签时新增 ESIGN，不改变既有记录。
 * 4. ip/userAgent 为争议举证要素，允许为空（定时任务或内部代签场景），但接口层必须尽力采集。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant_agreement_consent")
public class MerchantAgreementConsent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请人维度签署；开通后店铺维度补签时也必须保留原申请关联。 */
    private Long applicationId;

    private Long merchantId;

    private String protocolType;

    private String protocolVersion;

    private String contentHash;

    private String subjectName;

    /** CHECKBOX/ESIGN；阶段一固定 CHECKBOX。 */
    private String consentMethod;

    private String ip;

    private String userAgent;

    private LocalDateTime agreedAt;
}
