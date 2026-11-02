package com.shop.b2b2c.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "platform_ad_config")
public class PlatformAdConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ad_location", nullable = false)
    private String adLocation = "PAYER_SUCCESS"; // 广告位置标识

    @Column(name = "ad_title", nullable = false)
    private String adTitle; // 主标题，如：我也想开店 —— 零门槛私域推单

    @Column(name = "ad_sub_title")
    private String adSubTitle; // 副标题，如：无需营业执照，个人身份证+卡号即可入驻

    @Column(name = "banner_url")
    private String bannerUrl; // 宣传海报图片或横幅图片URL

    @Column(name = "target_type", nullable = false)
    private String targetType = "MERCHANT_APPLY"; // MERCHANT_APPLY(唤起入驻开店), CUSTOM_URL(外部链接)

    @Column(name = "target_url")
    private String targetUrl; // 跳转目标链接或动作参数

    @Column(name = "is_enabled", nullable = false)
    private Integer isEnabled = 1; // 1-启用, 0-禁用

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
