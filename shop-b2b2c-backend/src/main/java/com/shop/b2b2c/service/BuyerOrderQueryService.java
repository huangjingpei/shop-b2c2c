/*
 * 文件：BuyerOrderQueryService.java
 * 用途：先限流，再校验订单/联系信息与独立领取凭证，最后构造买家白名单响应。
 * 维护注意：认证前不得读取并返回卡密或服务凭证；失败消息保持一致，Redis不可用时不能回退匿名查询。失去领取凭证的订单需另建安全恢复流程，不允许仅凭手机号绕过。
 */
package com.shop.b2b2c.service;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.*;
import com.shop.b2b2c.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
public class BuyerOrderQueryService {
    private final TradeOrderMapper orders;
    private final TradeOrderItemMapper items;
    private final MerchantStoreMapper stores;
    private final OrderQueryLimiter limiter;
    public BuyerOrderView lookup(BuyerLookupRequest request, String address) {
        if (request == null) throw new BusinessException(400, BuyerLookupRequest.INVALID);
        var req = request.normalized();
        limiter.check(req.orderSn(), address);
        var order = orders.findByOrderSnAndBuyerContact(req.orderSn(), req.contact())
                .orElseThrow(() -> new BusinessException(400, BuyerLookupRequest.INVALID));
        if (!BuyerLookupRequest.matches(order.getQueryToken(), req.queryToken())) {
            throw new BusinessException(400, BuyerLookupRequest.INVALID);
        }
        return BuyerOrderView.from(order, items.findByOrderSn(order.getOrderSn()),
                stores.findById(order.getMerchantId()).orElse(null));
    }
}
