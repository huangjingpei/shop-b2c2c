/*
 * 文件：BillingStatementController.java
 * 用途：F09 商家端本店账单查询与 CSV 导出入口（只读）。
 * 维护注意：
 *  1. 路径位于 /api/v1/merchant/**，由 SaTokenConfigure 统一拦截登录与商家角色；账单属财务展示数据，仅店长可见。
 *  2. 当前商户一律取会话店铺，不接受客户端传 shopId；账单生成/重算只走管理端与定时任务，商家端不提供触发入口。
 *  3. CSV 转义与公式注入防护在 BillingStatementService 统一处理（CsvEscaper）。
 */
package com.shop.b2b2c.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.service.BillingStatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

@Tag(name = "商家账单 (Merchant Billing)")
@RestController
@RequestMapping("/api/v1/merchant/billing")
@RequiredArgsConstructor
public class BillingStatementController {

    private final BillingStatementService billingService;

    @Operation(summary = "查询本店账单（日/月）")
    @GetMapping("/statements")
    public R<Map<String, Object>> statements(@RequestParam(defaultValue = "DAILY") String periodType,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
        LocalDate[] range = defaultRange(periodType, from, to);
        return R.ok(billingService.list(SecurityUtils.requireCurrentMerchantId(), periodType,
                range[0], range[1], page, size));
    }

    @Operation(summary = "导出本店账单 CSV")
    @GetMapping("/statements/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "DAILY") String periodType,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
        Long shopId = SecurityUtils.requireCurrentMerchantId();
        LocalDate[] range = defaultRange(periodType, from, to);
        String csv = billingService.csv(shopId, periodType, range[0], range[1]);
        String filename = "billing-" + shopId + "-" + periodType.toLowerCase() + "-" + range[0] + "-" + range[1] + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return ResponseEntity.ok().headers(headers).body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** 缺省范围：日账单近 30 天，月账单近 6 个月（跨度上限由服务层校验）。 */
    private LocalDate[] defaultRange(String periodType, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from
                : "MONTHLY".equals(periodType) ? YearMonth.from(end).minusMonths(5).atDay(1) : end.minusDays(29);
        return new LocalDate[]{start, end};
    }
}
