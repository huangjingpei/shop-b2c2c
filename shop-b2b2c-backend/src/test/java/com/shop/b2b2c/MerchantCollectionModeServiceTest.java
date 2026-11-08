/*
 * 文件：MerchantCollectionModeServiceTest.java
 * 用途：M1 收款双轨模式开关的隔离规则用例：生效模式优先级、非法/重复切换、平台代收前置
 *      （资质硬闸 + 绑定三要素）、切回直收、CAS 并发冲突、总闸更新与切换审计写入。
 * 维护注意：
 *  1. 全部 Mockito 隔离，不连数据库；真实唯一键/外键、权限 401/403 与审计持久化由
 *     MerchantCollectionModeIntegrationTest 的 H2 用例覆盖。
 *  2. 锁定“切平台代收必须资质硬闸已启用 + BOUND + sub_mchid + settlement_account_id，缺一即 409 且不写审计”
 *     的语义；若未来放宽前置或允许先切后补，本用例必须失败并同步设计文档。
 *  3. 不验证任何渠道 API；当前无真实微信/支付宝凭据。
 */
package com.shop.b2b2c;

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
import com.shop.b2b2c.service.MerchantCollectionModeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MerchantCollectionModeServiceTest {

    private static final Long MERCHANT_ID = 12L;
    private static final Long OPERATOR_ID = 900L;

    private final MerchantStoreMapper stores = mock(MerchantStoreMapper.class);
    private final PlatformCollectionConfigMapper configs = mock(PlatformCollectionConfigMapper.class);
    private final MerchantCollectionSwitchAuditMapper audits = mock(MerchantCollectionSwitchAuditMapper.class);
    private final MerchantPaymentAccountMapper accounts = mock(MerchantPaymentAccountMapper.class);
    private final com.shop.b2b2c.service.PlatformCollectionService platforms =
            mock(com.shop.b2b2c.service.PlatformCollectionService.class);

    @Test
    void emergencySwitchForcesMerchantDirectEvenWhenGateAndStoreSayPlatform() {
        MerchantCollectionModeService service = service(true);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(store(CollectionMode.PLATFORM_COLLECT)));
        when(configs.findSingleton()).thenReturn(config(true));

        assertEquals(CollectionMode.MERCHANT_DIRECT, service.effectiveMode(MERCHANT_ID));

        MerchantCollectionModeDTOs.MerchantModeView view = service.merchantModeView(MERCHANT_ID);
        assertEquals(CollectionMode.PLATFORM_COLLECT, view.getConfiguredMode());
        assertEquals(CollectionMode.MERCHANT_DIRECT, view.getEffectiveMode());
        assertTrue(view.isGlobalEnabled());
        assertTrue(view.isEmergencyDisabled());
    }

    @Test
    void globalGateOffKeepsEffectiveModeDirectUntilOperatorTurnsItOn() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(store(CollectionMode.PLATFORM_COLLECT)));
        when(configs.findSingleton()).thenReturn(config(false));

        assertEquals(CollectionMode.MERCHANT_DIRECT, service.effectiveMode(MERCHANT_ID));

        when(configs.findSingleton()).thenReturn(config(true));
        assertEquals(CollectionMode.PLATFORM_COLLECT, service.effectiveMode(MERCHANT_ID));
    }

    @Test
    void missingConfigRowIsTreatedAsDisabled() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(store(CollectionMode.PLATFORM_COLLECT)));
        when(configs.findSingleton()).thenReturn(null);

        assertEquals(CollectionMode.MERCHANT_DIRECT, service.effectiveMode(MERCHANT_ID));
        assertFalse(service.configView().getEnabled());
    }

    @Test
    void unknownModeIsRejectedWithoutTouchingStoreOrAudit() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(store(CollectionMode.MERCHANT_DIRECT)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.switchMode(MERCHANT_ID, "ALIPAY_DIRECT", "试点", OPERATOR_ID));

        assertEquals(400, error.getCode());
        verify(stores, never()).updateCollectionModeCas(anyLong(), anyString(), anyString());
        verifyNoInteractions(audits);
    }

    @Test
    void missingMerchantIsNotFound() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.switchMode(MERCHANT_ID, CollectionMode.PLATFORM_COLLECT, null, OPERATOR_ID));

        assertEquals(404, error.getCode());
        verifyNoInteractions(audits);
    }

    @Test
    void repeatingCurrentModeIsConflict() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(store(CollectionMode.MERCHANT_DIRECT)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.switchMode(MERCHANT_ID, CollectionMode.MERCHANT_DIRECT, null, OPERATOR_ID));

        assertEquals(409, error.getCode());
        verify(stores, never()).updateCollectionModeCas(anyLong(), anyString(), anyString());
        verifyNoInteractions(audits);
    }

    @Test
    void platformSwitchIsRejectedWhenQualificationChannelIsDisabled() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(store(CollectionMode.MERCHANT_DIRECT)));
        when(platforms.enabled()).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.switchMode(MERCHANT_ID, CollectionMode.PLATFORM_COLLECT, "试点", OPERATOR_ID));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("分账通道未启用"));
        // 资质硬闸先于绑定校验：不查账户、不更新、不写审计。
        verify(accounts, never()).findByMerchantIdAndChannel(anyLong(), anyString());
        verify(stores, never()).updateCollectionModeCas(anyLong(), anyString(), anyString());
        verifyNoInteractions(audits);
    }

    @Test
    void platformSwitchRequiresExistingBoundAccountWithSubMchidAndSettlementAccount() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(store(CollectionMode.MERCHANT_DIRECT)));
        when(platforms.enabled()).thenReturn(true);

        // 无进件账户
        when(accounts.findByMerchantIdAndChannel(MERCHANT_ID, "WECHAT")).thenReturn(Optional.empty());
        assertPlatformSwitchRejected(service, "进件账户");

        // 未绑定 BOUND
        MerchantPaymentAccount account = account("BINDING", "MCH-1", 31L);
        when(accounts.findByMerchantIdAndChannel(MERCHANT_ID, "WECHAT")).thenReturn(Optional.of(account));
        assertPlatformSwitchRejected(service, "BOUND");

        // 缺二级商户号
        account.setBindingStatus("BOUND");
        account.setSubMchid("  ");
        assertPlatformSwitchRejected(service, "sub_mchid");

        // 缺结算账户
        account.setSubMchid("MCH-1");
        account.setSettlementAccountId(null);
        assertPlatformSwitchRejected(service, "settlement_account_id");

        verify(stores, never()).updateCollectionModeCas(anyLong(), anyString(), anyString());
        verifyNoInteractions(audits);
    }

    @Test
    void switchingToPlatformUsesCasAndWritesOneAudit() {
        MerchantCollectionModeService service = service(false);
        MerchantStore direct = store(CollectionMode.MERCHANT_DIRECT);
        MerchantStore platform = store(CollectionMode.PLATFORM_COLLECT);
        // 第一次读取用于切换前校验，切换成功后的视图重新读取已变更的店铺。
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(direct), Optional.of(platform));
        when(platforms.enabled()).thenReturn(true);
        when(accounts.findByMerchantIdAndChannel(MERCHANT_ID, "WECHAT"))
                .thenReturn(Optional.of(account("BOUND", "MCH-1", 31L)));
        when(configs.findSingleton()).thenReturn(config(true));
        when(stores.updateCollectionModeCas(MERCHANT_ID, CollectionMode.MERCHANT_DIRECT,
                CollectionMode.PLATFORM_COLLECT)).thenReturn(1);

        MerchantCollectionModeDTOs.MerchantModeView view = service.switchMode(
                MERCHANT_ID, CollectionMode.PLATFORM_COLLECT, "  首批试点  ", OPERATOR_ID);

        assertEquals(CollectionMode.PLATFORM_COLLECT, view.getConfiguredMode());
        assertEquals(CollectionMode.PLATFORM_COLLECT, view.getEffectiveMode());
        assertTrue(view.isBindingReady());
        assertNull(view.getBlockReason());

        ArgumentCaptor<MerchantCollectionSwitchAudit> captor =
                ArgumentCaptor.forClass(MerchantCollectionSwitchAudit.class);
        verify(audits).insert(captor.capture());
        MerchantCollectionSwitchAudit audit = captor.getValue();
        assertEquals(MERCHANT_ID, audit.getMerchantId());
        assertEquals(CollectionMode.MERCHANT_DIRECT, audit.getFromMode());
        assertEquals(CollectionMode.PLATFORM_COLLECT, audit.getToMode());
        assertEquals("首批试点", audit.getReason());
        assertEquals(OPERATOR_ID, audit.getOperatorId());
    }

    @Test
    void concurrentSwitchLosingCasGetsConflictAndWritesNoAudit() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(store(CollectionMode.MERCHANT_DIRECT)));
        when(platforms.enabled()).thenReturn(true);
        when(accounts.findByMerchantIdAndChannel(MERCHANT_ID, "WECHAT"))
                .thenReturn(Optional.of(account("BOUND", "MCH-1", 31L)));
        when(stores.updateCollectionModeCas(anyLong(), anyString(), anyString())).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.switchMode(MERCHANT_ID, CollectionMode.PLATFORM_COLLECT, null, OPERATOR_ID));

        assertEquals(409, error.getCode());
        verifyNoInteractions(audits);
    }

    @Test
    void switchingBackToDirectNeedsNoBindingAndStillAudits() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(
                Optional.of(store(CollectionMode.PLATFORM_COLLECT)),
                Optional.of(store(CollectionMode.MERCHANT_DIRECT)));
        when(configs.findSingleton()).thenReturn(config(true));
        when(stores.updateCollectionModeCas(MERCHANT_ID, CollectionMode.PLATFORM_COLLECT,
                CollectionMode.MERCHANT_DIRECT)).thenReturn(1);

        MerchantCollectionModeDTOs.MerchantModeView view = service.switchMode(
                MERCHANT_ID, CollectionMode.MERCHANT_DIRECT, "回滚试点", OPERATOR_ID);

        assertEquals(CollectionMode.MERCHANT_DIRECT, view.getConfiguredMode());
        assertEquals(CollectionMode.MERCHANT_DIRECT, view.getEffectiveMode());
        verify(audits).insert(any(MerchantCollectionSwitchAudit.class));
    }

    @Test
    void switchReasonLongerThanTwoHundredCharactersIsRejected() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(store(CollectionMode.MERCHANT_DIRECT)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.switchMode(MERCHANT_ID, CollectionMode.PLATFORM_COLLECT,
                        "x".repeat(201), OPERATOR_ID));

        assertEquals(400, error.getCode());
        verifyNoInteractions(audits);
    }

    @Test
    void configUpdateRequiresExplicitEnabledAndRejectsStaleVersion() {
        MerchantCollectionModeService service = service(false);
        assertThrows(BusinessException.class, () -> service.updateConfig(null, OPERATOR_ID));

        when(configs.findSingleton()).thenReturn(config(false));
        when(configs.updateSingleton(eq(true), eq(OPERATOR_ID), eq(7L))).thenReturn(0);

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> service.updateConfig(true, OPERATOR_ID));
        assertEquals(409, conflict.getCode());
    }

    @Test
    void configUpdateCreatesSingletonWhenMissing() {
        MerchantCollectionModeService service = service(false);
        when(configs.findSingleton()).thenReturn(null);

        MerchantCollectionModeDTOs.ConfigView view = service.updateConfig(true, OPERATOR_ID);

        verify(configs).insert(any(PlatformCollectionConfig.class));
        assertTrue(view.getEnabled());
        assertEquals(0L, view.getVersion());
    }

    @Test
    void newDirectOrderDoesNotQueryPlatformChannelOrBinding() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(store(CollectionMode.MERCHANT_DIRECT)));
        when(configs.findSingleton()).thenReturn(config(true));

        assertEquals(CollectionMode.MERCHANT_DIRECT, service.modeForNewOrder(MERCHANT_ID, "WECHAT"));
        verifyNoInteractions(platforms);
        verify(accounts, never()).findByMerchantIdAndChannel(anyLong(), anyString());
    }

    @Test
    void newOrderPrefersEffectiveGateOverConfiguredPlatformMode() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(store(CollectionMode.PLATFORM_COLLECT)));
        when(configs.findSingleton()).thenReturn(config(false));

        assertEquals(CollectionMode.MERCHANT_DIRECT, service.modeForNewOrder(MERCHANT_ID, "WECHAT"));
        verifyNoInteractions(platforms);
    }

    @Test
    void newPlatformOrderIsRejectedWhenQualificationChannelIsDisabled() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(store(CollectionMode.PLATFORM_COLLECT)));
        when(configs.findSingleton()).thenReturn(config(true));
        when(platforms.enabled()).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.modeForNewOrder(MERCHANT_ID, "WECHAT"));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("分账通道未启用"));
        verify(accounts, never()).findByMerchantIdAndChannel(anyLong(), anyString());
    }

    @Test
    void newPlatformOrderIsRejectedWhenBindingIsNotReady() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(store(CollectionMode.PLATFORM_COLLECT)));
        when(configs.findSingleton()).thenReturn(config(true));
        when(platforms.enabled()).thenReturn(true);
        when(accounts.findByMerchantIdAndChannel(MERCHANT_ID, "WECHAT"))
                .thenReturn(Optional.of(account("BINDING", "MCH-1", 31L)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.modeForNewOrder(MERCHANT_ID, "WECHAT"));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("BOUND"));
    }

    @Test
    void newPlatformOrderSnapshotsPlatformCollectOnlyWhenChannelAndBindingAreReady() {
        MerchantCollectionModeService service = service(false);
        when(stores.findById(MERCHANT_ID)).thenReturn(Optional.of(store(CollectionMode.PLATFORM_COLLECT)));
        when(configs.findSingleton()).thenReturn(config(true));
        when(platforms.enabled()).thenReturn(true);
        when(accounts.findByMerchantIdAndChannel(MERCHANT_ID, "WECHAT"))
                .thenReturn(Optional.of(account("BOUND", "MCH-1", 31L)));

        assertEquals(CollectionMode.PLATFORM_COLLECT, service.modeForNewOrder(MERCHANT_ID, "WECHAT"));
    }

    private MerchantCollectionModeService service(boolean emergencyDisabled) {
        return new MerchantCollectionModeService(stores, configs, audits, accounts, platforms, emergencyDisabled);
    }

    private void assertPlatformSwitchRejected(MerchantCollectionModeService service, String reasonKeyword) {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.switchMode(MERCHANT_ID, CollectionMode.PLATFORM_COLLECT, "试点", OPERATOR_ID));
        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains(reasonKeyword), "原因应包含: " + reasonKeyword);
        verify(stores, never()).updateCollectionModeCas(anyLong(), anyString(), anyString());
    }

    private MerchantStore store(String mode) {
        MerchantStore store = new MerchantStore();
        store.setId(MERCHANT_ID);
        store.setCollectionMode(mode);
        store.setPayChannel("WECHAT");
        return store;
    }

    private PlatformCollectionConfig config(boolean enabled) {
        PlatformCollectionConfig config = new PlatformCollectionConfig();
        config.setId(1L);
        config.setEnabled(enabled);
        config.setVersion(7L);
        return config;
    }

    private MerchantPaymentAccount account(String bindingStatus, String subMchid, Long settlementAccountId) {
        MerchantPaymentAccount account = new MerchantPaymentAccount();
        account.setMerchantId(MERCHANT_ID);
        account.setChannel("WECHAT");
        account.setBindingStatus(bindingStatus);
        account.setSubMchid(subMchid);
        account.setSettlementAccountId(settlementAccountId);
        return account;
    }
}
