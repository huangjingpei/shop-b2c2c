/*
 * 文件：ProductVirtualCard.java
 * 用途：虚拟卡密资产、售出状态与订单归属模型。
 * 维护注意：cardContent为敏感交付资产，不得写公开日志；分配必须使用条件认领和事务，查询必须校验租户。fingerprintUnavailable仅表示该行缺少明文或解密失败、无法参与指纹比对，不代表卡密不可用；不得据此跳过履约或改变状态。已交付给买家的卡密不允许更换或重发。密钥按当前单密钥假设处理，轮换不在本轮范围（缺口 G09）。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("product_virtual_card")
public class ProductVirtualCard {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;
    private Long skuId;
    private Long merchantId;
    private String cardFingerprint;
    /** T14 指纹不可用状态：0-可比对，1-缺少明文或解密失败；不影响卡密状态，也不阻断导入。 */
    private Integer fingerprintUnavailable;
    private String cardContent;
    private Integer status = 0; // 0-未售出, 1-已售出核销, 2-作废
    private String orderSn;
    private LocalDateTime createdAt;
}
