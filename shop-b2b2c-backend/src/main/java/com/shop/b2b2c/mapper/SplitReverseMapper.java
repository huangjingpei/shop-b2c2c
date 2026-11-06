/*
 * 文件：SplitReverseMapper.java
 * 用途：T40 退分账本的幂等查询与持久化。
 * 维护注意：
 * 1. 同一 split_detail_id + refund_id 唯一；重试先查原记录，禁止重复插入。
 * 2. 本 Mapper 不调用渠道，不负责状态机推进；渠道结果由服务层按真实回调写入。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.SplitReverse;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SplitReverseMapper extends BaseMapper<SplitReverse> {

    default Optional<SplitReverse> findByDetailIdAndRefundId(Long splitDetailId, Long refundId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SplitReverse>()
                .eq(SplitReverse::getSplitDetailId, splitDetailId)
                .eq(SplitReverse::getRefundId, refundId)));
    }

    default List<SplitReverse> findByDetailId(Long splitDetailId) {
        return selectList(new LambdaQueryWrapper<SplitReverse>()
                .eq(SplitReverse::getSplitDetailId, splitDetailId)
                .orderByAsc(SplitReverse::getId));
    }
}
