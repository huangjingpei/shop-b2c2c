/*
 * 文件：PlatformCollectionConfig.java
 * 用途：平台代收全局总闸的单行配置（M1），控制“是否允许商家模式 PLATFORM_COLLECT 对新订单生效”。
 * 维护注意：
 *  1. 固定只有 id=1 一行，禁止按商家复制；商家级模式仍以 merchant_store.collection_mode 为准。
 *  2. enabled 只是总闸，不替代渠道进件校验；总闸开启后，未绑定 BOUND/子商户号/结算账户的商家仍然按商家直收解析。
 *  3. 环境变量仅作应急关闭（服务层优先判断），本表才是运营运行时配置与审计来源。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("platform_collection_config")
public class PlatformCollectionConfig {
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 平台代收全局总闸：false 时所有新订单按商家直收解析。 */
    private Boolean enabled = false;

    /** 每次更新 +1 的配置版本号，用于审计与并发排查，不参与订单金额计算。 */
    private Long version = 0L;

    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
