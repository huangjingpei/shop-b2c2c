/*
 * 文件：MerchantAgreementVersion.java
 * 用途：协议版本实体：协议类型、版本号、正文哈希、快照地址、生效时间与发布状态。
 * 维护注意：
 * 1. 同类型协议仅一条 PUBLISHED 为当前生效，由 MerchantAgreementService 在事务内保证（发布新版时同事务下线旧版）。
 * 2. contentHash 是签署校验证据：申请人/店长同意时携带的哈希必须与此一致，不一致返回 409（协议已更新）。
 * 3. contentUrl 必须指向不可变快照，禁止指向可变页面；下线（OFFLINE）只做状态迁移，不删除历史版本。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant_agreement_version")
public class MerchantAgreementVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** MERCHANT_SERVICE/PRIVACY/SETTLEMENT/PROHIBITED_GOODS。 */
    private String protocolType;

    private String version;

    private String title;

    private String contentHash;

    private String contentUrl;

    private LocalDateTime effectiveFrom;

    /** DRAFT/PUBLISHED/OFFLINE。 */
    private String status;

    private Long createdBy;

    private LocalDateTime createdAt;
}
