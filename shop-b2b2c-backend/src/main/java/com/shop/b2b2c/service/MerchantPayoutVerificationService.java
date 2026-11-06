/*
 * 文件：MerchantPayoutVerificationService.java
 * 用途：O5 收款账户小额打款验证（可选增强）：平台发起随机小额打款，商家/超管回填金额确认账户归属。
 * 维护注意：
 * 1. 金额只能由服务端生成（0.01-0.99，SecureRandom），客户端传金额只用于"确认比对"，不得用于生成；
 *    amount 字段仅平台侧接口返回，商家端视图一律脱敏（adminView/merchantView 分流）。
 * 2. 同一账户最多 3 次尝试：第 3 次金额不匹配把账户置 REJECTED（PAYOUT_ATTEMPTS_EXCEEDED）；
 *    attempt_no 由历史记录数递增，uk_payout_account_attempt 兜底并发，冲突翻译 409。
 * 3. 确认必须行锁 + PENDING + 未过期；已过期直接 409（状态由过期任务或下次发起推进为 EXPIRED），
 *    不在抛错同事务内改写状态，避免 BusinessException 回滚把 EXPIRED 一起回滚。
 * 4. 超管确认必须与发起人不同人（403）；商家店长确认必须属于本店铺（404 防枚举）。
 * 5. 金额匹配成功在同一事务内推进验证单 SUCCESS + 账户 VERIFIED/默认切换（含变更切换），失败即整体回滚。
 * 6. 阶段一无真实出款通道：线下转账凭证号记录在 transferVoucherNo，不得虚构通道结果。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.ManagementGuards;
import com.shop.b2b2c.entity.MerchantPayoutVerification;
import com.shop.b2b2c.entity.MerchantSettlementAccount;
import com.shop.b2b2c.mapper.MerchantPayoutVerificationMapper;
import com.shop.b2b2c.mapper.MerchantSettlementAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MerchantPayoutVerificationService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    public static final String FAILURE_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    public static final String FAILURE_EXPIRED = "EXPIRED";

    /** 每账户最大尝试次数（与 chk_payout_attempt 一致）。 */
    public static final int MAX_ATTEMPTS = 3;

    private final MerchantPayoutVerificationMapper verificationMapper;
    private final MerchantSettlementAccountMapper accountMapper;
    private final MerchantSettlementAccountService accountService;

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${shop.payout-verify.expire-hours:24}")
    private long expireHours = 24L;

    /**
     * 超管发起打款验证：账户必须属于该店铺且 PENDING；已有进行中验证 409；次数用完 409。
     * 返回视图含金额，仅供平台线下转账使用，禁止透传商家端。
     */
    @Transactional(rollbackFor = Exception.class)
    public MerchantPayoutVerification initiate(Long merchantId, Long accountId, String transferVoucherNo, Long operatorId) {
        MerchantSettlementAccount account = accountMapper.lockById(accountId);
        if (account == null || !merchantId.equals(account.getMerchantId())) {
            throw new BusinessException(404, "收款账户不存在");
        }
        if (!MerchantSettlementAccountService.STATUS_PENDING.equals(account.getStatus())) {
            throw new BusinessException(409, "仅待核验账户可发起打款验证");
        }
        ManagementGuards.text(transferVoucherNo, 128, false);
        MerchantPayoutVerification pending = verificationMapper.findPendingByAccountId(accountId);
        if (pending != null) {
            if (pending.getExpiresAt() != null && !pending.getExpiresAt().isAfter(LocalDateTime.now())) {
                // 上一轮已过期但任务尚未推进：本轮发起前先落 EXPIRED，允许立即重新发起。
                markExpired(pending);
                verificationMapper.updateById(pending);
            } else {
                throw new BusinessException(409, "该账户已有进行中的打款验证，请先完成或等待过期");
            }
        }
        int used = verificationMapper.countByAccountId(accountId);
        if (used >= MAX_ATTEMPTS) {
            throw new BusinessException(409, "打款验证次数已用完（最多 " + MAX_ATTEMPTS + " 次），请走人工核验");
        }

        MerchantPayoutVerification verification = new MerchantPayoutVerification();
        verification.setAccountId(accountId);
        verification.setMerchantId(merchantId);
        verification.setAttemptNo(used + 1);
        verification.setAmount(randomAmount());
        verification.setStatus(STATUS_PENDING);
        verification.setTransferVoucherNo(transferVoucherNo == null || transferVoucherNo.isBlank()
                ? null : transferVoucherNo.trim());
        verification.setRequestedBy(operatorId);
        verification.setRequestedAt(LocalDateTime.now());
        verification.setExpiresAt(LocalDateTime.now().plusHours(expireHours));
        try {
            verificationMapper.insert(verification);
        } catch (DuplicateKeyException e) {
            // 并发同时发起：uk_payout_account_attempt 兜底，事务回滚。
            throw new BusinessException(409, "打款验证发起冲突，请刷新后重试");
        }
        return verification;
    }

    /** 店长确认到账金额：必须本店铺记录；金额匹配则账户通过核验（同事务）。 */
    @Transactional(rollbackFor = Exception.class)
    public MerchantPayoutVerification confirmByMerchant(Long verificationId, Long merchantId,
                                                        BigDecimal amount, Long operatorId) {
        MerchantPayoutVerification verification = requireLocked(verificationId);
        if (!merchantId.equals(verification.getMerchantId())) {
            throw new BusinessException(404, "打款验证不存在");
        }
        return doConfirm(verification, amount, operatorId);
    }

    /** 超管代确认到账金额：确认人不得与发起人同一人（建议双人）。 */
    @Transactional(rollbackFor = Exception.class)
    public MerchantPayoutVerification confirmByAdmin(Long verificationId, BigDecimal amount, Long operatorId) {
        MerchantPayoutVerification verification = requireLocked(verificationId);
        if (operatorId != null && operatorId.equals(verification.getRequestedBy())) {
            throw new BusinessException(403, "打款验证发起人与确认人不能为同一人");
        }
        return doConfirm(verification, amount, operatorId);
    }

    /** 过期任务单条处理：PENDING 且已过期的置 EXPIRED；返回是否处理。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean expireOne(Long verificationId) {
        MerchantPayoutVerification verification = verificationMapper.lockById(verificationId);
        if (verification == null || !STATUS_PENDING.equals(verification.getStatus())
                || verification.getExpiresAt() == null || verification.getExpiresAt().isAfter(LocalDateTime.now())) {
            return false;
        }
        markExpired(verification);
        verificationMapper.updateById(verification);
        return true;
    }

    public List<Long> findExpiredPendingIds(LocalDateTime now, int limit) {
        return verificationMapper.findExpiredPendingIds(now, limit);
    }

    public List<MerchantPayoutVerification> listByAccount(Long accountId) {
        return verificationMapper.findByAccountId(accountId);
    }

    public List<MerchantPayoutVerification> listByMerchant(Long merchantId) {
        return verificationMapper.findByMerchantId(merchantId);
    }

    /** 平台侧视图：含金额（平台需要知道转账金额），仅超管接口使用。 */
    public Map<String, Object> adminView(MerchantPayoutVerification verification) {
        Map<String, Object> view = baseView(verification);
        view.put("amount", verification.getAmount());
        view.put("transferVoucherNo", verification.getTransferVoucherNo());
        return view;
    }

    /** 商家侧视图：绝不返回金额与转账凭证号，防止验证失去意义。 */
    public Map<String, Object> merchantView(MerchantPayoutVerification verification) {
        return baseView(verification);
    }

    public List<Map<String, Object>> merchantViews(Long merchantId) {
        return listByMerchant(merchantId).stream().map(this::merchantView).collect(Collectors.toList());
    }

    private Map<String, Object> baseView(MerchantPayoutVerification verification) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", verification.getId());
        view.put("accountId", verification.getAccountId());
        view.put("merchantId", verification.getMerchantId());
        view.put("attemptNo", verification.getAttemptNo());
        view.put("status", verification.getStatus());
        view.put("requestedAt", verification.getRequestedAt());
        view.put("expiresAt", verification.getExpiresAt());
        view.put("confirmedAt", verification.getConfirmedAt());
        view.put("failureCode", verification.getFailureCode());
        return view;
    }

    private MerchantPayoutVerification doConfirm(MerchantPayoutVerification verification,
                                                 BigDecimal amount, Long operatorId) {
        if (!STATUS_PENDING.equals(verification.getStatus())) {
            throw new BusinessException(409, "该打款验证已完结，请勿重复确认");
        }
        if (verification.getExpiresAt() == null || !verification.getExpiresAt().isAfter(LocalDateTime.now())) {
            // 过期状态推进交给过期任务/下次发起；此处只拒绝，不写状态（避免抛错回滚吃掉状态变更）。
            throw new BusinessException(409, "打款验证已过期，请重新发起");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0 || amount.scale() > 2) {
            throw new BusinessException(400, "到账金额不合法");
        }
        verification.setConfirmedAt(LocalDateTime.now());
        verification.setConfirmedBy(operatorId);
        if (verification.getAmount().compareTo(amount) == 0) {
            verification.setStatus(STATUS_SUCCESS);
            verificationMapper.updateById(verification);
            // 同事务推进账户核验：成功即 VERIFIED + 默认切换；任何失败整体回滚。
            accountService.confirmPayoutSuccess(verification.getAccountId(), operatorId);
            return verification;
        }
        verification.setStatus(STATUS_FAILED);
        verification.setFailureCode(FAILURE_AMOUNT_MISMATCH);
        verificationMapper.updateById(verification);
        if (verificationMapper.countFailedByAccountId(verification.getAccountId()) >= MAX_ATTEMPTS) {
            accountService.rejectForPayoutExceeded(verification.getAccountId(), operatorId);
        }
        return verification;
    }

    private MerchantPayoutVerification requireLocked(Long verificationId) {
        MerchantPayoutVerification verification = verificationId == null
                ? null : verificationMapper.lockById(verificationId);
        if (verification == null) {
            throw new BusinessException(404, "打款验证不存在");
        }
        return verification;
    }

    private void markExpired(MerchantPayoutVerification verification) {
        verification.setStatus(STATUS_EXPIRED);
        verification.setFailureCode(FAILURE_EXPIRED);
    }

    /** 服务端生成 0.01-0.99 随机金额；客户端不得指定。 */
    private BigDecimal randomAmount() {
        return BigDecimal.valueOf(secureRandom.nextInt(99) + 1, 2);
    }
}
