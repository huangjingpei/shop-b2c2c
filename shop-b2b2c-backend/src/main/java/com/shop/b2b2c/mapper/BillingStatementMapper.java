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
