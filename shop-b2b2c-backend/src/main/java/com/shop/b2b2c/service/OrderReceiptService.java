/*
 * 文件：OrderReceiptService.java
 * 用途：F07 买家确认收货：SHIPPED → COMPLETED，写 finish_time，进入售后期。
 * 维护注意：
 *  1. 买家身份凭 orderSn+buyerContact+queryToken 校验，复用 BuyerLookupRequest 的匹配规则；不依赖登录态。
 *  2. 只有 SHIPPED 实物订单可确认收货；已 COMPLETED 重复提交按幂等成功返回，不重复写 finish_time。
 *  3. 状态迁移必须在订单行锁内完成；不得覆盖退款/仲裁中的状态。
 *  4. queryToken 只放请求体，控制器不得回显或记录。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.BuyerLookupRequest;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderReceiptService {

    private final TradeOrderMapper orders;

    @Transactional(rollbackFor = Exception.class)
    public TradeOrder confirm(BuyerLookupRequest request) {
        BuyerLookupRequest req = request == null ? null : request.normalized();
        if (req == null) {
            throw new BusinessException(400, BuyerLookupRequest.INVALID);
        }
        TradeOrder order = orders.findByOrderSnAndBuyerContact(req.orderSn(), req.contact())
                .orElseThrow(() -> new BusinessException(400, BuyerLookupRequest.INVALID));
        if (!BuyerLookupRequest.matches(order.getQueryToken(), req.queryToken())) {
            throw new BusinessException(400, BuyerLookupRequest.INVALID);
        }
        // 行锁后重新读取，避免确认收货与退款/发货并发时基于旧状态写入。
        TradeOrder locked = Optional.ofNullable(orders.lockById(order.getId()))
                .orElseThrow(() -> new BusinessException(400, BuyerLookupRequest.INVALID));
        if ("COMPLETED".equals(locked.getOrderStatus())) {
            return locked;
        }
        if (!"SHIPPED".equals(locked.getOrderStatus())) {
            throw new BusinessException(409, "当前订单状态不可确认收货");
        }
        if (!Integer.valueOf(1).equals(locked.getGoodsType())) {
            throw new BusinessException(409, "仅实物订单需要确认收货");
        }
        locked.setOrderStatus("COMPLETED");
        locked.setFinishTime(LocalDateTime.now());
        orders.save(locked);
        return locked;
    }
}
