/*
 * 文件：MerchantController.java
 * 用途：平台商户配置及商家本店资料接口。
 * 维护注意：管理参数必须在持久化前校验，所有既有店铺更新只写本操作允许列，禁止定向SQL后再save旧实体；同列并发最后提交者生效。T39 起超管开通也必须走入驻申请与审核留痕，阶段一收款账户写入 merchant_settlement_account，不再生成 SUB+时间戳/PASSED 假进件值。服务费状态按ServiceFeeStatus保留五种编码，配置标记不代表扣费或停服生效。Q13-A：员工资料读取走白名单，身份证/银行卡默认脱敏，资料修改仅店长。O4：应急开通建店后店铺为 status=0 待开通（协议签齐自动开通或超管 open 兜底）；商家资料修改等写操作先经 MerchantStoreGuard.requireOpen 闸门。O6：冻结/解封/关店/注销走 POST /store-status（MerchantStoreStatusService 状态机，CAS+审计）；旧 PUT /status 仅保留打烊/恢复开关，不再支持直接置 3。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.PiiMasking;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.ManagementGuards;
import com.shop.b2b2c.common.ServiceFeeStatus;
import java.util.Set;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.entity.MerchantFeeAudit;
import com.shop.b2b2c.entity.MerchantFeePolicy;
import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import com.shop.b2b2c.entity.MerchantPaymentAccount;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.MerchantPaymentAccountMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.SysUserMapper;
import com.shop.b2b2c.service.MerchantFeePolicyService;
import com.shop.b2b2c.service.MerchantOnboardingService;
import com.shop.b2b2c.service.MerchantSettlementAccountService;
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
    private final MerchantOnboardingService onboardingService;
    private final MerchantSettlementAccountService settlementAccountService;
    private final MerchantFeePolicyService feePolicyService;
    /** O4 商家写接口营业状态闸门；统一收口在 MerchantStoreGuard，不在各接口各写一套。 */
    private final com.shop.b2b2c.service.MerchantStoreGuard storeGuard;
    /** O6 店铺状态机：冻结/解封/关店/注销统一入口（CAS + 审计同事务）。 */
    private final com.shop.b2b2c.service.MerchantStoreStatusService storeStatusService;
    private final com.shop.b2b2c.mapper.MerchantStoreStatusAuditMapper storeStatusAuditMapper;

    @Data
    public static class StoreStatusActionDTO {
        /** PAUSE/RESUME/FREEZE/UNFREEZE/CLOSE/CANCEL。 */
        private String action;
        /** 冻结/解封/关店/注销必填（附录 A.5 字典）；打烊/恢复可空。 */
        private String reasonCode;
        private String reason;
        /** 解封必填（双人复核）；关店/注销传入时强制换人复核。 */
        private Long confirmedBy;
    }

    @Operation(summary = "【超管】店铺状态操作：打烊/恢复/冻结/解封(双人)/关店/注销（全审计）")
    @PostMapping("/api/v1/admin/merchants/{id}/store-status")
    public R<java.util.Map<String, Object>> applyStoreStatus(@PathVariable Long id,
                                                             @RequestBody StoreStatusActionDTO dto) {
        if (dto == null) {
            throw new BusinessException(400, "店铺状态操作不能为空");
        }
        return R.ok(storeStatusService.applyAction(id, dto.getAction(), dto.getReasonCode(),
                dto.getReason(), SecurityUtils.getCurrentUserId(), dto.getConfirmedBy()));
    }

    @Operation(summary = "【超管】店铺状态流转审计时间线")
    @GetMapping("/api/v1/admin/merchants/{id}/store-status-audits")
    public R<List<com.shop.b2b2c.entity.MerchantStoreStatusAudit>> storeStatusAudits(@PathVariable Long id) {
        if (storeMapper.selectById(id) == null) {
            throw new BusinessException(404, "店铺不存在");
        }
        return R.ok(storeStatusAuditMapper.findByMerchantId(id));
    }

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
        private String licenseNo;
        private String legalPerson;
        private String promotionReason;
        private String bankAccountName;
        private String bankAccountNumber;
        private String bankName;
        /** 初始费率复核人；生产双人复核开启时必须为除当前超管外的启用管理员。 */
        private Long rateConfirmedBy;
    }

    @Operation(summary = "【超管】应急开通商户（仍走入驻申请与审核留痕）")
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
        if (dto.getCommissionRate() != null) {
            ManagementGuards.percent(dto.getCommissionRate());
            if (dto.getCommissionRate().compareTo(new BigDecimal("10")) > 0) {
                throw new BusinessException(400, "佣金费率不得超过 10%");
            }
        }
        ManagementGuards.text(dto.getLicenseNo(), 64, false);
        ManagementGuards.text(dto.getLegalPerson(), 64, false);
        ManagementGuards.text(dto.getBankAccountName(), 64, false);
        ManagementGuards.text(dto.getBankName(), 128, false);
        ManagementGuards.text(dto.getBankAccountNumber(), 64, dto.getBankAccountNumber() != null);
        if (storeMapper.findByStoreCode(dto.getStoreCode()).isPresent()) {
            throw new BusinessException("店铺编码已存在");
        }
        if (userMapper.findByUsername(dto.getAdminUsername()).isPresent()) {
            throw new BusinessException("登录账号已存在");
        }

        Long operatorId = SecurityUtils.getCurrentUserId();
        MerchantOnboardingService.ApplicationForm applicationForm = new MerchantOnboardingService.ApplicationForm();
        boolean enterprise = dto.getMerchantType() != null && dto.getMerchantType() == 3;
        applicationForm.setSubjectType(enterprise
                ? MerchantOnboardingService.SUBJECT_ENTERPRISE : MerchantOnboardingService.SUBJECT_INDIVIDUAL);
        applicationForm.setSubjectName(dto.getOwnerName());
        // 入驻提交校验要求店铺名称；应急开通沿用表单里的店铺名称。
        applicationForm.setStoreName(dto.getStoreName());
        applicationForm.setIdCardNumber(dto.getIdCardNumber());
        applicationForm.setLicenseNo(dto.getLicenseNo());
        applicationForm.setLegalPerson(dto.getLegalPerson());
        applicationForm.setContactPhone(dto.getContactPhone());

        // 应急开通也必须先形成申请、审核审计与通过记录，不再直接“建店即营业”。
        MerchantOnboardingApplication application = onboardingService.create(applicationForm, operatorId);
        onboardingService.submit(application.getId(), operatorId);

        MerchantOnboardingService.Provisioning provisioning = new MerchantOnboardingService.Provisioning();
        provisioning.setStoreCode(dto.getStoreCode());
        provisioning.setStoreName(dto.getStoreName());
        provisioning.setMerchantType(dto.getMerchantType());
        provisioning.setAdminUsername(dto.getAdminUsername());
        provisioning.setAdminPassword(dto.getAdminPassword());
        provisioning.setRateBps(dto.getCommissionRate() == null ? 300
                : dto.getCommissionRate().multiply(new BigDecimal("100")).intValue());
        provisioning.setPromotionReason(dto.getPromotionReason());
        provisioning.setRateConfirmedBy(dto.getRateConfirmedBy());
        onboardingService.approve(application.getId(), provisioning, "超管应急开通，保留完整审核留痕", operatorId);

        MerchantStore store = storeMapper.findById(application.getMerchantId())
                .orElseThrow(() -> new BusinessException(500, "店铺创建后未找到"));

        // 阶段一只登记商家自有收款账户，不生成 sub_mchid、不写 PASSED 假进件值。
        if (dto.getBankAccountNumber() != null) {
            MerchantSettlementAccountService.RegisterForm accountForm = new MerchantSettlementAccountService.RegisterForm();
            accountForm.setAccountType("BANK");
            accountForm.setHolderName(dto.getBankAccountName() != null ? dto.getBankAccountName() : dto.getOwnerName());
            accountForm.setBankName(dto.getBankName());
            accountForm.setAccountNo(dto.getBankAccountNumber());
            accountForm.setDefault(true);
            settlementAccountService.register(store.getId(), accountForm, operatorId);
        }

        // 响应体默认脱敏；店铺主表只保存脱敏值，证件原文仅以密文留在入驻申请中。
        store.setIdCardNumber(PiiMasking.idCard(store.getIdCardNumber()));
        return R.ok("商户开通成功", store);
    }

    /**
     * 旧版直接改状态接口：O6 起仅保留打烊(2)/恢复营业(1)开关并改走状态机写审计；
     * 冻结(3)/解封必须改用 POST /store-status 携带原因码（解封需双人复核），
     * 待开通(0)无对应动作。已注销(4)终态只能经关店/注销流程进入，本接口不接受。
     */
    @Operation(summary = "【超管】变更店铺营业状态（仅打烊/恢复；冻结解封请用 /store-status）")
    @PutMapping("/api/v1/admin/merchants/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        ManagementGuards.status(status);
        if (id == null || id <= 0) throw new BusinessException(400, "店铺编号不合法");
        MerchantStore store = storeMapper.findById(id)
                .orElseThrow(() -> new BusinessException(404, "店铺不存在"));
        Integer current = store.getStatus();
        String action;
        if (Integer.valueOf(2).equals(status) && Integer.valueOf(1).equals(current)) {
            action = com.shop.b2b2c.service.MerchantStoreStatusService.ACTION_PAUSE;
        } else if (Integer.valueOf(1).equals(status) && Integer.valueOf(2).equals(current)) {
            action = com.shop.b2b2c.service.MerchantStoreStatusService.ACTION_RESUME;
        } else {
            throw new BusinessException(400,
                    "该接口仅支持打烊/恢复切换；冻结/解封/注销请使用 /store-status 并携带原因码");
        }
        storeStatusService.applyAction(id, action, null, null, SecurityUtils.getCurrentUserId(), null);
        return R.ok("状态更新成功", null);
    }

    @Operation(summary = "【超管】调整店铺技术服务费率（兼容旧佣金接口；降费即时/调高次日生效）")
    @PutMapping("/api/v1/admin/merchants/{id}/commission")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> updateCommission(@PathVariable Long id, @RequestParam BigDecimal rate,
                                    @RequestParam(required = false) String reason,
                                    @RequestParam(required = false) Long confirmedBy) {
        ManagementGuards.percent(rate);
        int bps = requirePolicyBps(rate);
        requireStore(id);
        MerchantFeePolicyService.AdjustForm form = new MerchantFeePolicyService.AdjustForm();
        form.setRateType(bps == 0 ? MerchantFeePolicyService.RATE_PROMOTION : MerchantFeePolicyService.RATE_NORMAL);
        form.setRateBps(bps);
        form.setReason(reason == null || reason.isBlank() ? "超管通过佣金接口调整费率" : reason);
        form.setPromotionReason(bps == 0
                ? (reason == null || reason.isBlank() ? "超管通过佣金接口设为推广费率" : reason) : null);
        form.setConfirmedBy(confirmedBy);
        feePolicyService.adjust(id, form, SecurityUtils.getCurrentUserId());
        return R.ok("费率调整成功，降费即时生效、调高次日00:00生效", null);
    }

    @Data
    public static class FeePolicyDTO {
        private String rateType; // NORMAL/PROMOTION
        private Integer rateBps;
        private String promotionReason;
        private String reason;
        private Long confirmedBy;
    }

    @Operation(summary = "【超管】当前生效费率政策")
    @GetMapping("/api/v1/admin/merchants/{id}/fee-policy")
    public R<MerchantFeePolicy> currentFeePolicy(@PathVariable Long id) {
        requireStore(id);
        return R.ok(feePolicyService.getEffectivePolicy(id));
    }

    @Operation(summary = "【超管】费率政策历史（含次日待生效）")
    @GetMapping("/api/v1/admin/merchants/{id}/fee-policies")
    public R<List<MerchantFeePolicy>> feePolicyHistory(@PathVariable Long id) {
        requireStore(id);
        return R.ok(feePolicyService.list(id));
    }

    @Operation(summary = "【超管】费率调整审计")
    @GetMapping("/api/v1/admin/merchants/{id}/fee-audits")
    public R<List<MerchantFeeAudit>> feeAudits(@PathVariable Long id) {
        requireStore(id);
        return R.ok(feePolicyService.audits(id));
    }

    @Operation(summary = "【超管】调整店铺费率政策（记录原因/操作人/前后值）")
    @PutMapping("/api/v1/admin/merchants/{id}/fee-policy")
    @Transactional(rollbackFor = Exception.class)
    public R<MerchantFeePolicy> adjustFeePolicy(@PathVariable Long id, @RequestBody FeePolicyDTO dto) {
        if (dto == null) throw new BusinessException(400, "费率政策参数不能为空");
        requireStore(id);
        MerchantFeePolicyService.AdjustForm form = new MerchantFeePolicyService.AdjustForm();
        form.setRateType(dto.getRateType());
        form.setRateBps(dto.getRateBps());
        form.setPromotionReason(dto.getPromotionReason());
        form.setReason(dto.getReason());
        form.setConfirmedBy(dto.getConfirmedBy());
        return R.ok(feePolicyService.adjust(id, form, SecurityUtils.getCurrentUserId()));
    }

    private int requirePolicyBps(BigDecimal rate) {
        int bps = rate.multiply(new BigDecimal("100")).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        if (bps != 0 && (bps < 100 || bps > 1000)) {
            throw new BusinessException(400, "费率必须为 0%（推广期）或 1%~10%");
        }
        return bps;
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
            // T39 阶段一收款账户：只返回脱敏值，店长可见，员工不返回。
            map.put("settlementAccounts", settlementAccountService.maskedList(merchantId));
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
        MerchantStore store = storeMapper.findById(merchantId)
                .orElseThrow(() -> new BusinessException(404, "店铺不存在"));
        storeGuard.requireOpen(store);
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
