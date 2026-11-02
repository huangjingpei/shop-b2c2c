package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.TradeOrder;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {

    // Serialize all order writers so stale detail/expiry writes cannot undo payment.
    @org.apache.ibatis.annotations.Select("SELECT * FROM trade_order WHERE peer_pay_token = #{token} FOR UPDATE")
    TradeOrder lockByPeerPayToken(@org.apache.ibatis.annotations.Param("token") String token);

    default Optional<TradeOrder> findByPeerPayTokenForUpdate(String token) {
        return Optional.ofNullable(lockByPeerPayToken(token));
    }

    @org.apache.ibatis.annotations.Select("SELECT * FROM trade_order WHERE id = #{id} FOR UPDATE")
    TradeOrder lockById(@org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Update("UPDATE trade_order SET order_status = 'PAID', peer_status = 'PAID', "
            + "peer_payer_id = #{payerId}, pay_trade_no = #{tradeNo}, payment_channel = #{channel} "
            + "WHERE id = #{id} AND order_status = 'UNPAID' AND peer_status = 'RESERVED_LOCK' "
            + "AND lock_expire_time > CURRENT_TIMESTAMP")
    int markPaidIfUnpaid(@org.apache.ibatis.annotations.Param("id") Long id,
                        @org.apache.ibatis.annotations.Param("payerId") String payerId,
                        @org.apache.ibatis.annotations.Param("tradeNo") String tradeNo,
                        @org.apache.ibatis.annotations.Param("channel") String channel);

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
