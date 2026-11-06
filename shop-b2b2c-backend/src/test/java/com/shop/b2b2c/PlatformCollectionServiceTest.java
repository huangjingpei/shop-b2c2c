/*
 * 文件：PlatformCollectionServiceTest.java
 * 用途：T40 平台代收结算骨架用例：默认关闭、阶段一订单拒绝、接收方绑定、金额守恒、幂等与退分上限。
 * 维护注意：
 * 1. 使用 Mockito 隔离，不启动 Spring、不连接数据库、不调用真实分账通道。
 * 2. 只验证服务层与本地账本口径；真实 MySQL 唯一索引、并发行锁与渠道联调仍由用户手动验证。
 * 3. 若未来默认开启平台代收、允许阶段一订单进入分账或放宽金额守恒，本用例必须失败。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.CollectionMode;
import com.shop.b2b2c.entity.MerchantPaymentAccount;
import com.shop.b2b2c.entity.PlatformCollectionSettlement;
import com.shop.b2b2c.entity.SplitDetail;
import com.shop.b2b2c.entity.SplitReverse;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.mapper.MerchantPaymentAccountMapper;
import com.shop.b2b2c.mapper.PlatformCollectionSettlementMapper;
import com.shop.b2b2c.mapper.SplitDetailMapper;
import com.shop.b2b2c.mapper.SplitReverseMapper;
import com.shop.b2b2c.service.PlatformCollectionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformCollectionServiceTest {

    private static final Long MERCHANT_ID = 1L;
    private static final Long ORDER_ID = 11L;

    private final PlatformCollectionSettlementMapper settlementMapper =
            mock(PlatformCollectionSettlementMapper.class);
    private final SplitDetailMapper splitDetailMapper = mock(SplitDetailMapper.class);
    private final SplitReverseMapper splitReverseMapper = mock(SplitReverseMapper.class);
    private final MerchantPaymentAccountMapper paymentAccountMapper =
            mock(MerchantPaymentAccountMapper.class);
    private final PlatformCollectionService service = new PlatformCollectionService(
            settlementMapper, splitDetailMapper, splitReverseMapper, paymentAccountMapper);

    @Test
    void serviceIsDisabledByDefaultAndRejectsBeforeAnyWrite() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prepareSettlement(order(), 21L, "PLAT-1", new BigDecimal("100.00")));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("未启用"));
        verify(settlementMapper, never()).insert(any());
    }

    @Test
    void merchantDirectOrderCannotEnterPlatformSplitLedger() {
        enable();
        TradeOrder order = order();
        order.setCollectionMode(CollectionMode.MERCHANT_DIRECT);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prepareSettlement(order, 21L, "PLAT-1", new BigDecimal("100.00")));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("PLATFORM_COLLECT"));
        verify(settlementMapper, never()).insert(any());
    }

    @Test
    void channelGrossMustEqualOrderPayAmount() {
        enable();
        when(settlementMapper.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prepareSettlement(order(), 21L, "PLAT-1", new BigDecimal("99.99")));

        assertEquals(409, ex.getCode());
        verify(paymentAccountMapper, never()).findEffectiveBound(anyLong(), any(), any());
    }

    @Test
    void missingEffectiveReceiverBindingIsRejected() {
        enable();
        when(settlementMapper.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(paymentAccountMapper.findEffectiveBound(eq(MERCHANT_ID), eq("WECHAT"), any()))
                .thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prepareSettlement(order(), 21L, "PLAT-1", new BigDecimal("100.00")));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("分账接收方"));
        verify(splitDetailMapper, never()).insert(any());
    }

    @Test
    void prepareCreatesSettlementAndSplitWithAmountConservation() {
        enable();
        when(settlementMapper.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(paymentAccountMapper.findEffectiveBound(eq(MERCHANT_ID), eq("WECHAT"), any()))
                .thenReturn(boundReceiver());

        PlatformCollectionSettlement settlement = service.prepareSettlement(
                order(), 21L, "PLAT-1", new BigDecimal("100.00"));

        assertEquals(new BigDecimal("100.00"), settlement.getGrossAmount());
        assertEquals(new BigDecimal("3.00"), settlement.getPlatformRetainAmount());
        assertEquals(new BigDecimal("97.00"), settlement.getMerchantPayableAmount());
        assertEquals("PENDING", settlement.getSettlementStatus());
        verify(settlementMapper).insert(any(PlatformCollectionSettlement.class));

        org.mockito.ArgumentCaptor<SplitDetail> captor =
                org.mockito.ArgumentCaptor.forClass(SplitDetail.class);
        verify(splitDetailMapper).insert(captor.capture());
        SplitDetail split = captor.getValue();
        assertEquals(21L, split.getPaymentAccountId());
        assertEquals(new BigDecimal("3.00"), split.getFeeAmount());
        assertEquals(new BigDecimal("97.00"), split.getSplitAmount());
        assertEquals("PENDING", split.getSplitStatus());
        assertEquals("WECHAT", split.getChannel());
        assertEquals(0, split.getReversedAmount().signum());
    }

    @Test
    void repeatedPrepareReturnsExistingSettlementWithoutSecondInsert() {
        enable();
        PlatformCollectionSettlement existing = new PlatformCollectionSettlement();
        existing.setId(99L);
        existing.setOrderId(ORDER_ID);
        when(settlementMapper.findByOrderId(ORDER_ID)).thenReturn(Optional.of(existing));

        PlatformCollectionSettlement result = service.prepareSettlement(
                order(), 21L, "PLAT-1", new BigDecimal("100.00"));

        assertEquals(99L, result.getId());
        verify(settlementMapper, never()).insert(any());
        verify(splitDetailMapper, never()).insert(any());
    }

    @Test
    void reverseAboveRemainingSplitAmountIsRejected() {
        enable();
        SplitDetail split = splitDetail(new BigDecimal("97.00"), new BigDecimal("90.00"));
        when(splitDetailMapper.lockById(5L)).thenReturn(split);
        when(splitReverseMapper.findByDetailIdAndRefundId(5L, 7L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recordSplitReverse(5L, 7L, "RF-1", new BigDecimal("10.00"), 3L));

        assertEquals(409, ex.getCode());
        verify(splitReverseMapper, never()).insert(any());
        verify(splitDetailMapper, never()).addReversedAmount(anyLong(), any());
    }

    @Test
    void reverseWritesEvidenceAndUpdatesBothLedgers() {
        enable();
        SplitDetail split = splitDetail(new BigDecimal("97.00"), BigDecimal.ZERO);
        when(splitDetailMapper.lockById(5L)).thenReturn(split);
        when(splitReverseMapper.findByDetailIdAndRefundId(5L, 7L)).thenReturn(Optional.empty());
        when(splitDetailMapper.addReversedAmount(5L, new BigDecimal("97.00"))).thenReturn(1);
        when(settlementMapper.addRefundedAmount(ORDER_ID, new BigDecimal("97.00"))).thenReturn(1);

        SplitReverse reverse = service.recordSplitReverse(5L, 7L, "RF-1", new BigDecimal("97.00"), 3L);

        assertEquals("PENDING", reverse.getReverseStatus());
        assertEquals(new BigDecimal("97.00"), reverse.getReverseAmount());
        assertEquals(3L, reverse.getOperatorId());
        assertEquals("RF-1", reverse.getRefundNo());
        assertNotNull(reverse.getCreatedAt());
        verify(splitReverseMapper).insert(reverse);
    }

    @Test
    void repeatedReverseReturnsExistingWithoutSecondBalanceChange() {
        enable();
        SplitDetail split = splitDetail(new BigDecimal("97.00"), new BigDecimal("97.00"));
        SplitReverse existing = new SplitReverse();
        existing.setId(88L);
        when(splitDetailMapper.lockById(5L)).thenReturn(split);
        when(splitReverseMapper.findByDetailIdAndRefundId(5L, 7L)).thenReturn(Optional.of(existing));

        SplitReverse result = service.recordSplitReverse(5L, 7L, "RF-1", new BigDecimal("97.00"), 3L);

        assertEquals(88L, result.getId());
        verify(splitDetailMapper, never()).addReversedAmount(anyLong(), any());
        verify(settlementMapper, never()).addRefundedAmount(anyLong(), any());
    }

    private void enable() {
        ReflectionTestUtils.setField(service, "platformCollectEnabled", true);
    }

    private TradeOrder order() {
        TradeOrder order = new TradeOrder();
        order.setId(ORDER_ID);
        order.setOrderSn("SN-T40");
        order.setMerchantId(MERCHANT_ID);
        order.setPayAmount(new BigDecimal("100.00"));
        order.setFeeAmount(new BigDecimal("3.00"));
        order.setPayChannel("WECHAT");
        order.setCollectionMode(CollectionMode.PLATFORM_COLLECT);
        return order;
    }

    private MerchantPaymentAccount boundReceiver() {
        MerchantPaymentAccount account = new MerchantPaymentAccount();
        account.setId(21L);
        account.setMerchantId(MERCHANT_ID);
        account.setChannel("WECHAT");
        account.setChannelMerchantId("REC-1");
        account.setBindingStatus("BOUND");
        account.setSettlementAccountId(31L);
        return account;
    }

    private SplitDetail splitDetail(BigDecimal splitAmount, BigDecimal reversedAmount) {
        SplitDetail split = new SplitDetail();
        split.setId(5L);
        split.setOrderId(ORDER_ID);
        split.setOrderSn("SN-T40");
        split.setMerchantId(MERCHANT_ID);
        split.setSplitAmount(splitAmount);
        split.setReversedAmount(reversedAmount);
        return split;
    }
}
