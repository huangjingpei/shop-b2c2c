/*
 * 文件：AdminBillingStatementController.java
 * 用途：F09 平台超管的商户账单治理入口：全平台账单查询、CSV 导出与按店按期重算。
 * 维护注意：
 *  1. 路径位于 /api/v1/admin/**，由 SaTokenConfigure 统一要求 SUPERADMIN，控制器内仍显式校验一次。
 *  2. 重算只触发聚合覆盖，不涉及 SettlementService 结算批次的确认/锁定语义；批量历史重算走任务或逐期调用。
 *  3. shopId 过滤为可选；重算 MONTHLY 的 periodDate 在服务层归一到月初。
 */
package com.shop.b2b2c.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.dto.BillingStatementDTOs;
import com.shop.b2b2c.entity.BillingStatement;
import com.shop.b2b2c.service.BillingStatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

@Tag(name = "平台商户账单 (Admin Billing)")
@RestController
@RequestMapping("/api/v1/admin/billing")
@RequiredArgsConstructor
public class AdminBillingStatementController {

    private final BillingStatementService billingService;

    @Operation(summary = "全平台账单分页查询（可按店铺过滤）")
    @GetMapping("/statements")
    public R<Map<String, Object>> statements(@RequestParam(required = false) Long shopId,
                                             @RequestParam(defaultValue = "DAILY") String periodType,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        StpUtil.checkRole("SUPERADMIN");
        LocalDate[] range = defaultRange(periodType, from, to);
        return R.ok(billingService.list(shopId, periodType, range[0], range[1], page, size));
    }

    @Operation(summary = "重算单店单期账单")
    @PostMapping("/statements/regenerate")
    public R<BillingStatement> regenerate(@RequestBody BillingStatementDTOs.RegenerateRequest request) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok("账单已重算", billingService.generate(request == null ? null : request.getShopId(),
                request == null ? null : request.getPeriodDate(),
                request == null ? null : request.getPeriodType(), StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "导出账单 CSV")
    @GetMapping("/statements/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Long shopId,
                                         @RequestParam(defaultValue = "DAILY") String periodType,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        StpUtil.checkRole("SUPERADMIN");
        LocalDate[] range = defaultRange(periodType, from, to);
        String csv = billingService.csv(shopId, periodType, range[0], range[1]);
        String filename = "billing-" + (shopId == null ? "all" : shopId) + "-" + periodType.toLowerCase()
                + "-" + range[0] + "-" + range[1] + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return ResponseEntity.ok().headers(headers).body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private LocalDate[] defaultRange(String periodType, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from
                : "MONTHLY".equals(periodType) ? java.time.YearMonth.from(end).minusMonths(5).atDay(1)
                : end.minusDays(29);
        return new LocalDate[]{start, end};
    }
}
