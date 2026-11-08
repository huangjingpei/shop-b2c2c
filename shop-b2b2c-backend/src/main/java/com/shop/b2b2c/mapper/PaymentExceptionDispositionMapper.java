/*
 * 文件：PaymentExceptionDispositionMapper.java
 * 用途：F08 支付异常人工处置记录的数据访问，支撑按事件查询与唯一约束兜底。
 * 维护注意：
 *  1. 本 Mapper 只用于插入与按 event_id 查询；处置记录只增不改，不得新增 update/delete 业务方法。
 *  2. event_id 有唯一约束；并发重复提交由数据库兜底，Service 捕获 DuplicateKeyException 转 409。
 *  3. 查询结果含 note 与 operator_id，仅供超管队列投影使用，不得下发给商家端或公开接口。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.PaymentExceptionDisposition;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface PaymentExceptionDispositionMapper extends BaseMapper<PaymentExceptionDisposition> {

    default Optional<PaymentExceptionDisposition> findByEventId(Long eventId) {
        if (eventId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<PaymentExceptionDisposition>()
                .eq(PaymentExceptionDisposition::getEventId, eventId)));
    }
}
