/*
 * 文件：PeerPayService.java
 * 用途：代付建单、预占、授权释放、到期处理及内部落账服务契约。
 * 维护注意：
 *  1. notifyPaymentSuccess 仅供可信渠道适配器调用，不能直接公开 HTTP 映射；适配器须先验签。
 *  2. 落账入参必须携带渠道实收金额/币种/商户号，禁止由实现从订单应付金额反填 (T06)。
 *  3. T10/Q17：ownerToken 只能由服务端生成并仅随预占响应返回；客户端幂等键不得恢复/取消预占；同一预占最多重发 3 次。
 *  4. 修改取消签名或 ownerToken 语义时须同步调用方、前端 (T29) 和回归测试。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.dto.PeerPayDTOs;
import com.shop.b2b2c.entity.PlatformAdConfig;
import com.shop.b2b2c.entity.TradeOrder;

import java.math.BigDecimal;
import java.util.Map;

public interface PeerPayService {

    /**
     * 买家发起求助代付 (生成待付短链与海报，两段式 Stage 1: 意向不扣物理库存)
     */
    Map<String, Object> createBuyerPeerPay(PeerPayDTOs.BuyerCreateRequest req);

    /**
     * 卖家代客推单 (生成定制账单代付码/短链)
     */
    Map<String, Object> createPushPayOrder(PeerPayDTOs.PushPayCreateRequest req, Long merchantId);

    /**
     * 免注册公开代付详情查询 (含自愈到期检查、脱敏买家隐私)
     */
    PeerPayDTOs.DetailResponse getPeerPayDetail(String token);

    /**
     * 两段式核心：调起支付收银台时原子预占库存 (锁 15 分钟)
     */
    default PeerPayDTOs.PreLockResult preLockInventory(String token) {
        return preLockInventory(token, null, null);
    }

    /**
     * T10：attemptKey 仅是客户端幂等尝试键，不能作为所有权凭证恢复预占。
     */
    default PeerPayDTOs.PreLockResult preLockInventory(String token, String attemptKey) {
        return preLockInventory(token, attemptKey, null);
    }

    /**
     * 两段式核心：调起支付收银台时原子预占库存 (锁 15 分钟)。
     * 恢复已持有预占时必须提交服务端上次签发的 ownerToken；同一预占最多重发 3 次 (Q17)。
     */
    PeerPayDTOs.PreLockResult preLockInventory(String token, String attemptKey, String presentedOwnerToken);

    /**
     * 付款人主动关闭/离开收银台：主动释放预占锁
     */
    void cancelPreLock(String token, String ownerToken);

    void expireReservation(Long orderId);

    /**
     * 内部支付落账入口，不允许直接暴露为HTTP接口。
     * 渠道适配器必须先验签，并把渠道实收金额、币种、商户号原样传入；实现层不得从订单反填。
     * 订单已支付时仍须完成要素校验并落第二笔 DUPLICATE_PAYMENT 事件，不得静默返回。
     */
    TradeOrder notifyPaymentSuccess(String token, String payerId, String payTradeNo, String channel,
                                    BigDecimal receivedAmount, String currency, Long channelMerchantId);

    /**
     * 卖家录入交付凭证完成【线上服务】履约 (锁定退款仲裁限制)
     */
    void fulfillVirtualOrder(Long orderId, String serviceVoucher, Long currentMerchantId);

    /**
     * 获取代付成功页【我也想开店】平台招商引流位配置
     */
    PlatformAdConfig getSuccessAdBanner();

    /**
     * 重新生成代付海报 (Base64)
     */
    String regeneratePoster(String token);
}
