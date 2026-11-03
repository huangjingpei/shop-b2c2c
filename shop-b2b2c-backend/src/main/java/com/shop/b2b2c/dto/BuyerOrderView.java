/*
 * 文件：BuyerOrderView.java
 * 用途：通过领取凭证验证后的买家订单白名单，按履约状态控制卡密、服务及物流展示。
 * 维护注意：不包含queryToken、付款分享token、付款人ID、渠道流水或佣金。卡密只在类型2且COMPLETED时返回；定制凭证只在类型3完成时返回。不可替换为原实体直接序列化。
 */
package com.shop.b2b2c.dto;
import com.shop.b2b2c.common.PublicStoreView;
import com.shop.b2b2c.entity.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
public record BuyerOrderView(OrderInfo order, List<ItemInfo> items, PublicStoreView store) {
    public record OrderInfo(String orderSn, String orderStatus, Integer goodsType, BigDecimal payAmount,
                            LocalDateTime createdAt, String serviceVoucher, String logisticsCompany,
                            String trackingNumber, LocalDateTime deliveryTime) {}
    public record ItemInfo(Long id, String productTitle, String specInfo, BigDecimal price,
                           Integer quantity, BigDecimal subtotal, String cardDetails) {}
    public static BuyerOrderView from(TradeOrder order, List<TradeOrderItem> items, MerchantStore store) {
        boolean completed = "COMPLETED".equals(order.getOrderStatus());
        boolean cards = completed && Integer.valueOf(2).equals(order.getGoodsType());
        boolean service = completed && Integer.valueOf(3).equals(order.getGoodsType());
        boolean shipping = Integer.valueOf(1).equals(order.getGoodsType())
                && Set.of("SHIPPED", "COMPLETED").contains(order.getOrderStatus());
        return new BuyerOrderView(new OrderInfo(order.getOrderSn(), order.getOrderStatus(), order.getGoodsType(),
                order.getPayAmount(), order.getCreatedAt(), service ? order.getServiceVoucher() : null,
                shipping ? order.getLogisticsCompany() : null, shipping ? order.getTrackingNumber() : null,
                shipping ? order.getDeliveryTime() : null), items.stream().map(item -> new ItemInfo(item.getId(),
                item.getProductTitle(), item.getSpecInfo(), item.getPrice(), item.getQuantity(), item.getSubtotal(),
                cards ? item.getCardDetails() : null)).toList(), PublicStoreView.from(store));
    }
}
