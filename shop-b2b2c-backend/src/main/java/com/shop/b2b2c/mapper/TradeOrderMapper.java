package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.TradeOrder;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {

    default Optional<TradeOrder> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default TradeOrder save(TradeOrder entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default Optional<TradeOrder> findByOrderSn(String orderSn) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getOrderSn, orderSn)));
    }

    default Optional<TradeOrder> findByOrderSnAndBuyerContact(String orderSn, String buyerContact) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getOrderSn, orderSn)
                .eq(TradeOrder::getBuyerContact, buyerContact)));
    }

    default Optional<TradeOrder> findByPeerPayToken(String peerPayToken) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getPeerPayToken, peerPayToken)));
    }

    default List<TradeOrder> findByMerchantIdOrderByCreatedAtDesc(Long merchantId) {
        return selectList(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getMerchantId, merchantId)
                .orderByDesc(TradeOrder::getCreatedAt));
    }

    default List<TradeOrder> findAllByOrderByCreatedAtDesc() {
        return selectList(new LambdaQueryWrapper<TradeOrder>()
                .orderByDesc(TradeOrder::getCreatedAt));
    }
}
