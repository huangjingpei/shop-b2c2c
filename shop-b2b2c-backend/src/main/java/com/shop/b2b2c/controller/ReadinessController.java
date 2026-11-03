/*
 * 文件：ReadinessController.java
 * 用途：公开有界依赖就绪结果，与原存活接口分开。
 * 维护注意：HTTP及业务码同时为200/503，禁止缓存；只返回UP/DOWN摘要，不回显连接地址、SQL、Redis INFO原文或异常。业务鉴权不依赖探针结果。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.R;
import com.shop.b2b2c.service.ReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReadinessController {
    private final ReadinessService readiness;

    @GetMapping("/api/v1/auth/ready")
    public ResponseEntity<R<ReadinessService.Report>> ready() {
        var report = readiness.check();
        int code = report.ready() ? 200 : 503;
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore())
                .body(new R<>(code, report.ready() ? "依赖检查通过" : "依赖未就绪", report));
    }
}
