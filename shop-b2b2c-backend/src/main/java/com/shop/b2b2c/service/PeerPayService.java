/*
 * 文件：PeerPayService.java
 * 用途：代付建单、预占、授权释放、到期处理及内部落账服务契约。
 * 维护注意：notifyPaymentSuccess仅供未来可信渠道适配器调用，不能直接公开HTTP映射；适配器须先验签并核对资金。修改取消签名或ownerToken语义时须同步调用方和回归测试。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.dto.PeerPayDTOs;
import com.shop.b2b2c.entity.PlatformAdConfig;
import com.shop.b2b2c.entity.TradeOrder;

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
    PeerPayDTOs.PreLockResult preLockInventory(String token);

    /**
     * 付款人主动关闭/离开收银台：主动释放预占锁
     */
    void cancelPreLock(String token, String ownerToken);

    void expireReservation(Long orderId);

    /**
     * 内部支付落账入口，不允许直接暴露为HTTP接口。
     * 未来渠道适配器必须先验签、核对金额/商户/币种并持久化支付事件，再调用本方法。
     * 当前没有真实支付适配器；生产购买只建待付款订单。
     */
    TradeOrder notifyPaymentSuccess(String token, String payerId, String payTradeNo, String channel);

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
