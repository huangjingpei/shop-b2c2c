/*
 * 文件：ProductModerationDTOs.java
 * 用途：F11 平台商品违规处置接口的请求与列表视图模型。
 * 维护注意：
 *  1. reason/note 长度在服务层统一 trim 并校验（reason 必填 1..255，note 可选 ≤255），DTO 只承载入参。
 *  2. AdminProductRow 为管理端列表只读视图（join 店铺名），不用于写操作；Long 字段按全局 Jackson 配置序列化为字符串。
 */
package com.shop.b2b2c.dto;

import lombok.Data;

import java.time.LocalDateTime;

public final class ProductModerationDTOs {

    private ProductModerationDTOs() {
    }

    @Data
    public static class TakedownRequest {
        /** 违规处置原因，必填，trim 后 1..255。 */
        private String reason;
    }

    @Data
    public static class RestoreRequest {
        /** 审核备注，可选，trim 后 ≤255；为空时服务层写默认恢复说明。 */
        private String note;
    }

    /** 管理端商品治理列表行（含店铺名）。 */
    @Data
    public static class AdminProductRow {
        private Long id;
        private Long merchantId;
        private String storeName;
        private Long categoryId;
        private String title;
        private String subTitle;
        private Integer goodsType;
        private String mainImage;
        private java.math.BigDecimal minPrice;
        private Integer status;
        private String violationReason;
        private LocalDateTime violatedAt;
        private LocalDateTime updatedAt;
    }
}
