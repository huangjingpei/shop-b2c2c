/*
 * 文件：MerchantSettlementItemMapper.java
 * 用途：结算明细数据访问（T34），支持按批次重算覆盖与按批次查询逐笔明细。
 * 维护注意：DRAFT 批次重算时先删旧明细再重建；CONFIRMED 批次不得删除或重建明细。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantSettlementItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MerchantSettlementItemMapper extends BaseMapper<MerchantSettlementItem> {

    @Delete("DELETE FROM merchant_settlement_item WHERE batch_id = #{batchId}")
    int deleteByBatchId(@Param("batchId") Long batchId);

    default List<MerchantSettlementItem> findByBatchId(Long batchId) {
        return selectList(new LambdaQueryWrapper<MerchantSettlementItem>()
                .eq(MerchantSettlementItem::getBatchId, batchId)
                .orderByAsc(MerchantSettlementItem::getId));
    }

    /**
     * T35：逐笔明细查询，支持按订单号、商品类型、退款单号、支付事件ID过滤。
     */
    default List<MerchantSettlementItem> search(Long batchId, String orderSn, Integer goodsType,
                                                String refundNo, Long paymentEventId) {
        return selectList(new LambdaQueryWrapper<MerchantSettlementItem>()
                .eq(MerchantSettlementItem::getBatchId, batchId)
                .eq(orderSn != null && !orderSn.isBlank(), MerchantSettlementItem::getOrderSn, orderSn)
                .eq(goodsType != null, MerchantSettlementItem::getGoodsType, goodsType)
                .eq(refundNo != null && !refundNo.isBlank(), MerchantSettlementItem::getRefundNo, refundNo)
                .eq(paymentEventId != null, MerchantSettlementItem::getPaymentEventId, paymentEventId)
                .orderByAsc(MerchantSettlementItem::getId));
    }
}
