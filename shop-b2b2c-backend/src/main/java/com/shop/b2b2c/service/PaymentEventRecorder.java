/*
 * 文件：PaymentEventRecorder.java
 * 用途：支付流水状态记录器；正常状态推进与业务事务同生共死，避免“事件 SUCCESS 先于订单提交”造成资损假成功 (R13/T05)。
 * 维护注意：
 *  1. 事件初始占位由 PaymentEventServiceImpl.claimEvent 在业务事务之外的独立事务完成；本类不提供初始写入，
 *     避免参与主事务的方法抛出唯一键异常而把主事务标记为 rollback-only (G34)。
 *  2. recordEventStatus 使用默认传播，加入调用方事务；外层回滚时事件不会残留 SUCCESS。
 *  3. 只有业务事务失败后的补偿审计 recordFailureAudit 使用独立事务，保证失败态可见；它只应在事务完成回滚后
 *     或 PaymentEventReconcileService 的无外层事务补偿扫描中调用。
 *  4. 失败审计不得把其他订单的事件改写，也不得把已完成支付的事件降级；重复冲突由调用方跳过审计。
 *  5. 事件状态与订单状态必须保持一致，禁止在业务事务内另开事件表或第二套状态机。
 *  6. T+1：funds_received 必须由调用方显式给出（1-已到账、0-明确未到账、NULL-未标注），
 *     本类不提供默认值，避免漏写被当成“无需流水”而失去对账可见性。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.mapper.PaymentEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventRecorder {

    private static final int ERROR_MESSAGE_LIMIT = 512;

    private final PaymentEventMapper paymentEventMapper;

    /**
     * 回写事件状态与资金标记。
     * <b>陷阱</b>：updateById 走 MyBatis-Plus 默认 NOT_NULL 字段策略，实体字段为 null 时不会拼进 UPDATE，
     * 即“把 funds_received 清空为 NULL”在这里会静默不生效（本次传 null 只是“不改”，并非“写到 NULL”）。
     * 若将来需要真正清空资金标记，必须使用显式 SET funds_received = NULL 的自定义 Mapper SQL，
     * 不得依赖本方法或把 null 当成清空语义（G44 已登记）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordEventStatus(Long eventId, String status, Integer fundsReceived, String errorMessage) {
        if (eventId == null) return;
        PaymentEvent pe = paymentEventMapper.selectById(eventId);
        if (pe != null) {
            pe.setEventStatus(status);
            pe.setFundsReceived(fundsReceived);
            pe.setErrorMessage(trim(errorMessage));
            paymentEventMapper.updateById(pe);
        }
    }

    /**
     * 业务事务已回滚后的补偿审计。
     * 使用独立事务是为了让失败态在业务数据回滚后仍然可追溯；调用点必须位于事务边界之外，
     * 避免在主连接池耗尽时形成自锁。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordFailureAudit(PaymentEvent snapshot, String status, Integer fundsReceived, String errorMessage) {
        if (snapshot == null || snapshot.getChannel() == null || snapshot.getPayTradeNo() == null) {
            return;
        }
        PaymentEvent existing = paymentEventMapper.findByChannelAndTradeNo(
                snapshot.getChannel(), snapshot.getPayTradeNo());
        if (existing != null) {
            // 流水号已被其他订单占用时绝不改写对方事件。
            if (!Objects.equals(existing.getOrderSn(), snapshot.getOrderSn())) {
                log.warn("支付事件流水号归属冲突，跳过失败审计 channel={}, tradeNo={}",
                        snapshot.getChannel(), snapshot.getPayTradeNo());
                return;
            }
            // 已验证履约成功的事件不得被后续重放异常降级。
            if ("SUCCESS".equals(existing.getEventStatus())) {
                return;
            }
            existing.setEventStatus(status);
            existing.setFundsReceived(fundsReceived);
            existing.setErrorMessage(trim(errorMessage));
            paymentEventMapper.updateById(existing);
            return;
        }
        snapshot.setId(null);
        snapshot.setEventStatus(status);
        snapshot.setFundsReceived(fundsReceived);
        snapshot.setErrorMessage(trim(errorMessage));
        paymentEventMapper.insert(snapshot);
    }

    private String trim(String message) {
        if (message == null) return null;
        return message.length() <= ERROR_MESSAGE_LIMIT ? message : message.substring(0, ERROR_MESSAGE_LIMIT);
    }
}
