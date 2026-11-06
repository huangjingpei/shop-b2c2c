/*
 * 文件：MerchantSettlementAccountService.java
 * 用途：阶段一商家收款账户登记、人工核验、默认账户切换、停用与脱敏展示。
 * 维护注意：
 * 1. 登记前必须存在已审核通过的入驻申请；户名与实名主体（subjectName）严格相等，不一致直接拒绝，不允许人工放行。
 * 2. 账号只存 CardCipherService 密文与 SHA-256 指纹，展示只用 accountNoMasked；接口不得返回密文或明文账号。
 * 3. 核验只由平台人工动作推进：PENDING -> VERIFIED/REJECTED；换默认账户时旧默认账户置 DISABLED 并记录 effectiveTo，历史不原地覆盖。
 * 4. 阶段一不生成 sub_mchid、不调用渠道进件；merchant_payment_account 的绑定字段留待 T40 与渠道真实结果。
 * 5. 行锁 + 状态校验防重复核验；真实并发行锁由人工在 MySQL 验证。
 * 6. O2 默认账户唯一：uk_settlement_default 生成列唯一索引兜底；登记/核验命中唯一冲突翻译为 409，
 *    事务回滚，不继续提交、不向客户端泄漏 SQL。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.ManagementGuards;
import com.shop.b2b2c.common.PiiMasking;
import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import com.shop.b2b2c.entity.MerchantSettlementAccount;
import com.shop.b2b2c.entity.MerchantSettlementAccountAudit;
import com.shop.b2b2c.mapper.MerchantSettlementAccountAuditMapper;
import com.shop.b2b2c.mapper.MerchantSettlementAccountMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MerchantSettlementAccountService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_VERIFIED = "VERIFIED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_DISABLED = "DISABLED";

    private static final Set<String> ACCOUNT_TYPES = Set.of("BANK", "WECHAT", "ALIPAY");

    private final MerchantSettlementAccountMapper accountMapper;
    private final MerchantSettlementAccountAuditMapper auditMapper;
    private final MerchantOnboardingService onboardingService;
    private final CardCipherService cipherService;

    @Data
    public static class RegisterForm {
        private String accountType;
        private String holderName;
        private String bankName;
        private String bankBranch;
        private String accountNo;
        private boolean isDefault;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantSettlementAccount register(Long merchantId, RegisterForm form, Long operatorId) {
        MerchantOnboardingApplication application = onboardingService.findApprovedByMerchantId(merchantId);
        if (application == null) {
            throw new BusinessException(409, "店铺未完成实名审核，禁止登记收款账户");
        }
        if (form == null) {
            throw new BusinessException(400, "收款账户信息不能为空");
        }
        ManagementGuards.text(form.getHolderName(), 128, true);
        ManagementGuards.text(form.getAccountNo(), 128, true);
        ManagementGuards.text(form.getBankName(), 128, false);
        ManagementGuards.text(form.getBankBranch(), 128, false);
        ManagementGuards.choice(form.getAccountType(), ACCOUNT_TYPES);

        String holderName = form.getHolderName().trim();
        String subjectName = application.getSubjectName() == null ? "" : application.getSubjectName().trim();
        if (!holderName.equals(subjectName)) {
            throw new BusinessException(400, "收款户名必须与实名主体一致，不允许人工放行");
        }
        if ("BANK".equals(form.getAccountType()) && (form.getBankName() == null || form.getBankName().isBlank())) {
            throw new BusinessException(400, "银行账户必须填写开户银行");
        }

        String accountNo = form.getAccountNo().trim();
        String fingerprint = cipherService.fingerprint(accountNo);
        if (accountMapper.findByMerchantIdAndFingerprint(merchantId, fingerprint) != null) {
            throw new BusinessException(409, "该收款账户已登记，请勿重复提交");
        }

        MerchantSettlementAccount account = new MerchantSettlementAccount();
        account.setMerchantId(merchantId);
        account.setAccountType(form.getAccountType());
        account.setHolderName(holderName);
        account.setBankName(blankToNull(form.getBankName()));
        account.setBankBranch(blankToNull(form.getBankBranch()));
        account.setAccountNoCipher(cipherService.encrypt(accountNo));
        account.setAccountFingerprint(fingerprint);
        account.setAccountNoMasked(PiiMasking.bankAccount(accountNo));
        account.setStatus(STATUS_PENDING);
        boolean firstAccount = accountMapper.countByMerchantId(merchantId) == 0;
        account.setIsDefault(form.isDefault() || firstAccount);
        account.setEffectiveFrom(LocalDateTime.now());
        account.setVersion(0);
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            // 命中 uk_settlement_account_fingerprint 或 uk_settlement_default：并发重复登记/双默认。
            throw new BusinessException(409, "收款账户登记冲突：账户已存在或默认账户已被占用，请勿重复提交");
        }

        writeAudit(account, "REGISTER", operatorId, "收款账户登记，待人工核验");
        return account;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantSettlementAccount verify(Long accountId, boolean approved, String reason, Long operatorId) {
        MerchantSettlementAccount account = requireLocked(accountId);
        if (!STATUS_PENDING.equals(account.getStatus())) {
            throw new BusinessException(409, "仅待核验账户可审核");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!approved) {
            if (reason == null || reason.isBlank()) {
                throw new BusinessException(400, "驳回必须填写原因");
            }
            account.setStatus(STATUS_REJECTED);
            account.setRejectReason(reason.trim());
            account.setIsDefault(false);
            account.setVersion((account.getVersion() == null ? 0 : account.getVersion()) + 1);
            accountMapper.updateById(account);
            writeAudit(account, "REJECT", operatorId, reason);
            return account;
        }

        account.setStatus(STATUS_VERIFIED);
        account.setVerifiedAt(now);
        account.setVerifiedBy(operatorId);
        account.setRejectReason(null);
        boolean makeDefault = Boolean.TRUE.equals(account.getIsDefault())
                || accountMapper.findDefaultVerified(account.getMerchantId()) == null;
        if (makeDefault) {
            accountMapper.disableOtherDefaults(account.getMerchantId(), account.getId(), now);
            account.setIsDefault(true);
        } else {
            account.setIsDefault(false);
        }
        account.setVersion((account.getVersion() == null ? 0 : account.getVersion()) + 1);
        try {
            accountMapper.updateById(account);
        } catch (DuplicateKeyException e) {
            // 并发核验同时设默认账户：uk_settlement_default 兜底，事务回滚。
            throw new BusinessException(409, "默认收款账户已被其他账户占用，请刷新后重试");
        }
        writeAudit(account, "VERIFY", operatorId, reason == null ? "人工核对户名与账号一致" : reason);
        return account;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantSettlementAccount disable(Long accountId, String reason, Long operatorId) {
        MerchantSettlementAccount account = requireLocked(accountId);
        if (!STATUS_PENDING.equals(account.getStatus()) && !STATUS_VERIFIED.equals(account.getStatus())) {
            throw new BusinessException(409, "当前状态不可停用");
        }
        account.setStatus(STATUS_DISABLED);
        account.setIsDefault(false);
        account.setEffectiveTo(LocalDateTime.now());
        account.setVersion((account.getVersion() == null ? 0 : account.getVersion()) + 1);
        accountMapper.updateById(account);
        writeAudit(account, "DISABLE", operatorId, reason);
        return account;
    }

    public List<MerchantSettlementAccount> list(Long merchantId) {
        return accountMapper.findByMerchantId(merchantId);
    }

    public List<Map<String, Object>> maskedList(Long merchantId) {
        return list(merchantId).stream().map(this::maskedView).collect(Collectors.toList());
    }

    public Map<String, Object> maskedView(MerchantSettlementAccount account) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", account.getId());
        view.put("merchantId", account.getMerchantId());
        view.put("accountType", account.getAccountType());
        view.put("holderName", account.getHolderName());
        view.put("bankName", account.getBankName());
        view.put("bankBranch", account.getBankBranch());
        view.put("accountNoMasked", account.getAccountNoMasked());
        view.put("status", account.getStatus());
        view.put("rejectReason", account.getRejectReason());
        view.put("isDefault", account.getIsDefault());
        view.put("effectiveFrom", account.getEffectiveFrom());
        view.put("effectiveTo", account.getEffectiveTo());
        view.put("verifiedAt", account.getVerifiedAt());
        view.put("verifiedBy", account.getVerifiedBy());
        view.put("version", account.getVersion());
        return view;
    }

    private MerchantSettlementAccount requireLocked(Long accountId) {
        MerchantSettlementAccount account = accountMapper.lockById(accountId);
        if (account == null) {
            throw new BusinessException(404, "收款账户不存在");
        }
        return account;
    }

    private void writeAudit(MerchantSettlementAccount account, String action, Long operatorId, String reason) {
        MerchantSettlementAccountAudit audit = new MerchantSettlementAccountAudit();
        audit.setAccountId(account.getId());
        audit.setMerchantId(account.getMerchantId());
        audit.setAction(action);
        audit.setAccountType(account.getAccountType());
        audit.setAccountFingerprint(account.getAccountFingerprint());
        audit.setOperatorId(operatorId);
        audit.setReason(reason);
        auditMapper.insert(audit);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
