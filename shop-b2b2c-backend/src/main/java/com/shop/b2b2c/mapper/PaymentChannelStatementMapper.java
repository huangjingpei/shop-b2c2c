/*
 * 文件：PaymentChannelStatementMapper.java
 * 用途：渠道对账流水数据访问（T35）。
 * 维护注意：按渠道+流水号唯一定位；按商户/时间查询必须带边界，避免全表扫描。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.PaymentChannelStatement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface PaymentChannelStatementMapper extends BaseMapper<PaymentChannelStatement> {

    @Select("SELECT * FROM payment_channel_statement WHERE channel = #{channel} "
            + "AND channel_trade_no = #{tradeNo} LIMIT 1")
    PaymentChannelStatement findByChannelAndTradeNoRaw(@Param("channel") String channel,
                                                       @Param("tradeNo") String tradeNo);

    default Optional<PaymentChannelStatement> findByChannelAndTradeNo(String channel, String tradeNo) {
        return Optional.ofNullable(findByChannelAndTradeNoRaw(channel, tradeNo));
    }

    default List<PaymentChannelStatement> findByMerchantAndPeriod(Long merchantId, LocalDateTime from,
                                                                  LocalDateTime to) {
        return selectList(new LambdaQueryWrapper<PaymentChannelStatement>()
                .eq(PaymentChannelStatement::getMerchantId, merchantId)
                .ge(PaymentChannelStatement::getTradeTime, from)
                .lt(PaymentChannelStatement::getTradeTime, to)
                .orderByAsc(PaymentChannelStatement::getId));
    }

    @Select("SELECT COUNT(*) FROM payment_channel_statement WHERE merchant_id = #{merchantId} "
            + "AND channel = #{channel} AND trade_time >= #{from} AND trade_time < #{to}")
    int countByMerchantChannelPeriod(@Param("merchantId") Long merchantId,
                                     @Param("channel") String channel,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);
}
