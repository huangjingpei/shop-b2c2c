/*
 * 文件：MerchantStoreStatusAuditMapper.java
 * 用途：O6 店铺状态流转审计持久化：只插不改，按店铺查时间线。
 * 维护注意：
 * 1. 不提供 update/delete；审计行一经写入不可变（数据库层依赖应用自律，不额外加触发器）。
 * 2. findByMerchantId 用于管理端审计时间线，按时间倒序。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantStoreStatusAudit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MerchantStoreStatusAuditMapper extends BaseMapper<MerchantStoreStatusAudit> {

    @Select("SELECT * FROM merchant_store_status_audit WHERE merchant_id = #{merchantId} ORDER BY id DESC")
    List<MerchantStoreStatusAudit> findByMerchantId(@Param("merchantId") Long merchantId);
}
