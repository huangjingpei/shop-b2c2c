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
}
