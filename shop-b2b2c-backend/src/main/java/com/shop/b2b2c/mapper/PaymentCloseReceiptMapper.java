/*
 * 文件：PaymentCloseReceiptMapper.java
 * 用途：F08 关单回执写入与重试候选查询。
 * 维护注意：
 *  1. 尝试序号在插入时由同订单+渠道的当前最大值递增计算，避免应用层先查后写的竞态；并发下允许重复序号，扫描以最新 attempted_at 为准。
 *  2. 重试候选只取“最新一条为 FAILED、未达次数上限、已过退避间隔且在扫描窗口内”的回执，SKIPPED/SUCCESS 不进入重试。
 *  3. 查询只返回 order_sn，不返回 reason/凭据；订单状态与渠道可用性由调用方二次校验。
 *  4. 本 Mapper 不提供 update/delete：回执只增不改，留痕不随重试结果回写。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.PaymentCloseReceipt;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PaymentCloseReceiptMapper extends BaseMapper<PaymentCloseReceipt> {

    @Insert("INSERT INTO payment_close_receipt "
            + "(order_sn, merchant_id, channel, attempt_no, result, reason, attempted_at, created_at) "
            + "SELECT #{orderSn}, #{merchantId}, #{channel}, COALESCE(MAX(attempt_no), 0) + 1, "
            + "#{result}, #{reason}, #{attemptedAt}, #{attemptedAt} "
            + "FROM payment_close_receipt WHERE order_sn = #{orderSn} AND channel = #{channel}")
    int insertAttempt(@Param("orderSn") String orderSn,
                      @Param("merchantId") Long merchantId,
                      @Param("channel") String channel,
                      @Param("result") String result,
                      @Param("reason") String reason,
                      @Param("attemptedAt") LocalDateTime attemptedAt);

    @Select("SELECT r.order_sn FROM payment_close_receipt r "
            + "WHERE r.result = 'FAILED' AND r.attempted_at >= #{since} AND r.attempted_at <= #{until} "
            + "AND r.attempt_no < #{maxAttempts} "
            + "AND NOT EXISTS (SELECT 1 FROM payment_close_receipt n "
            + "WHERE n.order_sn = r.order_sn AND n.channel = r.channel "
            + "AND (n.attempted_at > r.attempted_at OR (n.attempted_at = r.attempted_at AND n.id > r.id))) "
            + "ORDER BY r.attempted_at LIMIT #{limit}")
    List<String> findRetryableOrderSn(@Param("since") LocalDateTime since,
                                      @Param("until") LocalDateTime until,
                                      @Param("maxAttempts") int maxAttempts,
                                      @Param("limit") int limit);
}
