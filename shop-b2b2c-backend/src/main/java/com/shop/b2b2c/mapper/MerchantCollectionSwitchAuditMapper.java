/*
 * 文件：MerchantCollectionSwitchAuditMapper.java
 * 用途：商家收款模式切换审计数据访问（M1）。
 * 维护注意：只追加、按商户倒序分页，limit 必须由调用方限制，禁止无限扫描或改写历史审计。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantCollectionSwitchAudit;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MerchantCollectionSwitchAuditMapper extends BaseMapper<MerchantCollectionSwitchAudit> {

    default List<MerchantCollectionSwitchAudit> findByMerchant(Long merchantId, int limit) {
        return selectList(new LambdaQueryWrapper<MerchantCollectionSwitchAudit>()
                .eq(MerchantCollectionSwitchAudit::getMerchantId, merchantId)
                .orderByDesc(MerchantCollectionSwitchAudit::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
    }
}
