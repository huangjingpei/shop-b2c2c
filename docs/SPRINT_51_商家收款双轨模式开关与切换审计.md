<!--
文件：SPRINT_51_商家收款双轨模式开关与切换审计.md
用途：记录 M1（收款模式总闸、商家级切换、渠道绑定前置校验、切换审计、生效模式解析）的实际子范围、接口、数据语义、验证边界与未验证项。
维护注意：
  1. 本文只描述 M1 已实现的开关与审计；下单按生效模式、平台代收台账、分账/退分属于 M2–M4，不得提前宣称完成。
  2. 本功能不替换 PaymentGatewayService 的阶段一入口；PLATFORM_COLLECT 支付仍被显式拒绝，避免在分账未接入时收钱。
  3. 切换只影响新订单，不回写 trade_order，也不改在途订单、退款或分账账本。
  4. 测试命令由用户手动执行；代理未运行 mvn，所有结果标记为“待人工验证”。
  5. 不记录真实商户号、卡号、密钥或渠道报文。
-->
# Sprint 51：商家收款双轨模式开关与切换审计（M1）

- 日期：2026-11-08
- 主台账：F02（支付渠道与分账）
- 设计来源：[2026-11-08-商家收款双轨与微信电商收付通进件设计](2026-11-08-商家收款双轨与微信电商收付通进件设计.md)
- 执行方：代码代理（实现、用例、静态审阅）
- 验证状态：待人工验证（代理未执行 Maven/数据库命令）

## 1. 本轮实际子范围

| 项 | 已做 | 未做 |
| --- | --- | --- |
| 全局总闸 | `platform_collection_config` 单行 id=1、`enabled/version/updated_by`、超管读写 | 按环境的多实例配置中心同步 |
| 商家级模式 | `merchant_store.collection_mode`，仅 `MERCHANT_DIRECT`/`PLATFORM_COLLECT` | 批量导入/批量切换 |
| 切换前置 | 切平台代收校验 `binding_status=BOUND`、`sub_mchid` 非空、`settlement_account_id` 非空，缺一 409 | 进件资料提交与微信真实审核（M2/M6） |
| 切换审计 | `merchant_collection_switch_audit` 只追加，记录 from/to/reason/operator/created_at | 总闸变更历史表（当前仅 updated_by/updated_at/version） |
| 生效模式 | 环境应急关闭 > 数据库总闸 > 商家配置；未知值 400 | 下单按生效模式写入新订单（M4） |
| 支付入口 | 保持阶段一行为：`PLATFORM_COLLECT` 订单仍被拒绝 | 平台代收支付、分账、退分（M4） |
| 前端 | 未改页面 | 管理端切换与审核页面（M3）、商家收款账户页（M2） |

## 2. 数据与语义

### 2.1 `merchant_store.collection_mode`

- 取值：`MERCHANT_DIRECT`（默认）/`PLATFORM_COLLECT`。
- 只表示商家“配置意图”，不保证当前生效；生效由总闸与环境应急开关决定。
- 切换使用 CAS：`WHERE id=? AND collection_mode=读取值`，并发只有一个成功，只落一条审计。
- 只影响新订单；M4 接入下单时会快照到 `trade_order.collection_mode`。

### 2.2 `platform_collection_config`

- 固定单行 `id=1`；`enabled=0` 时所有新订单按商家直收解析。
- `version` 每次更新 +1；更新使用版本条件，冲突返回 409，要求刷新后重试。
- 总闸只决定“是否允许商家配置生效”，不批量改商家模式，也不代表渠道已绑定。

### 2.3 `merchant_collection_switch_audit`

- 只追加，不更新、不删除；`operator_id` 取当前会话用户。
- `reason` 可空，最长 200 字；不得写入卡号、证件号、密钥或渠道报文。
- 与 `merchant_collection_status_audit`（停收/恢复）语义不同，禁止混用。

### 2.4 生效模式优先级

1. 环境变量 `PLATFORM_COLLECTION_EMERGENCY_DISABLED=true`：强制 `MERCHANT_DIRECT`（应急关闭，只用于降级）。
2. 数据库总闸 `platform_collection_config.enabled=false`：`MERCHANT_DIRECT`。
3. 总闸开启：按 `merchant_store.collection_mode` 返回。
4. 未知模式：400，不自动降级。

说明：切平台代收时已强制校验绑定；若后续绑定失效，M4 在下单前仍须再次校验，不能只信本开关。

## 3. 接口（全部仅 SUPERADMIN）

