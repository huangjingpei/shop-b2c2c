/*
 * 文件：TradeOrderMapper.java
 * 用途：订单查询、行锁和有效待付预占的支付CAS迁移；SQL代付编码复用PeerStatus。
 * 维护注意：
 *  1. 列表/统计投影不读 poster_url，不能将这些不完整实体当作订单全量快照；已存海报单独按 ID 和商户读取。
 *  2. 所有写者须保持订单行锁顺序，支付 CAS 须检查影响行数，事务外 FOR UPDATE 无并发保护。
 *  3. T08 起普通订单支付 CAS 也校验 lock_expire_time > 当前时间，不对 NULL 截止时间放行；
 *     截止时间由订单创建/预占路径写入 createdAt+15，迁移脚本不回填历史订单。
 *  4. T24 列表只走 keyset（created_at+id 游标）；T25 统计只走 aggregateOrderStatistics 的库内 GROUP BY，
 *     不再提供全表订单投影方法，金额异常必须在 SQL 里单独计数而不是并入汇总。
 *  5. T32 允许未支付订单在首次真实支付尝试时切换 pay_channel；切换必须以 order_status=UNPAID 为条件，
 *     与支付回调的 PAID 迁移互斥，避免付款后改渠道。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.common.OrderCursor;
import com.shop.b2b2c.common.PeerStatus;
import com.shop.b2b2c.dto.OrderStatisticsAggregate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;

@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {

    @org.apache.ibatis.annotations.Select("SELECT poster_url FROM trade_order WHERE id = #{id} AND merchant_id = #{merchantId}")
    String findStoredPoster(@org.apache.ibatis.annotations.Param("id") Long id,
                           @org.apache.ibatis.annotations.Param("merchantId") Long merchantId);

    @org.apache.ibatis.annotations.Select("SELECT poster_url FROM trade_order WHERE peer_pay_token = #{token}")
    String findStoredPosterByToken(@org.apache.ibatis.annotations.Param("token") String token);

    @org.apache.ibatis.annotations.Update("UPDATE trade_order SET poster_url = #{posterUrl} WHERE id = #{id}")
    int updatePosterUrl(@org.apache.ibatis.annotations.Param("id") Long id,
                        @org.apache.ibatis.annotations.Param("posterUrl") String posterUrl);

    @org.apache.ibatis.annotations.Update("UPDATE trade_order SET peer_status = '" + PeerStatus.EXPIRED + "' WHERE id = #{id} AND peer_status = '" + PeerStatus.INIT + "'")
    int markInitExpired(@org.apache.ibatis.annotations.Param("id") Long id);

    // Serialize all order writers so stale detail/expiry writes cannot undo payment.
    @org.apache.ibatis.annotations.Select("SELECT * FROM trade_order WHERE peer_pay_token = #{token} FOR UPDATE")
    TradeOrder lockByPeerPayToken(@org.apache.ibatis.annotations.Param("token") String token);

    default Optional<TradeOrder> findByPeerPayTokenForUpdate(String token) {
        return Optional.ofNullable(lockByPeerPayToken(token));
    }

    @org.apache.ibatis.annotations.Select("SELECT * FROM trade_order WHERE id = #{id} FOR UPDATE")
    TradeOrder lockById(@org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Update("UPDATE trade_order SET order_status = 'PAID', peer_status = '" + PeerStatus.PAID + "', "
            + "peer_payer_id = #{payerId}, pay_trade_no = #{tradeNo}, payment_channel = #{channel}, lock_expire_time = NULL "
            + "WHERE id = #{id} AND order_status = 'UNPAID' AND peer_status = '" + PeerStatus.RESERVED_LOCK + "' "
            + "AND lock_expire_time > CURRENT_TIMESTAMP")
    int markPaidIfUnpaid(@org.apache.ibatis.annotations.Param("id") Long id,
                        @org.apache.ibatis.annotations.Param("payerId") String payerId,
                        @org.apache.ibatis.annotations.Param("tradeNo") String tradeNo,
                        @org.apache.ibatis.annotations.Param("channel") String channel);

    @org.apache.ibatis.annotations.Update("UPDATE trade_order SET order_status = 'PAID', peer_status = '" + PeerStatus.PAID + "', "
            + "peer_payer_id = #{payerId}, pay_trade_no = #{tradeNo}, payment_channel = #{channel}, lock_expire_time = NULL "
            + "WHERE id = #{id} AND order_status = 'UNPAID' AND lock_expire_time > CURRENT_TIMESTAMP")
    int markOrderPaidIfUnpaid(@org.apache.ibatis.annotations.Param("id") Long id,
                              @org.apache.ibatis.annotations.Param("payerId") String payerId,
                              @org.apache.ibatis.annotations.Param("tradeNo") String tradeNo,
                              @org.apache.ibatis.annotations.Param("channel") String channel);

    @org.apache.ibatis.annotations.Select("SELECT id FROM trade_order "
            + "WHERE order_status = 'UNPAID' AND lock_expire_time <= #{now} "
            + "AND id > #{cursor} ORDER BY id LIMIT #{limit}")
    List<Long> findExpiredUnpaidOrderIds(@org.apache.ibatis.annotations.Param("now") java.time.LocalDateTime now,
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
                .select(TradeOrder.class, field -> !"posterUrl".equals(field.getProperty()))
                .eq(TradeOrder::getOrderSn, orderSn)));
    }

    default Optional<TradeOrder> findByOrderSnAndBuyerContact(String orderSn, String buyerContact) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<TradeOrder>()
                .select(TradeOrder.class, field -> !"posterUrl".equals(field.getProperty()))
                .eq(TradeOrder::getOrderSn, orderSn)
                .eq(TradeOrder::getBuyerContact, buyerContact)));
    }

    default Optional<TradeOrder> findByPeerPayToken(String peerPayToken) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<TradeOrder>()
                .select(TradeOrder.class, field -> !"posterUrl".equals(field.getProperty()))
                .eq(TradeOrder::getPeerPayToken, peerPayToken)));
    }

    /**
     * T32：首次真实支付尝试时把未支付订单的渠道快照切换到实际选择；已支付订单不做任何变更。
     */
    default int switchPayChannelIfUnpaid(Long id, String payChannel) {
        return update(null, new LambdaUpdateWrapper<TradeOrder>()
                .eq(TradeOrder::getId, id)
                .eq(TradeOrder::getOrderStatus, "UNPAID")
                .set(TradeOrder::getPayChannel, payChannel)
                .set(TradeOrder::getPaymentChannel, payChannel));
    }

    default List<TradeOrder> findByMerchantIdOrderByCreatedAtDesc(Long merchantId) {
        return selectList(new LambdaQueryWrapper<TradeOrder>()
                .select(TradeOrder.class, field -> !"posterUrl".equals(field.getProperty()))
                .eq(TradeOrder::getMerchantId, merchantId)
                .orderByDesc(TradeOrder::getCreatedAt)
                .orderByDesc(TradeOrder::getId));
    }

    default List<TradeOrder> findAllByOrderByCreatedAtDesc() {
        return selectList(new LambdaQueryWrapper<TradeOrder>()
                .select(TradeOrder.class, field -> !"posterUrl".equals(field.getProperty()))
                .orderByDesc(TradeOrder::getCreatedAt)
                .orderByDesc(TradeOrder::getId));
    }

    /**
     * T24：keyset 分页。排序键固定为 created_at DESC + id DESC，游标同时包含两个分量；
     * 返回 limit + 1 行供调用方判断 hasMore，禁止使用 OFFSET。
     */
    default List<TradeOrder> findMerchantKeyset(Long merchantId, String status, OrderCursor before, int limit) {
        LambdaQueryWrapper<TradeOrder> query = new LambdaQueryWrapper<TradeOrder>()
                .select(TradeOrder.class, field -> !"posterUrl".equals(field.getProperty()))
                .eq(TradeOrder::getMerchantId, merchantId)
                .eq(status != null, TradeOrder::getOrderStatus, status);
        applyCursor(query, before);
        return selectList(query.orderByDesc(TradeOrder::getCreatedAt)
                .orderByDesc(TradeOrder::getId)
                .last("LIMIT " + limit));
    }

    default long countMerchant(Long merchantId, String status) {
        return selectCount(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getMerchantId, merchantId)
                .eq(status != null, TradeOrder::getOrderStatus, status));
    }

    default List<TradeOrder> findAllKeyset(String status, OrderCursor before, int limit) {
        LambdaQueryWrapper<TradeOrder> query = new LambdaQueryWrapper<TradeOrder>()
                .select(TradeOrder.class, field -> !"posterUrl".equals(field.getProperty()))
                .eq(status != null, TradeOrder::getOrderStatus, status);
        applyCursor(query, before);
        return selectList(query.orderByDesc(TradeOrder::getCreatedAt)
                .orderByDesc(TradeOrder::getId)
                .last("LIMIT " + limit));
    }

    default long countAll(String status) {
        return selectCount(new LambdaQueryWrapper<TradeOrder>()
                .eq(status != null, TradeOrder::getOrderStatus, status));
    }

    private static void applyCursor(LambdaQueryWrapper<TradeOrder> query, OrderCursor before) {
        if (before == null) {
            return;
        }
        query.and(w -> w.lt(TradeOrder::getCreatedAt, before.createdAt())
                .or(n -> n.eq(TradeOrder::getCreatedAt, before.createdAt())
                        .lt(TradeOrder::getId, before.id())));
    }

    /**
     * T25：统计只做库内 GROUP BY，不再把订单行加载进 JVM。金额汇总只计入合法值
     * （非 NULL、非负、最多两位小数），异常单数单独计数；退款状态仍按历史已付款计入 turnover，
     * 但不计预计佣金，与 OrderStatistics 原逐单口径保持一致。
     */
    @Select("<script>"
            + "SELECT order_status, peer_status, COUNT(*) AS order_count, "
            + "COALESCE(SUM(CASE WHEN pay_amount IS NOT NULL AND pay_amount &gt;= 0 "
            + "AND pay_amount = ROUND(pay_amount, 2) THEN pay_amount ELSE 0 END), 0) AS pay_amount_sum, "
            + "COALESCE(SUM(CASE WHEN pay_amount IS NOT NULL AND pay_amount &gt;= 0 AND pay_amount = ROUND(pay_amount, 2) "
            + "AND commission_amount IS NOT NULL AND commission_amount &gt;= 0 "
            + "AND commission_amount = ROUND(commission_amount, 2) THEN commission_amount ELSE 0 END), 0) AS commission_amount_sum, "
            + "COALESCE(SUM(CASE WHEN "
            + "(order_status IN ('PAID','SHIPPED','COMPLETED','REFUNDED') AND (pay_amount IS NULL OR pay_amount &lt; 0 OR pay_amount &lt;&gt; ROUND(pay_amount, 2))) "
            + "OR (order_status IN ('PAID','SHIPPED','COMPLETED') AND (commission_amount IS NULL OR commission_amount &lt; 0 OR commission_amount &lt;&gt; ROUND(commission_amount, 2))) "
            + "OR (order_status = 'UNPAID' AND COALESCE(peer_status, '') NOT IN ('EXPIRED','CANCELLED') "
            + "AND (pay_amount IS NULL OR pay_amount &lt; 0 OR pay_amount &lt;&gt; ROUND(pay_amount, 2))) "
            + "THEN 1 ELSE 0 END), 0) AS amount_anomaly_count "
            + "FROM trade_order WHERE 1 = 1 "
            + "<if test='merchantId != null'>AND merchant_id = #{merchantId} </if>"
            + "<if test='from != null'>AND created_at &gt;= #{from} </if>"
            + "<if test='to != null'>AND created_at &lt; #{to} </if>"
            + "GROUP BY order_status, peer_status"
            + "</script>")
    List<OrderStatisticsAggregate> aggregateOrderStatistics(@Param("merchantId") Long merchantId,
                                                            @Param("from") LocalDateTime from,
                                                            @Param("to") LocalDateTime to);

    /**
     * T34：结算生成时按账期读取已支付订单。只用于结算明细生成，不用于列表/统计接口。
     */
    @Select("SELECT * FROM trade_order WHERE merchant_id = #{merchantId} "
            + "AND created_at >= #{from} AND created_at < #{to} "
            + "AND order_status IN ('PAID','SHIPPED','COMPLETED','REFUNDED') "
            + "ORDER BY created_at, id")
    List<TradeOrder> findFeeBearingByMerchantAndPeriod(@Param("merchantId") Long merchantId,
                                                       @Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to);
}
