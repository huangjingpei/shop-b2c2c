/*
 * 文件：SplitDetailMapper.java
 * 用途：T40 分账明细账本的幂等查询、行锁与退分累计。
 * 维护注意：
 * 1. lockById 使用 SELECT ... FOR UPDATE；退分/状态推进必须在锁内或条件 UPDATE 中完成，禁止先查后改。
 * 2. addReversedAmount 带余额条件，累计退分不得超过 split_amount。
 * 3. 本 Mapper 不调用渠道，只维护本地账本。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.SplitDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Mapper
public interface SplitDetailMapper extends BaseMapper<SplitDetail> {

    default Optional<SplitDetail> findByOrderIdAndAccount(Long orderId, Long paymentAccountId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SplitDetail>()
                .eq(SplitDetail::getOrderId, orderId)
                .eq(SplitDetail::getPaymentAccountId, paymentAccountId)));
    }

    default List<SplitDetail> findByOrderId(Long orderId) {
        return selectList(new LambdaQueryWrapper<SplitDetail>()
                .eq(SplitDetail::getOrderId, orderId)
                .orderByAsc(SplitDetail::getId));
    }

    @Select("SELECT * FROM split_detail WHERE id = #{id} FOR UPDATE")
    SplitDetail lockById(@Param("id") Long id);

    @Update("UPDATE split_detail SET reversed_amount = reversed_amount + #{amount}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND reversed_amount + #{amount} <= split_amount")
    int addReversedAmount(@Param("id") Long id, @Param("amount") BigDecimal amount);
}
