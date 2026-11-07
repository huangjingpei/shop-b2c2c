/*
 * 文件：BillingStatementDTOs.java
 * 用途：F09 商户账单接口入参/出参定义（重算请求）。
 * 维护注意：账单行直接序列化 BillingStatement 实体（无敏感字段）；periodType 只接受 DAILY/MONTHLY，
 * MONTHLY 的 periodDate 在服务层归一到月初。重算仅超管发起，不接受商户自选金额。
 */
package com.shop.b2b2c.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;

public class BillingStatementDTOs {

    /** 账单重算请求：MONTHLY 时 periodDate 允许传月内任意一天，服务层归一到 1 号。 */
    @Data
    public static class RegenerateRequest {
        private Long shopId;
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate periodDate;
        private String periodType;
    }
}
