/*
 * 文件：MerchantCollectionStatusAuditMapper.java
 * 用途：收款状态审计数据访问（T35）。
 * 维护注意：只追加；按商户倒序分页由调用方限制条数，禁止无限扫描。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantCollectionStatusAudit;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MerchantCollectionStatusAuditMapper extends BaseMapper<MerchantCollectionStatusAudit> {

    default List<MerchantCollectionStatusAudit> findByMerchant(Long merchantId, int limit) {
        return selectList(new LambdaQueryWrapper<MerchantCollectionStatusAudit>()
                .eq(MerchantCollectionStatusAudit::getMerchantId, merchantId)
                .orderByDesc(MerchantCollectionStatusAudit::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
    }
}
