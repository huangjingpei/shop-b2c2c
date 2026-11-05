/*
 * 文件：MerchantController.java
 * 用途：平台商户配置及商家本店资料接口。
 * 维护注意：管理参数必须在持久化前校验，所有既有店铺更新只写本操作允许列，禁止定向SQL后再save旧实体；同列并发最后提交者生效；进件账户仍为本地模拟，不能将PASSED视为真实渠道开通。服务费状态按ServiceFeeStatus保留五种编码，配置标记不代表扣费或停服生效。Q13-A：员工资料读取走白名单，身份证/银行卡默认脱敏，资料修改仅店长。
 */
package com.shop.b2b2c.controller;

import cn.dev33.satoken.secure.BCrypt;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.PiiMasking;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.ManagementGuards;
import com.shop.b2b2c.common.ServiceFeeStatus;
import java.util.Set;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.entity.MerchantPaymentAccount;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.SysUser;
import com.shop.b2b2c.mapper.MerchantPaymentAccountMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.SysUserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "商户管理 (Merchant)")
@RestController
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantStoreMapper storeMapper;
    private final MerchantPaymentAccountMapper paymentAccountMapper;
    private final SysUserMapper userMapper;

    // ==========================================
    // 平台超级管理员视角 API (/api/v1/admin/merchants)
    // ==========================================

    @Operation(summary = "【超管】全平台入驻店铺列表")
    @GetMapping("/api/v1/admin/merchants")
    public R<List<MerchantStore>> listAllMerchants() {
        List<MerchantStore> stores = storeMapper.selectList(null);
        stores.forEach(store -> store.setIdCardNumber(PiiMasking.idCard(store.getIdCardNumber())));
        return R.ok(stores);
    }

    @Data
    public static class CreateMerchantDTO {
        private String storeCode;
        private String storeName;
        private Integer merchantType; // 1-个人, 2-个体, 3-企业
        private String ownerName;
        private String idCardNumber;
        private String contactPhone;
        private BigDecimal commissionRate;
        private String adminUsername;
        private String adminPassword;
        private String bankAccountName;
        private String bankAccountNumber;
        private String bankName;
    }

    @Operation(summary = "【超管】审批/新增入驻商户并开通二级分账账户")
    @PostMapping("/api/v1/admin/merchants")
    @Transactional(rollbackFor = Exception.class)
    public R<MerchantStore> createMerchant(@RequestBody CreateMerchantDTO dto) {
        ManagementGuards.text(dto.getStoreCode(), 64, true);
        ManagementGuards.text(dto.getStoreName(), 128, true);
        ManagementGuards.text(dto.getOwnerName(), 64, true);
        ManagementGuards.text(dto.getIdCardNumber(), 32, true);
        ManagementGuards.text(dto.getContactPhone(), 32, true);
        ManagementGuards.text(dto.getAdminUsername(), 64, true);
        ManagementGuards.text(dto.getAdminPassword(), 72, true);
        if (dto.getAdminPassword().length() < 8 || dto.getAdminPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(400, "密码须至少8字符且UTF-8编码不超过72字节");
        }
        if (dto.getMerchantType() != null && (dto.getMerchantType() < 1 || dto.getMerchantType() > 3)) {
            throw new BusinessException(400, "商户类型不合法");
        }
        if (dto.getCommissionRate() != null) ManagementGuards.percent(dto.getCommissionRate());
        ManagementGuards.text(dto.getBankAccountName(), 64, false);
        ManagementGuards.text(dto.getBankName(), 128, false);
        ManagementGuards.text(dto.getBankAccountNumber(), 64, dto.getBankAccountNumber() != null);
        if (storeMapper.findByStoreCode(dto.getStoreCode()).isPresent()) {
            throw new BusinessException("店铺编码已存在");
        }
        if (userMapper.findByUsername(dto.getAdminUsername()).isPresent()) {
            throw new BusinessException("登录账号已存在");
        }

        // 1. 创建店铺
        MerchantStore store = new MerchantStore();
        store.setStoreCode(dto.getStoreCode());
        store.setStoreName(dto.getStoreName());
        store.setMerchantType(dto.getMerchantType() != null ? dto.getMerchantType() : 1);
        store.setOwnerName(dto.getOwnerName());
        store.setIdCardNumber(dto.getIdCardNumber());
        store.setContactPhone(dto.getContactPhone());
        store.setStatus(1); // 正常营业
        store.setCommissionRate(dto.getCommissionRate() != null ? dto.getCommissionRate() : new BigDecimal("3.00"));
        store = storeMapper.save(store);

        // 2. 创建商户主管理员账号 (使用 Sa-Token BCrypt 加密)
        SysUser user = new SysUser();
        user.setMerchantId(store.getId());
        user.setUsername(dto.getAdminUsername());
        String rawPwd = dto.getAdminPassword();
        user.setPassword(BCrypt.hashpw(rawPwd));
        user.setRealName(dto.getOwnerName() + " (店长)");
        user.setRoleCode("MERCHANT_ADMIN");
        user.setIsActive(true);
        userMapper.save(user);

        // 3. 创建二级商户收付通进件记录
        if (dto.getBankAccountNumber() != null) {
            MerchantPaymentAccount account = new MerchantPaymentAccount();
            account.setMerchantId(store.getId());
            account.setChannel("WECHAT");
            account.setSubMchid("SUB" + System.currentTimeMillis());
            account.setBankAccountName(dto.getBankAccountName() != null ? dto.getBankAccountName() : dto.getOwnerName());
            account.setBankAccountNumber(dto.getBankAccountNumber());
            account.setBankName(dto.getBankName() != null ? dto.getBankName() : "中国工商银行");
            account.setApplyStatus("PASSED");
            paymentAccountMapper.save(account);
        }

        // 响应体默认脱敏，数据库仍保存进件原件；超管需要原文时走受控审批流程，不复用列表接口。
        store.setIdCardNumber(PiiMasking.idCard(store.getIdCardNumber()));
        return R.ok("商户开通成功", store);
    }

    @Operation(summary = "【超管】变更店铺营业状态 (打烊/封禁/营业)")
    @PutMapping("/api/v1/admin/merchants/{id}/status")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        ManagementGuards.status(status);
        requireStore(id);
        storeMapper.updateStatusOnly(id, status);
        return R.ok("状态更新成功", null);
    }

    @Operation(summary = "【超管】调整店铺平台抽成比率")
    @PutMapping("/api/v1/admin/merchants/{id}/commission")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> updateCommission(@PathVariable Long id, @RequestParam BigDecimal rate) {
        ManagementGuards.percent(rate);
        requireStore(id);
        storeMapper.updateCommissionOnly(id, rate);
        return R.ok("费率调整成功", null);
    }

    @Data
    public static class ServiceFeeConfigDTO {
        private BigDecimal monthlyFee;
        private java.time.LocalDate feeFreeUntil;
        private String feeStatus; // ServiceFeeStatus五种编码；null表示保留原值
        private Boolean clearFeeFreeUntil; // true表示显式清空免收截止日
    }

    @Operation(summary = "【超管】配置商户月度技术服务费与免收期")
    @PutMapping("/api/v1/admin/merchants/{id}/service-fee")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> updateServiceFee(@PathVariable Long id, @RequestBody ServiceFeeConfigDTO dto) {
        if (dto == null) throw new BusinessException(400, "服务费配置不能为空");
        if (dto.getMonthlyFee() != null) ManagementGuards.monthlyFee(dto.getMonthlyFee());
        if (dto.getFeeStatus() != null) ManagementGuards.choice(dto.getFeeStatus(), ServiceFeeStatus.SUPPORTED);
        requireStore(id);
        // null保留旧值；显式清除优先于同时提交的日期，兼容既有R23协议。
        storeMapper.updateServiceFeeOnly(id, dto.getMonthlyFee(),
                dto.getFeeFreeUntil() == null ? null : dto.getFeeFreeUntil().atStartOfDay(),
                dto.getFeeStatus(), Boolean.TRUE.equals(dto.getClearFeeFreeUntil()));
        return R.ok("商户技术服务费配置更新成功", null);
    }

    @Data
    public static class PaymentChannelConfigDTO {
        private String paymentChannelType; // A-平台收付通, B-商家独立直收, C-测试桩
        private String customPayQrcode;
    }

    @Operation(summary = "【超管】配置商户结算通道类型与独立收款码")
    @PutMapping("/api/v1/admin/merchants/{id}/payment-channel")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> updatePaymentChannel(@PathVariable Long id, @RequestBody PaymentChannelConfigDTO dto) {
        if (dto == null) throw new BusinessException(400, "收款配置不能为空");
        if (dto.getPaymentChannelType() != null) ManagementGuards.choice(dto.getPaymentChannelType(), Set.of("A", "B", "C"));
        ManagementGuards.text(dto.getCustomPayQrcode(), 512, false);
        requireStore(id);
        storeMapper.updatePaymentChannelOnly(id, dto.getPaymentChannelType(), dto.getCustomPayQrcode());
        return R.ok("商户结算收款通道配置成功", null);
    }

    // ==========================================
    // 商家工作台视角 API (/api/v1/merchant/profile)
    // ==========================================

    @Operation(summary = "【商家】查看本店资料与进件分账状态")
    @GetMapping("/api/v1/merchant/profile")
    public R<Map<String, Object>> getMerchantProfile() {
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        if (merchantId == null) {
            throw new BusinessException("当前非商家账号或未绑定店铺");
        }
        MerchantStore store = storeMapper.findById(merchantId)
                .orElseThrow(() -> new BusinessException("店铺不存在"));

        Map<String, Object> map = new HashMap<>();
        if (com.shop.b2b2c.service.AuthSessionService.isManagementRole()) {
            store.setIdCardNumber(PiiMasking.idCard(store.getIdCardNumber()));
            map.put("store", store);
            MerchantPaymentAccount paymentAccount = paymentAccountMapper.findByMerchantId(merchantId).orElse(null);
            if (paymentAccount != null) {
                paymentAccount.setBankAccountNumber(PiiMasking.bankAccount(paymentAccount.getBankAccountNumber()));
            }
            map.put("paymentAccount", paymentAccount);
        } else {
            // Q13-A：员工只能读取展示资料，不返回经营者姓名、身份证、费率与收款账户。
            map.put("store", AuthController.staffStoreView(store));
        }
        return R.ok(map);
    }

    @Operation(summary = "【商家】更新店铺公告与展示资料")
    @PutMapping("/api/v1/merchant/profile")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> updateMerchantProfile(@RequestBody MerchantStore updateDTO) {
        if (updateDTO == null) throw new BusinessException(400, "店铺资料不能为空");
        ManagementGuards.text(updateDTO.getNotice(), 512, false);
        ManagementGuards.text(updateDTO.getLogoUrl(), 255, false);
        ManagementGuards.text(updateDTO.getContactPhone(), 32, false);
        ManagementGuards.text(updateDTO.getCustomPayQrcode(), 512, false);
        com.shop.b2b2c.service.AuthSessionService.requireManagementRole();
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        requireStore(merchantId);
        storeMapper.updateProfileOnly(merchantId, updateDTO.getNotice(), updateDTO.getLogoUrl(),
                updateDTO.getContactPhone(), updateDTO.getCustomPayQrcode());

        return R.ok("店铺资料保存成功", null);
    }

    private void requireStore(Long id) {
        if (id == null || id <= 0) throw new BusinessException(400, "店铺编号不合法");
        storeMapper.findById(id).orElseThrow(() -> new BusinessException(404, "店铺不存在"));
        // 仅验证存在性，不将读到的实体用于回写。重复设置相同值允许成功，兼容MySQL changed-row计数为0。
        // 当前没有删店入口；未来引入删除须统一事务/锁或版本策略，不能将此读取当行锁。
    }

}
