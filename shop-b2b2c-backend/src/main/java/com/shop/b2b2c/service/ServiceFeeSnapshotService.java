/*
 * 文件：ServiceFeeSnapshotService.java
 * 用途：下单时固化订单级服务费快照（T34）：计费基数、费率、标准服务费、应收/减免、价税分离。
 * 维护注意：
 *  1. 费率来源暂时为 merchant_store.commission_rate（T39 费率政策落地后改为政策版本来源）；快照一旦写入不得随商家费率调整回溯。
 *  2. 允许 0 费率（推广期）与 1%~10% 正常费率；其他费率在下单时拒绝，避免生成无法解释的账单。
 *  3. 免税率为系统配置，未配置时默认 0.06，不允许把税额默认为 0 掩盖税负。
 *  4. 本服务只做计算与字段填充，不写库；调用方仍在原订单创建事务中保存。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.TradeOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class ServiceFeeSnapshotService {

    private static final BigDecimal BPS_DENOMINATOR = new BigDecimal("10000");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    @Value("${shop.settlement.fee-tax-rate:0.06}")
    private BigDecimal feeTaxRate = new BigDecimal("0.06");

    /**
     * 按当前商家费率生成订单快照，并同步维护既有的 commissionAmount / merchantSettleAmount 字段，
     * 保证 T33 退款冲正仍按 commission_amount 口径工作。
     */
    public void apply(TradeOrder order, MerchantStore store, BigDecimal goodsAmount) {
        BigDecimal rate = store.getCommissionRate() != null
                ? store.getCommissionRate() : new BigDecimal("3.00");
        int rateBps = rate.multiply(ONE_HUNDRED).setScale(0, RoundingMode.HALF_UP).intValue();
        if (rateBps != 0 && (rateBps < 100 || rateBps > 1000)) {
            throw new BusinessException(409, "服务费费率必须为 0%（推广期）或 1%~10%");
        }

        BigDecimal base = goodsAmount.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal standard = base.multiply(BigDecimal.valueOf(rateBps))
                .divide(BPS_DENOMINATOR, 2, RoundingMode.HALF_UP);
        boolean promotion = rateBps == 0;
        BigDecimal payable = promotion ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : standard;
        BigDecimal waived = promotion ? standard : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        BigDecimal taxRate = feeTaxRate == null ? BigDecimal.ZERO : feeTaxRate;
        if (taxRate.signum() < 0) {
            throw new BusinessException(500, "服务费税率配置非法");
        }
        BigDecimal net = payable.signum() == 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : payable.divide(BigDecimal.ONE.add(taxRate), 2, RoundingMode.HALF_UP);
        BigDecimal tax = payable.subtract(net);

        order.setGoodsAmount(base);
        order.setMerchantDiscountAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        order.setPlatformDiscountAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        order.setShippingAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        order.setOrderTaxAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        order.setFeePolicyId(null);
        order.setFeeRateBps(rateBps);
        order.setFeeRateType(promotion ? "PROMOTION" : "NORMAL");
        order.setFeeBaseAmount(base);
        order.setFeeStandardAmount(standard);
        order.setFeeAmount(payable);
        order.setFeeWaivedAmount(waived);
        order.setFeeNetAmount(net);
        order.setFeeTaxAmount(tax);
        order.setFeeTaxRate(taxRate.setScale(4, RoundingMode.HALF_UP));

        order.setCommissionRate(rate);
        order.setCommissionAmount(payable);
        order.setMerchantSettleAmount((order.getPayAmount() == null ? base : order.getPayAmount()).subtract(payable));
    }

    /**
     * 旧订单（T34 之前创建）没有快照字段时的兜底口径：以 commission_amount 作为应收服务费。
     */
    public BigDecimal resolveFeeAmount(TradeOrder order) {
        if (order.getFeeAmount() != null) {
            return order.getFeeAmount().setScale(2, RoundingMode.HALF_UP);
        }
        if (order.getCommissionAmount() != null) {
            return order.getCommissionAmount().setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    public int resolveRateBps(TradeOrder order) {
        if (order.getFeeRateBps() != null && order.getFeeRateBps() > 0) {
            return order.getFeeRateBps();
        }
        if (order.getCommissionRate() == null) {
            return 0;
        }
        return order.getCommissionRate().multiply(ONE_HUNDRED).setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
