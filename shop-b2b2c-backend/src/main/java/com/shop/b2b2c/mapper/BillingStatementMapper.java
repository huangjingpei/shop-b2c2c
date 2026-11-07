/*
 * 文件：BillingStatementMapper.java
 * 用途：BillingStatement领域的MyBatis基础持久化接口（F09：账单生成upsert与商户/管理端分页查询）。
 * 维护注意：商户账单基础查询；生成/重算走服务层事务（find→update/insert，唯一键uk_shop_period兜底并发），新增查询必须约束商户和账期。普通save不是并发控制，资金/库存更新须专用事务与条件SQL。
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

    /** F09 账单查询：按商户（可空=全平台）+账期类型+账期日期范围分页，账期倒序。 */
    default com.baomidou.mybatisplus.extension.plugins.pagination.Page<BillingStatement> pageByPeriodRange(
            Long shopId, String periodType, java.time.LocalDate from, java.time.LocalDate to,
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<BillingStatement> page) {
        var wrapper = new LambdaQueryWrapper<BillingStatement>()
                .eq(shopId != null, BillingStatement::getShopId, shopId)
                .eq(BillingStatement::getPeriodType, periodType)
                .ge(from != null, BillingStatement::getPeriodDate, from)
                .le(to != null, BillingStatement::getPeriodDate, to)
                .orderByDesc(BillingStatement::getPeriodDate)
                .orderByAsc(BillingStatement::getShopId);
        return selectPage(page, wrapper);
    }

    /** F09 账单导出：同口径全量行（不分页），调用方必须已限制日期跨度。 */
    default List<BillingStatement> listByPeriodRange(Long shopId, String periodType,
                                                     java.time.LocalDate from, java.time.LocalDate to) {
        return selectList(new LambdaQueryWrapper<BillingStatement>()
                .eq(shopId != null, BillingStatement::getShopId, shopId)
                .eq(BillingStatement::getPeriodType, periodType)
                .ge(from != null, BillingStatement::getPeriodDate, from)
                .le(to != null, BillingStatement::getPeriodDate, to)
                .orderByDesc(BillingStatement::getPeriodDate)
                .orderByAsc(BillingStatement::getShopId));
    }
}