| 方法 | 路径 | 用途 | 关键返回/错误 |
| --- | --- | --- | --- |
| GET | `/api/v1/admin/settlement/platform-collection/config` | 查询总闸 | `enabled/emergencyDisabled/version/updatedBy/updatedAt` |
| PUT | `/api/v1/admin/settlement/platform-collection/config` | 更新总闸 | `enabled` 缺失 400；版本冲突 409 |
| GET | `/api/v1/admin/settlement/merchants/{merchantId}/collection-mode` | 商家模式与生效模式 | 含 `configuredMode/effectiveMode/bindingReady/blockReason`，不返回卡号或 sub_mchid |
| PUT | `/api/v1/admin/settlement/merchants/{merchantId}/collection-mode` | 切换模式 | 未知模式 400；重复模式 409；缺绑定三要素 409；并发 CAS 失败 409 |
| GET | `/api/v1/admin/settlement/merchants/{merchantId}/collection-switch-audits` | 切换审计 | 按 id 倒序，最多 100 条 |

权限：`SaTokenConfigure` 对 `/api/v1/admin/**` 统一拦截，控制器内再次 `checkRole("SUPERADMIN")`；商家角色与未登录均不可操作。

## 4. 文件清单

| 文件 | 变化 |
| --- | --- |
| `src/main/resources/db/schema.sql` | 新列、两张表、默认总闸行 |
| `src/main/resources/db/migrations/V23__merchant_collection_dual_mode.sql` | 幂等补列/建表/默认行 |
| `src/test/resources/sprint1-schema.sql` | H2 夹具同步（不预置默认行，由用例写入） |
| `entity/MerchantStore.java` | 新增 `collectionMode` |
| `entity/PlatformCollectionConfig.java` | 总闸实体 |
| `entity/MerchantCollectionSwitchAudit.java` | 切换审计实体 |
| `mapper/PlatformCollectionConfigMapper.java` | 单行乐观更新 |
| `mapper/MerchantCollectionSwitchAuditMapper.java` | 按商户分页只读 |
| `mapper/MerchantStoreMapper.java` | 收款模式 CAS |
| `dto/MerchantCollectionModeDTOs.java` | 请求/响应，脱敏 |
| `service/MerchantCollectionModeService.java` | 总闸、切换、前置校验、审计、生效模式 |
| `controller/AdminSettlementController.java` | 5 个超管接口 |
| `common/CollectionMode.java` | 新增 `requireKnown`，阶段一拒绝逻辑不变 |
| `src/main/resources/application.yml` | 应急关闭环境变量，默认 false |
| `test/.../MerchantCollectionModeServiceTest.java` | 13 个隔离规则用例 |
| `test/.../MerchantCollectionModeIntegrationTest.java` | H2 权限/409/落库/审计集成用例 |
| `test/.../SchemaMigrationContractTest.java` | V23 幂等与非破坏静态断言 |

## 5. 验证命令与预期（用户手动执行）

```bash
mvn -f shop-b2b2c-backend/pom.xml -Dtest=MerchantCollectionModeServiceTest test
mvn -f shop-b2b2c-backend/pom.xml -Dtest=MerchantCollectionModeIntegrationTest test
mvn -f shop-b2b2c-backend/pom.xml -Dtest=SchemaMigrationContractTest test
```

预期：

- 单元测试覆盖环境应急关闭、总闸关闭/开启、缺账户/非 BOUND/缺 sub_mchid/缺结算账户、CAS 冲突、切回直收、非法模式、原因超长、总闸空值与版本冲突。
- H2 用例覆盖未登录 401、商家角色 403、未绑定 409 且店铺不变、绑定后切换成功、审计两条、总闸开启后生效模式变化、非法模式 400。
- 静态用例覆盖 V23 存在性、`CREATE TABLE IF NOT EXISTS`、默认行幂等、无 UPDATE/DELETE/DROP。
- H2 与静态测试通过不等于 MySQL 真实迁移或微信渠道联调通过。

## 6. 未验证项与已知问题

1. **未执行编译/测试**：按项目约定由用户运行，当前全部标记待验证。
2. **无真实渠道凭据**：没有微信服务商商户号、APIv3 密钥/证书与二级商户进件，任何“联调通过”结论都不成立。
3. **`merchant_payment_account` 无写入流程**：M1 只做读取与前置校验；存量数据可能为空，切平台代收会被 409 拦截。进件写入属 M2/M6。
4. **H2 默认值差异**：夹具 `apply_status` 默认 `PASSED`，MySQL 默认 `AUDITING`；集成用例显式写 `PASSED`，不证明审核流程正确。
5. **并发只落一条审计**：由 CAS 与服务层用例证明；真实 MySQL 的并发/唯一键/外键仍需用户演练。
6. **总闸变更无独立历史表**：当前只有 `updated_by/updated_at/version`，若需完整追溯总闸历史，后续单独建表，不在 M1 隐式扩展。
7. **未接入下单**：`OrderController`/`PeerPayServiceImpl` 仍固定 `MERCHANT_DIRECT`，`PaymentGatewayService` 仍拒绝 `PLATFORM_COLLECT`；M4 才切换。
8. **前端未改**：商家页仍有假“审核中”与写死银行名（M2 处理），本轮不动前端。
