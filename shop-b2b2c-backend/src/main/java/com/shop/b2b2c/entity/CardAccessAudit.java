/*
 * 文件：CardAccessAudit.java
 * 用途：卡密明文访问审计记录（当前用于 SKU 导出）。
 * 维护注意：只追加、不修改/删除；不保存任何卡密正文或指纹；actorId 取自已校验会话，禁止由客户端传入。审计写入失败必须使请求失败，不能返回明文。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("card_access_audit")
public class CardAccessAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long merchantId;
    private Long actorId;
    private String action;
    private Long skuId;
    private Integer cardCount;
    private LocalDateTime createdAt;
}
