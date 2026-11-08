/*
 * 文件：MerchantSettlementAccountService.java
 * 用途：阶段一商家收款账户登记、人工核验、默认账户切换、停用与脱敏展示；O5 增加账户变更申请与打款验证通过/驳回联动。
 * 维护注意：
 * 1. 登记前必须存在已审核通过的入驻申请；户名与实名主体（subjectName）严格相等，不一致直接拒绝，不允许人工放行。
 * 2. 账号只存 CardCipherService 密文与 SHA-256 指纹，展示只用 accountNoMasked；接口不得返回密文或明文账号。
 * 3. 核验只由平台人工动作推进：PENDING -> VERIFIED/REJECTED；换默认账户时旧默认账户置 DISABLED 并记录 effectiveTo，历史不原地覆盖。
 * 4. 阶段一不生成 sub_mchid、不调用渠道进件。结算账户核验通过且成为默认账户时（问题 3 方案 A，Sprint 56），
 *    同步 upsert merchant_payment_account 台账：channel 取店铺 pay_channel，settlement_account_id 引用已核验账户，
 *    apply_status=AUDITING、binding_status=UNBOUND、sub_mchid 空串，全部按真实值，不伪造 PASSED/BOUND；
 *    bindingStatus/subMchid/channelMerchantId 等渠道字段仍只允许真实渠道结果推进，本服务绝不写入。
 * 5. 行锁 + 状态校验防重复核验；真实并发行锁由人工在 MySQL 验证。
 * 6. O2 默认账户唯一：uk_settlement_default 生成列唯一索引兜底；登记/核验命中唯一冲突翻译为 409，
 *    事务回滚，不继续提交、不向客户端泄漏 SQL。
 * 7. O5 账户变更：店长发起 requestChange 生成 replaces_account_id 指向当前默认账户的新 PENDING 行，旧账户在变更审核期间
 *    保持 VERIFIED + 默认，结算不中断；核验/打款通过后在同一事务内先清旧默认（保持 VERIFIED、写 effectiveTo）
 *    再置新账户默认，避免 uk_settlement_default 瞬时冲突；审计写 CHANGE_REQUESTED/CHANGE_VERIFIED/CHANGE_SWITCHED。
 * 8. O5 打款验证：confirmPayoutSuccess 仅由 MerchantPayoutVerificationService 在金额匹配后同事务调用；
 *    累计 3 次失败由 rejectForPayoutExceeded 置 REJECTED（reject_reason=PAYOUT_ATTEMPTS_EXCEEDED）。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.ManagementGuards;
import com.shop.b2b2c.common.PiiMasking;
import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import com.shop.b2b2c.entity.MerchantPaymentAccount;
import com.shop.b2b2c.entity.MerchantSettlementAccount;
import com.shop.b2b2c.entity.MerchantSettlementAccountAudit;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.MerchantPaymentAccountMapper;
import com.shop.b2b2c.mapper.MerchantSettlementAccountAuditMapper;
import com.shop.b2b2c.mapper.MerchantSettlementAccountMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
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

    /** O5 核验方式：人工核验（默认）。 */
    public static final String VERIFY_METHOD_MANUAL = "MANUAL";
    /** O5 核验方式：小额打款验证。 */
    public static final String VERIFY_METHOD_PAYOUT = "PAYOUT";
    /** O5 打款验证连续失败达上限的驳回原因码。 */
    public static final String REJECT_PAYOUT_ATTEMPTS_EXCEEDED = "PAYOUT_ATTEMPTS_EXCEEDED";

    private static final Set<String> ACCOUNT_TYPES = Set.of("BANK", "WECHAT", "ALIPAY");

    private final MerchantSettlementAccountMapper accountMapper;
    private final MerchantSettlementAccountAuditMapper auditMapper;
    private final MerchantOnboardingService onboardingService;
    private final CardCipherService cipherService;
    private final MerchantPaymentAccountMapper paymentAccountMapper;
    private final MerchantStoreMapper storeMapper;

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
        return doRegister(merchantId, form, operatorId, false, null, "REGISTER", "收款账户登记，待人工核验");
    }

    /**
     * O5 店长发起账户变更：生成 replaces_account_id 指向当前已核验默认账户的新 PENDING 行；
     * 变更审核期间旧账户保持 VERIFIED + 默认，结算继续打到旧账户，不得提前切换。
     */
    @Transactional(rollbackFor = Exception.class)
    public MerchantSettlementAccount requestChange(Long merchantId, RegisterForm form, String reason, Long operatorId) {
        MerchantSettlementAccount currentDefault = accountMapper.findDefaultVerified(merchantId);
        if (currentDefault == null) {
            throw new BusinessException(409, "当前无已核验默认账户，无法发起变更；请先完成首次账户登记与核验");
        }
        MerchantSettlementAccount account = doRegister(merchantId, form, operatorId, true, currentDefault.getId(),
                "CHANGE_REQUESTED", "商家发起收款账户变更，替换账户#" + currentDefault.getId()
                        + (reason == null || reason.isBlank() ? "" : ("；" + reason.trim())));
        return account;
    }

    private MerchantSettlementAccount doRegister(Long merchantId, RegisterForm form, Long operatorId,
                                                 boolean forceNonDefault, Long replacesAccountId,
                                                 String auditAction, String auditReason) {
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
        account.setVerifyMethod(VERIFY_METHOD_MANUAL);
        account.setReplacesAccountId(replacesAccountId);
        boolean firstAccount = accountMapper.countByMerchantId(merchantId) == 0;
        // 变更申请的新账户绝不在登记时抢占默认位；默认切换只发生在核验通过事务内。
        account.setIsDefault(!forceNonDefault && (form.isDefault() || firstAccount));
        account.setEffectiveFrom(LocalDateTime.now());
        account.setVersion(0);
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            // 命中 uk_settlement_account_fingerprint 或 uk_settlement_default：并发重复登记/双默认。
            throw new BusinessException(409, "收款账户登记冲突：账户已存在或默认账户已被占用，请勿重复提交");
        }

        writeAudit(account, auditAction, operatorId, auditReason);
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

        return approveVerified(account, operatorId, VERIFY_METHOD_MANUAL,
                reason == null ? "人工核对户名与账号一致" : reason);
    }

    /**
     * O5 打款验证金额匹配后的账户通过钩子：仅 MerchantPayoutVerificationService 在同事务内调用；
     * 与人工核验共用默认切换/变更切换逻辑，verify_method 落 PAYOUT。
     */
    @Transactional(rollbackFor = Exception.class)
    public MerchantSettlementAccount confirmPayoutSuccess(Long accountId, Long operatorId) {
        MerchantSettlementAccount account = requireLocked(accountId);
        if (!STATUS_PENDING.equals(account.getStatus())) {
            throw new BusinessException(409, "仅待核验账户可完成打款验证");
        }
        return approveVerified(account, operatorId, VERIFY_METHOD_PAYOUT, "小额打款金额匹配，账户归属验证通过");
    }

    /**
     * O5 打款验证连续失败达上限：账户置 REJECTED（原因码 PAYOUT_ATTEMPTS_EXCEEDED），
     * 需重新登记或走人工核验；仅 MerchantPayoutVerificationService 在同事务内调用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void rejectForPayoutExceeded(Long accountId, Long operatorId) {
        MerchantSettlementAccount account = requireLocked(accountId);
        if (!STATUS_PENDING.equals(account.getStatus())) {
            return;
        }
        account.setStatus(STATUS_REJECTED);
        account.setRejectReason(REJECT_PAYOUT_ATTEMPTS_EXCEEDED);
        account.setIsDefault(false);
        account.setVersion((account.getVersion() == null ? 0 : account.getVersion()) + 1);
        accountMapper.updateById(account);
        writeAudit(account, "REJECT", operatorId, "打款验证连续失败次数达上限，账户驳回（" + REJECT_PAYOUT_ATTEMPTS_EXCEEDED + "）");
    }

    /**
     * 核验通过公共段：置 VERIFIED + 记录核验方式；变更账户先清旧默认（旧账户保持 VERIFIED、写 effectiveTo）
     * 再置新默认，普通账户按既有规则切换默认；唯一索引冲突翻译为 409。
     */
    private MerchantSettlementAccount approveVerified(MerchantSettlementAccount account, Long operatorId,
                                                      String verifyMethod, String reason) {
        LocalDateTime now = LocalDateTime.now();
        account.setStatus(STATUS_VERIFIED);
        account.setVerifiedAt(now);
        account.setVerifiedBy(operatorId);
        account.setRejectReason(null);
        account.setVerifyMethod(verifyMethod);

        Long replacesId = account.getReplacesAccountId();
        if (replacesId != null) {
            // O5 变更切换：先释放旧账户默认位（uk_settlement_default 为即时唯一校验，顺序不能反）。
            MerchantSettlementAccount old = accountMapper.lockById(replacesId);
            if (old != null && Boolean.TRUE.equals(old.getIsDefault())) {
                old.setIsDefault(false);
                old.setEffectiveTo(now);
                old.setVersion((old.getVersion() == null ? 0 : old.getVersion()) + 1);
                accountMapper.updateById(old);
            }
            account.setIsDefault(true);
            account.setVersion((account.getVersion() == null ? 0 : account.getVersion()) + 1);
            try {
                accountMapper.updateById(account);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(409, "默认收款账户已被其他账户占用，请刷新后重试");
            }
            writeAudit(account, "CHANGE_VERIFIED", operatorId, reason);
            if (old != null) {
                writeAudit(old, "CHANGE_SWITCHED", operatorId,
                        "新账户#" + account.getId() + " 核验通过，默认收款账户切换，旧账户失效");
            }
            syncPaymentAccountBinding(account);
            return account;
        }

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
        writeAudit(account, "VERIFY", operatorId, reason);
        if (Boolean.TRUE.equals(account.getIsDefault())) {
            // 仅默认账户同步渠道台账；非默认核验通过不影响 merchant_payment_account 的结算引用。
            syncPaymentAccountBinding(account);
        }
        return account;
    }

    /**
     * 问题 3 方案 A（Sprint 56）：核验通过的默认结算账户同步到 merchant_payment_account 台账。
     * 只写真实值：apply_status=AUDITING（渠道进件未提交）、binding_status=UNBOUND（渠道侧未绑定）、
     * sub_mchid 空串（官方未分配），账号只同步脱敏值；已有台账行仅更新 settlement_account_id 引用，
     * 渠道字段（subMchid/bindingStatus/channelMerchantId 等）保持原值，等待真实渠道结果推进。
     */
    private void syncPaymentAccountBinding(MerchantSettlementAccount account) {
        MerchantStore store = storeMapper.findById(account.getMerchantId()).orElse(null);
        if (store == null) {
            return;
        }
        String channel = store.getPayChannel() == null || store.getPayChannel().isBlank()
                ? "WECHAT" : store.getPayChannel().trim().toUpperCase();
        MerchantPaymentAccount existing = paymentAccountMapper
                .findByMerchantIdAndChannel(account.getMerchantId(), channel).orElse(null);
        if (existing != null) {
            if (!account.getId().equals(existing.getSettlementAccountId())) {
                existing.setSettlementAccountId(account.getId());
                paymentAccountMapper.updateById(existing);
            }
            return;
        }
        MerchantPaymentAccount created = new MerchantPaymentAccount();
        created.setMerchantId(account.getMerchantId());
        created.setChannel(channel);
        // sub_mchid 为 NOT NULL 列：官方未分配前写空串，切换/下单校验按 isBlank 如实拒绝。
        created.setSubMchid("");
        created.setBankAccountName(account.getHolderName());
        created.setBankAccountNumber(account.getAccountNoMasked() == null ? "" : account.getAccountNoMasked());
        created.setBankName(account.getBankName() == null ? "" : account.getBankName());
        created.setApplyStatus("AUDITING");
        created.setBindingStatus("UNBOUND");
        created.setSettlementAccountId(account.getId());
        created.setCreatedAt(LocalDateTime.now());
        try {
            paymentAccountMapper.insert(created);
        } catch (DuplicateKeyException race) {
            // 并发核验同事务外另一请求先落台账：uk_merchant_channel 兜底，只补结算引用。
            MerchantPaymentAccount winner = paymentAccountMapper
                    .findByMerchantIdAndChannel(account.getMerchantId(), channel).orElse(null);
            if (winner != null && !account.getId().equals(winner.getSettlementAccountId())) {
                winner.setSettlementAccountId(account.getId());
                paymentAccountMapper.updateById(winner);
            }
        }
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
        view.put("verifyMethod", account.getVerifyMethod());
        view.put("replacesAccountId", account.getReplacesAccountId());
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
