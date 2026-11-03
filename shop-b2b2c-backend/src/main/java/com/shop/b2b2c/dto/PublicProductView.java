/*
 * 文件：PublicProductView.java
 * 用途：公开商品列表的固定展示白名单及SQL查询结果模型。
 * 维护注意：保持既有JSON字段，不返回商品详情大字段、店铺身份或收款配置；totalStock是扣除预占后的可售SKU总量，使用Long避免int求和溢出，不代表下单承诺。
 */
package com.shop.b2b2c.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class PublicProductView {
    private Long id;
    private String title;
    private String subTitle;
    private Integer goodsType;
    private String mainImage;
    private BigDecimal minPrice;
    private Long merchantId;
    private String storeName;
    private String storeCode;
    private String storeLogo;
    private Long totalStock;
}
