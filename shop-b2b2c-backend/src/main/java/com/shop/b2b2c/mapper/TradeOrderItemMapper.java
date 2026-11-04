/*
 * 文件：TradeOrderItemMapper.java
 * 用途：TradeOrderItem领域的MyBatis基础持久化接口。
 * 维护注意：订单明细基础查询；按orderSn查出数据前须确认订单访问权，不能把通用查询直接暴露给未授权调用方。 普通save不是并发控制，资金/库存更新须专用事务与条件SQL。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.TradeOrderItem;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface TradeOrderItemMapper extends BaseMapper<TradeOrderItem> {

    default Optional<TradeOrderItem> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default TradeOrderItem save(TradeOrderItem entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default List<TradeOrderItem> findByOrderId(Long orderId) {
        return selectList(new LambdaQueryWrapper<TradeOrderItem>()
                .eq(TradeOrderItem::getOrderId, orderId));
    }

    default List<TradeOrderItem> findByOrderSn(String orderSn) {
        return selectList(new LambdaQueryWrapper<TradeOrderItem>()
                .eq(TradeOrderItem::getOrderSn, orderSn));
    }

    default List<TradeOrderItem> findByOrderSns(java.util.Collection<String> orderSns) {
        if (orderSns == null || orderSns.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return selectList(new LambdaQueryWrapper<TradeOrderItem>()
                .in(TradeOrderItem::getOrderSn, orderSns));
    }
}
