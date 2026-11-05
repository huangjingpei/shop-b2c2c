/*
 * 文件：CardAccessAuditMapper.java
 * 用途：卡密明文访问审计的只追加写入。
 * 维护注意：仅允许导出等已授权操作插入；调用方必须保证审计写入成功后才返回明文。查询入口暂不开放，避免把审计明细暴露给无关角色。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.CardAccessAudit;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CardAccessAuditMapper extends BaseMapper<CardAccessAudit> {
}
