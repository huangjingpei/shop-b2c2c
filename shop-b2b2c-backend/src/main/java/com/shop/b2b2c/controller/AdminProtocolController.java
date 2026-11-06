/*
 * 文件：AdminProtocolController.java
 * 用途：平台超管维护协议版本：发布、下线、查询当前生效列表。
 * 维护注意：
 * 1. 发布新版本时服务层在事务内先下线同类型旧版本，保证同类型仅一条 PUBLISHED 当前生效；
 *    重复 (protocolType, version) 返回 409，事务回滚不影响旧版本。
 * 2. 只迁移状态，不删除历史版本；既有同意记录按 (protocol_type, protocol_version) 与历史版本对账。
 * 3. contentHash 必须为正文的 SHA-256；签署接口按它校验"申请人看到的版本=发布的版本"，不一致拒绝签署。
 * 4. 本控制器显式限定 SUPERADMIN；公开协议列表走 /api/v1/onboarding/protocols。
 */
package com.shop.b2b2c.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.entity.MerchantAgreementVersion;
import com.shop.b2b2c.service.MerchantAgreementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "协议版本管理 (Admin Protocol Versions)")
@RestController
@RequestMapping("/api/v1/admin/protocol-versions")
@RequiredArgsConstructor
public class AdminProtocolController {

    private final MerchantAgreementService agreementService;

    @Data
    public static class PublishRequest {
        private String protocolType;
        private String version;
        private String title;
        private String contentHash;
        private String contentUrl;
        private LocalDateTime effectiveFrom;
    }

    @Operation(summary = "发布协议版本（同类型旧版本自动下线；重复版本号 409）")
    @PostMapping
    public R<MerchantAgreementVersion> publish(@RequestBody PublishRequest request) {
        StpUtil.checkRole("SUPERADMIN");
        MerchantAgreementService.PublishForm form = new MerchantAgreementService.PublishForm();
        if (request != null) {
            form.setProtocolType(request.getProtocolType());
            form.setVersion(request.getVersion());
            form.setTitle(request.getTitle());
            form.setContentHash(request.getContentHash());
            form.setContentUrl(request.getContentUrl());
            form.setEffectiveFrom(request.getEffectiveFrom());
        }
        return R.ok("协议版本已发布", agreementService.publish(form, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "下线协议版本（保留历史，不删除）")
    @PostMapping("/{id}/offline")
    public R<MerchantAgreementVersion> offline(@PathVariable Long id) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok(agreementService.offline(id));
    }

    @Operation(summary = "当前生效协议列表")
    @GetMapping
    public R<List<MerchantAgreementVersion>> list(@RequestParam(required = false) String type) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok(agreementService.listPublished(type));
    }
}
