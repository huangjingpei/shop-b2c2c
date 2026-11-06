/*
 * 文件：NotificationController.java
 * 用途：O8 通知查询接口：商家站内收件箱（本店 MERCHANT:{id} 已送达通知）与管理端通知列表（含审核SLA提醒）。
 * 维护注意：
 * 1. 路由守卫沿用 SaTokenConfigure：/api/v1/admin/** 仅 SUPERADMIN，/api/v1/merchant/** 超管与商家员工；
 *    商家收件箱只按会话店铺取数，禁止通过参数跨店查询。
 * 2. 只读接口，不暴露 PENDING/SKIPPED 的通道排队记录给商家（仅管理端可按状态过滤查看）；
 *    返回 payload_json 由前端按模板渲染，后端不回传任何证件/密码字段（写入侧已 fail-fast）。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.entity.MerchantNotificationOutbox;
import com.shop.b2b2c.mapper.MerchantNotificationOutboxMapper;
import com.shop.b2b2c.service.MerchantNotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class NotificationController {

    private static final int MAX_LIMIT = 100;

    private final MerchantNotificationOutboxMapper outboxMapper;

    public NotificationController(MerchantNotificationOutboxMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    /** 商家站内收件箱：本店（MERCHANT:{店铺ID}）已送达的 INAPP 通知，按时间倒序限量。 */
    @GetMapping("/api/v1/merchant/notifications")
    public R<List<MerchantNotificationOutbox>> merchantInbox(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return R.ok(outboxMapper.findInappByRecipient(
                MerchantNotificationService.merchantRecipient(merchantId), capped));
    }

    /** 管理端通知列表：按业务类型/状态过滤的最新记录（含 ROLE:SUPERADMIN 的审核SLA提醒与全量节点留痕）。 */
    @GetMapping("/api/v1/admin/notifications")
    public R<List<MerchantNotificationOutbox>> adminList(
            @RequestParam(value = "bizType", required = false) String bizType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return R.ok(outboxMapper.findRecentForAdmin(bizType, status, capped));
    }
}
