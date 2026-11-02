/*
 * 文件：TradeOrderItem.java
 * 用途：订单商品、规格、成交价格及交付卡密快照模型。
 * 维护注意：快照不应随当前商品价格或标题被覆盖；cardDetails为敏感资产，读取需订单权限。订单、明细、库存和发卡必须保持同一事务语义。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

@Data
@TableName("trade_order_item")
public class TradeOrderItem {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;
    private String orderSn;
    private Long productId;
    private Long skuId;
    private String skuCode;
    private String productTitle;
    private String specInfo;
    private BigDecimal price;
    private Integer quantity;
    private BigDecimal subtotal;
    private String cardDetails; // 虚拟卡密售出内容快照
}
