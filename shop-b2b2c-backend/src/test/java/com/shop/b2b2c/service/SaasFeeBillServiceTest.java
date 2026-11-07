/*
 * 文件：SaasFeeBillServiceTest.java
 * 用途：F10 SaaS 月费账单服务单元测试：幂等生成、免收判定、缴费确认、逾期扫描与查询参数校验。
 * 维护注意：
 *  1. 只使用 Mockito 模拟持久层与通知/复核服务，不连接数据库；真实事务与唯一键由用户 MySQL 环境验证。
 *  2. 代理细化规则（次月 10 日到期、TRIAL/EXEMPTED/免费期覆盖当月末日免收、仅线下缴费）变更时必须同步断言。
 *  3. 断言金额一律两位小数；WAIVED 时 amount=0.00 且 standardAmount 保留配置原值。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.SaasFeeBill;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.SaasFeeBillMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaasFeeBillServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Mock SaasFeeBillMapper bills;
    @Mock MerchantStoreMapper stores;
    @Mock MerchantNotificationService notifications;
    @Mock FinanceApprovalGuard approvalGuard;
    @InjectMocks SaasFeeBillService service;

    private MerchantStore store(String feeStatus, String monthlyFee, LocalDateTime freeUntil) {
        MerchantStore store = new MerchantStore();
        store.setId(7L);
        store.setStatus(1);
        store.setFeeStatus(feeStatus);
        store.setMonthlyFee(new BigDecimal(monthlyFee));
        store.setFeeFreeUntil(freeUntil);
        return store;
    }

    private SaasFeeBill bill(String status, String amount) {
        SaasFeeBill bill = new SaasFeeBill();
        bill.setId(11L);
        bill.setBillNo("SAAS-7-202610");
        bill.setMerchantId(7L);
        bill.setBillingMonth("2026-10");
        bill.setStandardAmount(new BigDecimal("99.00"));
        bill.setAmount(new BigDecimal(amount));
        bill.setStatus(status);
        bill.setDueDate(LocalDate.of(2026, 11, 10));
        return bill;
    }

    @Test
    @DisplayName("已结束月份生成待缴账单：金额/到期日/站内信正确")
    void generatePendingBill() {
        YearMonth month = YearMonth.now(ZONE).minusMonths(1);
        when(bills.findByMerchantMonth(eq(7L), anyString())).thenReturn(Optional.empty());
        when(stores.selectById(7L)).thenReturn(store("NORMAL", "99.00", null));
        when(bills.insert(any(SaasFeeBill.class))).thenAnswer(inv -> {
            inv.getArgument(0, SaasFeeBill.class).setId(11L);
            return 1;
        });

        SaasFeeBill created = service.generateForMerchant(7L, month, 1L);

        assertEquals("PENDING", created.getStatus());
        assertEquals(new BigDecimal("99.00"), created.getAmount());
        assertEquals(new BigDecimal("99.00"), created.getStandardAmount());
        assertEquals(month.plusMonths(1).atDay(10), created.getDueDate());
        verify(notifications).notify(eq(MerchantNotificationService.BIZ_SAAS_FEE_ISSUED), eq(11L),
                eq(MerchantNotificationService.CHANNEL_INAPP), eq("SAAS_FEE_BILL_ISSUED"), eq("MERCHANT:7"), anyMap());
    }

    @Test
    @DisplayName("同店同月已存在时原样返回，不重算、不重复通知")
    void generateIsIdempotent() {
        SaasFeeBill existing = bill("PAID", "99.00");
        when(bills.findByMerchantMonth(7L, "2026-10")).thenReturn(Optional.of(existing));

        SaasFeeBill result = service.generateForMerchant(7L, YearMonth.of(2026, 10), 1L);

        assertSame(existing, result);
        verify(bills, never()).insert(any());
        verify(notifications, never()).notify(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("未结束月份或未来月份拒绝出账")
    void futureMonthRejected() {
        YearMonth current = YearMonth.now(ZONE);
        for (YearMonth month : List.of(current, current.plusMonths(1))) {
            assertThrows(BusinessException.class, () -> service.generateForMerchant(7L, month, 1L));
        }
        verify(stores, never()).selectById(any());
    }

    @Test
    @DisplayName("0 月费/EXEMPTED/TRIAL/免费期覆盖当月末日均记 WAIVED 且应收为 0")
    void waivedRules() {
        YearMonth month = YearMonth.of(2026, 10);
        LocalDateTime coversMonth = month.atEndOfMonth().atTime(23, 59);
        Object[][] cases = {
                {"NORMAL", "0.00", null},
                {"EXEMPTED", "99.00", null},
                {"TRIAL", "99.00", null},
                {"NORMAL", "99.00", coversMonth}
        };
        for (Object[] item : cases) {
            reset(bills, stores, notifications);
            when(bills.findByMerchantMonth(eq(7L), eq("2026-10"))).thenReturn(Optional.empty());
            when(stores.selectById(7L)).thenReturn(store((String) item[0], (String) item[1],
                    (LocalDateTime) item[2]));
            when(bills.insert(any(SaasFeeBill.class))).thenAnswer(inv -> {
                inv.getArgument(0, SaasFeeBill.class).setId(11L);
                return 1;
            });

            SaasFeeBill created = service.generateForMerchant(7L, month, 1L);

            assertEquals("WAIVED", created.getStatus(), "case=" + item[0]);
            assertEquals(new BigDecimal("0.00"), created.getAmount(), "case=" + item[0]);
            assertNotNull(created.getWaiveReason());
            assertEquals(0, created.getStandardAmount().subtract(new BigDecimal((String) item[1]).setScale(2)).intValue(),
                    "豁免前标准额必须保留");
            verify(notifications, never()).notify(eq(MerchantNotificationService.BIZ_SAAS_FEE_ISSUED),
                    any(), any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("免收期未覆盖当月则正常收费")
    void freeUntilNotCoveringMonthCharges() {
        YearMonth month = YearMonth.of(2026, 10);
        when(bills.findByMerchantMonth(eq(7L), eq("2026-10"))).thenReturn(Optional.empty());
        when(stores.selectById(7L)).thenReturn(store("NORMAL", "99.00", LocalDateTime.of(2026, 10, 15, 0, 0)));
        when(bills.insert(any(SaasFeeBill.class))).thenAnswer(inv -> {
            inv.getArgument(0, SaasFeeBill.class).setId(11L);
            return 1;
        });

        SaasFeeBill created = service.generateForMerchant(7L, month, 1L);

        assertEquals("PENDING", created.getStatus());
        assertEquals(new BigDecimal("99.00"), created.getAmount());
        assertNull(created.getWaiveReason());
    }

    @Test
    @DisplayName("缴费确认：待缴账单置 PAID 并落复核人；凭证号必填")
    void confirmPendingBill() {
        SaasFeeBill pending = bill("PENDING", "99.00");
        when(bills.findByBillNo("SAAS-7-202610")).thenReturn(Optional.of(pending));
        when(bills.lockById(11L)).thenReturn(pending);
        when(approvalGuard.verify("SaaS 月费缴费确认", 2L, 3L)).thenReturn(FinanceApprovalGuard.MODE_DUAL_APPROVAL);
        when(approvalGuard.resolveReviewer(2L, 3L)).thenReturn(3L);

        SaasFeeBill paid = service.confirmPayment("SAAS-7-202610", 2L, 3L, "OFFLINE", "V-001", "银行转账");

        assertEquals("PAID", paid.getStatus());
        assertEquals(Long.valueOf(3L), paid.getConfirmedBy());
        assertEquals(Long.valueOf(2L), paid.getPaidBy());
        assertEquals("OFFLINE", paid.getPaymentMethod());
        assertEquals("V-001", paid.getVoucherNo());
        assertEquals(FinanceApprovalGuard.MODE_DUAL_APPROVAL, paid.getApprovalMode());
        assertNotNull(paid.getPaidAt());
        verify(bills).updateById(pending);
        verify(notifications).notify(eq(MerchantNotificationService.BIZ_SAAS_FEE_PAID), eq(11L),
                eq(MerchantNotificationService.CHANNEL_INAPP), eq("SAAS_FEE_BILL_PAID"), eq("MERCHANT:7"), anyMap());
    }

    @Test
    @DisplayName("重复缴费、免收账单与非线下方式均拒绝")
    void confirmRejectsInvalidState() {
        SaasFeeBill paid = bill("PAID", "99.00");
        when(bills.findByBillNo("PAID")).thenReturn(Optional.of(paid));
        when(bills.lockById(11L)).thenReturn(paid);
        assertThrows(BusinessException.class, () -> service.confirmPayment("PAID", 2L, 3L, "OFFLINE", "V-1", null));

        SaasFeeBill waived = bill("WAIVED", "0.00");
        when(bills.findByBillNo("WAIVED")).thenReturn(Optional.of(waived));
        when(bills.lockById(11L)).thenReturn(waived);
        assertThrows(BusinessException.class, () -> service.confirmPayment("WAIVED", 2L, 3L, "OFFLINE", "V-1", null));

        SaasFeeBill pending = bill("PENDING", "99.00");
        when(bills.findByBillNo("PENDING")).thenReturn(Optional.of(pending));
        when(bills.lockById(11L)).thenReturn(pending);
        assertThrows(BusinessException.class, () -> service.confirmPayment("PENDING", 2L, 3L, "CASH", "V-1", null));
        assertThrows(BusinessException.class, () -> service.confirmPayment("PENDING", 2L, 3L, "OFFLINE", "", null));
        verify(bills, never()).updateById(any());
    }

    @Test
    @DisplayName("逾期扫描：仅过期待缴账单置 OVERDUE 并发逾期站内信")
    void markOverdueUpdatesOnlyExpiredPending() {
        SaasFeeBill expired = bill("PENDING", "99.00");
        expired.setDueDate(LocalDate.of(2026, 11, 1));
        when(bills.findPendingDueBefore(LocalDate.of(2026, 11, 7), 200)).thenReturn(List.of(expired));
        when(bills.lockById(11L)).thenReturn(expired);

        int changed = service.markOverdue(LocalDate.of(2026, 11, 7));

        assertEquals(1, changed);
        assertEquals("OVERDUE", expired.getStatus());
        verify(bills).updateById(expired);
        verify(notifications).notify(eq(MerchantNotificationService.BIZ_SAAS_FEE_OVERDUE), eq(11L),
                eq(MerchantNotificationService.CHANNEL_INAPP), eq("SAAS_FEE_BILL_OVERDUE"), eq("MERCHANT:7"), anyMap());
    }

    @Test
    @DisplayName("超管列表：非法状态/月份拒绝，分页参数收敛")
    void adminQueryValidation() {
        assertThrows(BusinessException.class, () -> service.adminBills(null, "UNKNOWN", null, 50, 0));
        assertThrows(BusinessException.class, () -> service.adminBills(null, null, "2026/10", 50, 0));
        when(bills.search(null, "PENDING", "2026-10", 200, 0)).thenReturn(List.of());
        service.adminBills(null, "PENDING", "2026-10", 500, -1);
        verify(bills).search(null, "PENDING", "2026-10", 200, 0);
    }
}
