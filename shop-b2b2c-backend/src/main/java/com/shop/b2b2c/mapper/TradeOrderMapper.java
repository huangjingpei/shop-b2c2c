/*
 * 文件：TradeOrderMapper.java
 * 用途：订单查询、行锁和有效待付预占的支付CAS迁移；SQL代付编码复用PeerStatus。
 * 维护注意：列表/统计投影不读poster_url，不能将这些不完整实体当作订单全量快照；已存海报单独按ID和商户读取。所有写者须保持订单行锁顺序，支付CAS须检查影响行数，事务外FOR UPDATE无并发保护。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.common.PeerStatus;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {

    @org.apache.ibatis.annotations.Select("SELECT poster_url FROM trade_order WHERE id = #{id} AND merchant_id = #{merchantId}")
    String findStoredPoster(@org.apache.ibatis.annotations.Param("id") Long id,
                           @org.apache.ibatis.annotations.Param("merchantId") Long merchantId);

    // Serialize all order writers so stale detail/expiry writes cannot undo payment.
    @org.apache.ibatis.annotations.Select("SELECT * FROM trade_order WHERE peer_pay_token = #{token} FOR UPDATE")
    TradeOrder lockByPeerPayToken(@org.apache.ibatis.annotations.Param("token") String token);

    default Optional<TradeOrder> findByPeerPayTokenForUpdate(String token) {
        return Optional.ofNullable(lockByPeerPayToken(token));
    }

    @org.apache.ibatis.annotations.Select("SELECT * FROM trade_order WHERE id = #{id} FOR UPDATE")
    TradeOrder lockById(@org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Update("UPDATE trade_order SET order_status = 'PAID', peer_status = '" + PeerStatus.PAID + "', "
            + "peer_payer_id = #{payerId}, pay_trade_no = #{tradeNo}, payment_channel = #{channel} "
            + "WHERE id = #{id} AND order_status = 'UNPAID' AND peer_status = '" + PeerStatus.RESERVED_LOCK + "' "
            + "AND lock_expire_time > CURRENT_TIMESTAMP")
    int markPaidIfUnpaid(@org.apache.ibatis.annotations.Param("id") Long id,
                        @org.apache.ibatis.annotations.Param("payerId") String payerId,
                        @org.apache.ibatis.annotations.Param("tradeNo") String tradeNo,
                        @org.apache.ibatis.annotations.Param("channel") String channel);

    @org.apache.ibatis.annotations.Select("SELECT id FROM trade_order "
            + "WHERE order_status = 'UNPAID' "
            + "AND (lock_expire_time <= #{now} OR (lock_expire_time IS NULL AND created_at <= #{cutoffTime})) "
            + "AND id > #{cursor} ORDER BY id LIMIT #{limit}")
    List<Long> findExpiredUnpaidOrderIds(@org.apache.ibatis.annotations.Param("now") java.time.LocalDateTime now,
                                         @org.apache.ibatis.annotations.Param("cutoffTime") java.time.LocalDateTime cutoffTime,
                                         @org.apache.ibatis.annotations.Param("cursor") Long cursor,
                                         @org.apache.ibatis.annotations.Param("limit") int limit);

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
                .select(TradeOrder.class, field -> !"posterUrl".equals(field.getProperty()))
                .eq(TradeOrder::getMerchantId, merchantId)
                .orderByDesc(TradeOrder::getCreatedAt));
    }

    default List<TradeOrder> findAllByOrderByCreatedAtDesc() {
        return selectList(new LambdaQueryWrapper<TradeOrder>()
                .select(TradeOrder.class, field -> !"posterUrl".equals(field.getProperty()))
                .orderByDesc(TradeOrder::getCreatedAt));
    }
}
