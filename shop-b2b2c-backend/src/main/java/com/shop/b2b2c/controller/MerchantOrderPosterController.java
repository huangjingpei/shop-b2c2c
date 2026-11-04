/*
 * 文件：MerchantOrderPosterController.java
 * 用途：商家按需查看本店订单已保存的海报，替代列表批量返回Base64。
 * 维护注意：租户只取服务端会话，查询SQL同时绑定订单与商户；缺失、空内容及他店统一404业务码。
 * 禁止缓存，只读单字段，不生成海报、不加行锁、不回写订单，也不公开领取凭证；后续分页/海报存储调整须保持该租户边界。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MerchantOrderPosterController {
    private final TradeOrderMapper orders;

    @GetMapping("/api/v1/merchant/orders/{id}/poster")
    public R<String> poster(@PathVariable Long id, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        if (id == null || id <= 0) throw new BusinessException(400, "订单编号不正确");
        String poster = orders.findStoredPoster(id, merchantId);
        if (poster == null || poster.isBlank()) throw new BusinessException(404, "未找到已保存海报");
        return R.ok(poster);
    }
}
