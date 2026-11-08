/*
 * 文件：MerchantCollectionModeService.java
 * 用途：M1 商家收款双轨模式开关：全局总闸、单商家模式切换、前置绑定校验、切换审计与生效模式解析。
 * 维护注意：
 *  1. 切换只改 merchant_store.collection_mode，只影响新订单；不得回写 trade_order，也不得改在途订单/退款。
 *  2. 切到 PLATFORM_COLLECT 必须先满足资质硬闸（PlatformCollectionService.enabled()，即
 *     shop.settlement.platform-collect-enabled=true），再校验 merchant_payment_account.binding_status=BOUND、
 *     sub_mchid 非空、settlement_account_id 非空；任一不满足直接 409，不做“先切后补”，
 *     避免资质未开通时误切导致该商家新订单全部 409 停售。
 *  3. 生效模式优先级：环境变量应急关闭 > 数据库总闸 enabled > 商家配置模式；M4 起下单经 {@link #modeForNewOrder}
 *     写订单快照，平台代收还须分账通道已启用且绑定就绪，否则 409 拒绝建单，不降级为商家直收。
 *     支付通道入口（PaymentGatewayService）对 PLATFORM_COLLECT 仍拒绝，平台收银台接入由 M6 依据真实凭据完成。
 *  4. 模式更新使用 CAS（当前模式仍等于读取值才更新），并发切换只有一个成功并只写一条审计。
 *  5. 不返回银行卡号、证件号、渠道密钥或原始报文。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.CollectionMode;
import com.shop.b2b2c.dto.MerchantCollectionModeDTOs;
import com.shop.b2b2c.entity.MerchantCollectionSwitchAudit;
import com.shop.b2b2c.entity.MerchantPaymentAccount;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.PlatformCollectionConfig;
import com.shop.b2b2c.mapper.MerchantCollectionSwitchAuditMapper;
import com.shop.b2b2c.mapper.MerchantPaymentAccountMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.PlatformCollectionConfigMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MerchantCollectionModeService {

    private static final long CONFIG_ID = 1L;
    private static final int REASON_MAX_LENGTH = 200;

    private final MerchantStoreMapper storeMapper;
    private final PlatformCollectionConfigMapper configMapper;
    private final MerchantCollectionSwitchAuditMapper auditMapper;
    private final MerchantPaymentAccountMapper paymentAccountMapper;
    /** M4：下单前读取平台代收分账通道（资质）开关；阶段一商家直收不经过此依赖。 */
    private final PlatformCollectionService platformCollectionService;
    private final boolean emergencyDisabled;

    public MerchantCollectionModeService(MerchantStoreMapper storeMapper,
                                         PlatformCollectionConfigMapper configMapper,
                                         MerchantCollectionSwitchAuditMapper auditMapper,
                                         MerchantPaymentAccountMapper paymentAccountMapper,
                                         PlatformCollectionService platformCollectionService,
                                         @Value("${payment.platform-collection.emergency-disabled:false}")
                                         boolean emergencyDisabled) {
        this.storeMapper = storeMapper;
        this.configMapper = configMapper;
        this.auditMapper = auditMapper;
        this.paymentAccountMapper = paymentAccountMapper;
        this.platformCollectionService = platformCollectionService;
        this.emergencyDisabled = emergencyDisabled;
    }

    /** 全局总闸配置；行缺失时按关闭处理，不隐式开启平台代收。 */
    public MerchantCollectionModeDTOs.ConfigView configView() {
        return toConfigView(configOrDefault(), emergencyDisabled);
    }

    /**
     * 更新全局总闸：只改平台代收是否允许生效，不批量改商家模式，也不回填任何订单。
     */
    @Transactional(rollbackFor = Exception.class)
    public MerchantCollectionModeDTOs.ConfigView updateConfig(Boolean enabled, Long operatorId) {
        if (enabled == null) {
            throw new BusinessException(400, "enabled 不能为空");
        }
        PlatformCollectionConfig current = configMapper.findSingleton();
        if (current == null) {
            PlatformCollectionConfig created = new PlatformCollectionConfig();
            created.setId(CONFIG_ID);
            created.setEnabled(enabled);
            created.setVersion(0L);
            created.setUpdatedBy(operatorId);
            created.setCreatedAt(LocalDateTime.now());
            created.setUpdatedAt(LocalDateTime.now());
            configMapper.insert(created);
            return toConfigView(created, emergencyDisabled);
        }
        long expectedVersion = current.getVersion() == null ? 0L : current.getVersion();
        int changed = configMapper.updateSingleton(enabled, operatorId, expectedVersion);
        if (changed == 0) {
            throw new BusinessException(409, "平台代收总闸已被其他操作更新，请刷新后重试");
        }
        return toConfigView(configMapper.findSingleton(), emergencyDisabled);
    }

    /** 商家配置模式、生效模式与渠道绑定就绪情况；不回传任何账户敏感字段。 */
    public MerchantCollectionModeDTOs.MerchantModeView merchantModeView(Long merchantId) {
        MerchantStore store = requireStore(merchantId);
        PlatformCollectionConfig config = configOrDefault();
        String configuredMode = CollectionMode.requireKnown(store.getCollectionMode());
        String payChannel = normalizedChannel(store);
        MerchantPaymentAccount account = paymentAccountMapper
                .findByMerchantIdAndChannel(merchantId, payChannel).orElse(null);

        MerchantCollectionModeDTOs.MerchantModeView view = new MerchantCollectionModeDTOs.MerchantModeView();
        view.setMerchantId(merchantId);
        view.setConfiguredMode(configuredMode);
        view.setEffectiveMode(effectiveMode(configuredMode, config));
        view.setGlobalEnabled(Boolean.TRUE.equals(config.getEnabled()));
        view.setEmergencyDisabled(emergencyDisabled);
        view.setPayChannel(payChannel);
        view.setBindingStatus(account == null ? null : account.getBindingStatus());
        String blockReason = bindingBlockReason(account);
        view.setBindingReady(blockReason == null);
        view.setBlockReason(blockReason);
        return view;
    }

    /**
     * 切换商家收款模式：切平台代收前强制校验渠道绑定；切回商家直收不设前置，始终允许。
     */
    @Transactional(rollbackFor = Exception.class)
    public MerchantCollectionModeDTOs.MerchantModeView switchMode(Long merchantId, String mode,
                                                                  String reason, Long operatorId) {
        String toMode = CollectionMode.requireKnown(mode);
        String safeReason = normalizeReason(reason);
        MerchantStore store = requireStore(merchantId);
        String fromMode = CollectionMode.requireKnown(store.getCollectionMode());
        if (fromMode.equals(toMode)) {
            throw new BusinessException(409, "收款模式已是 " + toMode + "，无需重复切换");
        }
        if (CollectionMode.PLATFORM_COLLECT.equals(toMode)) {
            if (!platformCollectionService.enabled()) {
                throw new BusinessException(409, "无法切换到平台代收：分账通道未启用"
                        + "（需分账资质与通道联调，shop.settlement.platform-collect-enabled=true）");
            }
            String channel = normalizedChannel(store);
            MerchantPaymentAccount account = paymentAccountMapper
                    .findByMerchantIdAndChannel(merchantId, channel).orElse(null);
            String blockReason = bindingBlockReason(account);
            if (blockReason != null) {
                throw new BusinessException(409, "无法切换到平台代收：" + blockReason
                        + "（渠道 " + channel + "）");
            }
        }
        int changed = storeMapper.updateCollectionModeCas(merchantId, fromMode, toMode);
        if (changed == 0) {
            throw new BusinessException(409, "收款模式已被其他操作变更，请刷新后重试");
        }
        MerchantCollectionSwitchAudit audit = new MerchantCollectionSwitchAudit();
        audit.setMerchantId(merchantId);
        audit.setFromMode(fromMode);
        audit.setToMode(toMode);
        audit.setReason(safeReason);
        audit.setOperatorId(operatorId);
        auditMapper.insert(audit);
        return merchantModeView(merchantId);
    }

    /** 切换审计：只读、按商户倒序，最多 100 条。 */
    public List<MerchantCollectionSwitchAudit> audits(Long merchantId, int limit) {
        requireStore(merchantId);
        return auditMapper.findByMerchant(merchantId, limit);
    }

    /** 解析生效模式但不做下单前置校验；后台查询与下单前校验共用同一优先级。 */
    public String effectiveMode(Long merchantId) {
        MerchantStore store = requireStore(merchantId);
        return effectiveMode(CollectionMode.requireKnown(store.getCollectionMode()), configOrDefault());
    }

    /**
     * M4：下单前解析订单应写入的收款模式快照。
     * 商家直收直接返回；平台代收必须在建单时确认分账通道已启用且渠道绑定仍满足三要素，
     * 否则 409 拒绝建单——不降级为商家直收，也不先建单再补绑定。
     */
    public String modeForNewOrder(Long merchantId, String payChannel) {
        MerchantStore store = requireStore(merchantId);
        String mode = effectiveMode(CollectionMode.requireKnown(store.getCollectionMode()), configOrDefault());
        if (!CollectionMode.PLATFORM_COLLECT.equals(mode)) {
            return mode;
        }
        if (!platformCollectionService.enabled()) {
            throw new BusinessException(409, "平台代收分账通道未启用（需分账资质与通道联调），暂不能创建平台代收订单");
        }
        String channel = payChannel == null || payChannel.isBlank()
                ? normalizedChannel(store) : com.shop.b2b2c.common.PayChannel.normalize(payChannel);
        MerchantPaymentAccount account = paymentAccountMapper
                .findByMerchantIdAndChannel(merchantId, channel).orElse(null);
        String blockReason = bindingBlockReason(account);
        if (blockReason != null) {
            throw new BusinessException(409, "平台代收渠道未就绪：" + blockReason + "（渠道 " + channel + "）");
        }
        return mode;
    }

    private String effectiveMode(String configuredMode, PlatformCollectionConfig config) {
        if (emergencyDisabled || !Boolean.TRUE.equals(config.getEnabled())) {
            return CollectionMode.MERCHANT_DIRECT;
        }
        return configuredMode;
    }

    private MerchantStore requireStore(Long merchantId) {
        if (merchantId == null || merchantId <= 0) {
            throw new BusinessException(400, "merchantId 非法");
        }
        return storeMapper.findById(merchantId)
                .orElseThrow(() -> new BusinessException(404, "店铺不存在: " + merchantId));
    }

    private PlatformCollectionConfig configOrDefault() {
        PlatformCollectionConfig config = configMapper.findSingleton();
        if (config == null) {
            config = new PlatformCollectionConfig();
            config.setId(CONFIG_ID);
            config.setEnabled(false);
            config.setVersion(0L);
        }
        return config;
    }

    private String normalizedChannel(MerchantStore store) {
        String channel = store.getPayChannel();
        return channel == null || channel.isBlank() ? "WECHAT" : channel.trim().toUpperCase();
    }

    /**
     * 返回 null 表示满足前置；否则返回可直接展示的原因。仅校验进件绑定三要素，不读取卡号明文。
     */
    private String bindingBlockReason(MerchantPaymentAccount account) {
        if (account == null) {
            return "未找到该渠道的进件账户记录";
        }
        if (!"BOUND".equals(account.getBindingStatus())) {
            return "渠道绑定状态为 " + (account.getBindingStatus() == null ? "空" : account.getBindingStatus())
                    + "，需 BOUND";
        }
        if (account.getSubMchid() == null || account.getSubMchid().isBlank()) {
            return "缺少渠道二级商户号 sub_mchid";
        }
        if (account.getSettlementAccountId() == null) {
            return "缺少结算账户 settlement_account_id";
        }
        return null;
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.length() > REASON_MAX_LENGTH) {
            throw new BusinessException(400, "切换原因不能超过 " + REASON_MAX_LENGTH + " 字");
        }
        return trimmed;
    }

    private MerchantCollectionModeDTOs.ConfigView toConfigView(PlatformCollectionConfig config,
                                                               boolean emergency) {
        MerchantCollectionModeDTOs.ConfigView view = new MerchantCollectionModeDTOs.ConfigView();
        view.setEnabled(Boolean.TRUE.equals(config.getEnabled()));
        view.setEmergencyDisabled(emergency);
        view.setVersion(config.getVersion() == null ? 0L : config.getVersion());
        view.setUpdatedBy(config.getUpdatedBy());
        view.setUpdatedAt(config.getUpdatedAt());
        return view;
    }
}
