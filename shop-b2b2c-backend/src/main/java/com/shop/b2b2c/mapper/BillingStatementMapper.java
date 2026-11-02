/*
 * 文件：BillingStatementMapper.java
 * 用途：BillingStatement领域的MyBatis基础持久化接口。
 * 维护注意：商户账单基础查询；当前没有自动生成/渠道对账业务，新增查询必须约束商户和账期。 普通save不是并发控制，资金/库存更新须专用事务与条件SQL。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.BillingStatement;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Mapper
public interface BillingStatementMapper extends BaseMapper<BillingStatement> {

    default Optional<BillingStatement> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default BillingStatement save(BillingStatement entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default List<BillingStatement> findByShopIdOrderByPeriodDateDesc(Long shopId) {
        return selectList(new LambdaQueryWrapper<BillingStatement>()
                .eq(BillingStatement::getShopId, shopId)
                .orderByDesc(BillingStatement::getPeriodDate));
    }

    default Optional<BillingStatement> findByShopIdAndPeriodDateAndPeriodType(Long shopId, LocalDate periodDate, String periodType) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<BillingStatement>()
                .eq(BillingStatement::getShopId, shopId)
                .eq(BillingStatement::getPeriodDate, periodDate)
                .eq(BillingStatement::getPeriodType, periodType)));
    }

    default List<BillingStatement> findByPeriodDateAndPeriodTypeOrderByGmvDesc(LocalDate periodDate, String periodType) {
        return selectList(new LambdaQueryWrapper<BillingStatement>()
                .eq(BillingStatement::getPeriodDate, periodDate)
                .eq(BillingStatement::getPeriodType, periodType)
                .orderByDesc(BillingStatement::getGmv));
    }

    default List<BillingStatement> findAllByOrderByPeriodDateDesc() {
        return selectList(new LambdaQueryWrapper<BillingStatement>()
                .orderByDesc(BillingStatement::getPeriodDate));
    }
}
