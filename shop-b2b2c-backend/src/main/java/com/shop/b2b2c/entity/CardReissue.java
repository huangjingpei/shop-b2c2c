/*
 * 文件：CardReissue.java
 * 用途：卡密补发的只追加记录，保存源已售卡、新替代卡、订单号、操作人、原因及可售数量变化。
 * 维护注意：sourceCardId同时是自然幂等主键（一张已售卡最多补发一次），newCardId唯一；记录不含卡密内容，
 *           不得通过业务接口覆盖或删除。原卡保持已售状态不回滚交付事实，新卡绑定同一订单号；订单全额退款时
 *           两张卡都会被 voidSoldCardsByOrderSn 一并作废（status=1→2），属于既有退款语义。
 */
package com.shop.b2b2c.entity;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class CardReissue {
    /** 被补发的已售卡ID（主键）。 */
    private Long sourceCardId;
    /** 补发的新卡ID（同事务从同SKU未售池取出并绑定原订单）。 */
    private Long newCardId;
    private Long merchantId;
    private Long productId;
    private Long skuId;
    /** 原售出订单号，新卡复用同一订单号。 */
    private String orderSn;
    private Long actorId;
    /** 补发原因（必填，最长255字符）。 */
    private String reason;
    /** 补发前SKU可售库存。 */
    private Integer beforeStock;
    /** 补发后SKU可售库存（before-1，新卡从库存消耗）。 */
    private Integer afterStock;
    private LocalDateTime createdAt;
}
