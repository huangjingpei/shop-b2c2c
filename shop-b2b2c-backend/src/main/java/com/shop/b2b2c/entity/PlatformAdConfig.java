/*
 * 文件：PlatformAdConfig.java
 * 用途：代付成功页广告位置、素材与跳转配置模型。
 * 维护注意：跳转目标和素材需运营审核；当前只有广告读取等基础能力，不代表完整广告管理或入驻转化流程已实现。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("platform_ad_config")
public class PlatformAdConfig {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String adLocation = "PAYER_SUCCESS"; // 广告位置标识
    private String adTitle; // 主标题，如：我也想开店 —— 零门槛私域推单
    private String adSubTitle; // 副标题，如：无需营业执照，个人身份证+卡号即可入驻
    private String bannerUrl; // 宣传海报图片或横幅图片URL
    private String targetType = "MERCHANT_APPLY"; // MERCHANT_APPLY(唤起入驻开店), CUSTOM_URL(外部链接)
    private String targetUrl; // 跳转目标链接或动作参数
    private Integer isEnabled = 1; // 1-启用, 0-禁用
    private Integer sortOrder = 0;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
