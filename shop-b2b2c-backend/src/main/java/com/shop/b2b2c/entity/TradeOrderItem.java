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
