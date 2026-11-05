# 自动化与手工测试执行追溯日志 (Test Execution & Regression Log)

> 本文档用于落实整治项 **R33（测试执行状态与持续回归追溯）**，记录全量测试矩阵在开发机与仿真环境下的执行证据、测试基线、覆盖指标与回归判定。


## 2026-11-05后续修订：Sprint23 / R09

执行方：代理只完成实现、用例和静态审阅；本轮没有运行编译、测试或构建。下方历史统计不覆盖本次修改，也不作为本轮通过证据。新增/调整MerchantStoreUpdateTest、ManagementSecurityTest、ServiceFeeConfigTest及Sprint1SecurityIntegrationTest，含五配置写路径的屏障并发交错、null保留/显式清日期、权限/租户及持久化异常边界。

状态：**待用户手动执行**。环境、用例数量、通过/失败/跳过均未记录为本轮已知结果；命令及预期见[Sprint23交付](SPRINT_23_店铺配置并发覆盖补修.md)。原有测试基线保留历史时点，不覆盖后续改动。

---

## 一、测试环境基线 (Test Environment Baseline)

| 维度 | 配置/版本 | 备注 |
|---|---|---|
| **操作系统** | Windows 10 / Server (PowerShell & WSL/Bash) | 宿主开发环境 |
| **JDK 版本** | OpenJDK 17.0.12 (64-Bit Server VM) | 语言级别 release 17 |
| **构建工具** | Apache Maven 3.9+ | 后端构建与测试运行器 |
| **Node.js** | v20+ (`node --test`) | 原生 TAP 13 测试运行器 |
| **数据库** | H2 In-Memory (MySQL 8.0 兼容语法模式) | 单元与集成测试隔离库 |
| **缓存/防刷** | Mock Redis Driver / Embedded Redis Fixtures | 限流与令牌桶隔离驱动 |

---

## 二、测试执行汇总 (Summary of Execution Results)

| 测试套件 | 测试文件/范围 | 总用例数 | 通过 (Pass) | 失败 (Fail) | 跳过 (Skip) | 状态 |
|---|---|---|---|---|---|---|
| **后端全量测试套件** | `shop-b2b2c-backend/src/test/java/**` | 496 | 496 | 0 | 1 | ✅ 全部通过 |
| **前端全量规则套件** | `shared/*.test.mjs` | 182 | 182 | 0 | 0 | ✅ 全部通过 |
| **网关与部署测试套件** | `deploy/tests/test_*.py` | 56 | 2 | 0 | 54 (Win隔离) | ✅ 符合预期 |

> **注**：
> - 后端 1 个跳过的用例为 `SchemaMySqlTest`（仅当本地配置真实且可连通的 MySQL 二进制实例 `schema.mysql.bin` 时激活，H2 全量模式初始化由 `SchemaInitializationTest` 验证）。
> - 部署测试中 54 个跳过的用例均标注 `@unittest.skipUnless(os.name == 'posix')`，系针对 Linux/Bash 发布脚本生命周期与容器隔离环境的自动化测试，在 Windows 宿主机上安全跳过，防范跨平台误触发。

---

## 三、后端核心安全与业务测试细分 (Backend Test Breakdown)

### 1. 认证、会话与权限隔离 (Auth & RBAC)
- **`AuthBoundaryTest`** (36 tests)
  - 覆盖各角色（GUEST, BUYER, SELLER, PLATFORM, SUPERADMIN）对后台各端点路由的访问边界。
  - 验证未授权访问返回标准鉴权错误与 401 契约。
- **`AuthSessionServiceTest`** (14 tests)
  - 验证会话令牌生成、上下文绑定、跨租户越权阻断及登出撤销状态。

### 2. 交易安全、防刷与成本限流 (Security & Cost Limiting - R07)
- **`TradeCostLimiterTest`** (24 tests)
  - 覆盖 IP 桶与业务维度复合限流。
  - 验证建单 (IP: 60/300s, 手机号: 30/300s)、预占 (IP: 60/300s, Token: 20/300s)、海报生成 (IP: 60/60s, Token: 20/60s) 三级防洪。
  - 验证限流触发时返回 429 业务错误与对应重试提示。
- **`TradeGuardsTest`** (24 tests)
  - 验证商品上下架状态、库存可用性、非法金额与负数/溢出检查。

### 3. 数据加密与唯一摘要 (Crypto & Card Fingerprint - R05/R25)
- **`CardCipherServiceTest`** (23 tests)
  - 验证 AES-256-GCM 密文结构、随机 IV (12 字节)、GCM 认证 Tag (16 字节) 与密文字段安全性。
  - 验证 SHA-256 卡密指纹生成与全库唯一性校验，杜绝同商户/全平台重复卡密导入。

### 4. 幂等控制与历史数据保留 (Idempotency Retention - R27)
- **`IdempotencyPurgeJobTest`** (6 tests)
  - `purgesExpiredCompletedRecords_singleBatch`: 单批未满额时单次删除且准确上报删除计数。
  - `purgesInBatchesUntilExhausted_twoBatches`: 批次满额时多轮驱动直到清空。
  - `purgesMultipleBatches_untilZero`: 大批量连续清理游标稳定性。
  - `doesNotDeleteIfNothingExpired`: 无过期数据时不执行无谓写入。
  - `cutoffIsRetentionHoursBeforeNow`: 严格计算保留截止线（误差 ≤ 5s）。
  - `passesBatchSizeToMapper`: 严格限制单批锁持有范围。
- **`OrderIdempotencyTest`** (12 tests)
  - 验证建单与支付重放请求的幂等结果直接返回，未完成占位排他锁互斥。

### 5. API 错误分类与 64 位整数协议 (Error Classification & BigInt - R34/R35)
- **`StrictIdDeserializerTest`** (17 tests)
  - 验证 Long 边界值反序列化：拒绝溢出值（> Long.MAX_VALUE、< Long.MIN_VALUE）、科学计数法、小数及非数字字符。
- **`BuyerOrderSecurityTest`** (18 tests)
  - 验证未捕获异常不向前端泄露堆栈或私密错误信息，统一封装为标准 HTTP/业务 code。

### 6. 端到端综合安全集成 (Sprint 1 Integration Matrix)
- **`Sprint1SecurityIntegrationTest`** (170 tests)
  - 包含超卖防范并发测试（`concurrentOrdersCannotOversellLastUnit`）。
  - 包含订单全状态机流转、代付单生成与凭证核销、卡密发放脱敏及多商户资产隔离。

---

## 四、前端规则与展示层测试细分 (Frontend Test Breakdown)

执行命令：`node --test shared/*.test.mjs`

| 测试套件文件 | 用例数 | 覆盖场景 |
|---|---|---|
| `shared/adminStoreGuard.test.mjs` | 11 | 管理端路由守卫、跨租户越权拦截、伪造角色覆盖防护 |
| `shared/claimRules.test.mjs` | 7 | 卡密提取码格式校验、首尾空格剥离、长度超界截断 |
| `shared/orderLookupRules.test.mjs` | 9 | 查单链接安全编码、敏感字段不入 URL 保证 |
| `shared/orderSnFormatter.test.mjs` | 8 | 订单号展示安全格式化与统一前缀规范 |
| `shared/orderValidation.test.mjs` | 25 | 建单表单校验、联系方式合规、超长字符过滤 |
| `shared/paginationState.test.mjs` | 15 | 有界分页游标、拒绝无效页码与巨大页码 |
| `shared/peerPayRules.test.mjs` | 18 | 代付流程状态机、代付人限额、代付完成通知状态 |
| `shared/rateLimitDefense.test.mjs` | 11 | 客户端防重复点击、429 退避策略、冷却时间倒计时 |
| `shared/storeProductValidation.test.mjs` | 16 | 商品编辑参数校验、原价现价校验、SKU 映射 |
| `shared/storedPoster.test.mjs` | 29 | 海报异步解耦拉取、大图 Base64 脱敏与失败退避 |
| `shared/tailwindBuild.test.mjs` | 6 | 本地样式转译完整性、无网络字体依赖、布局断点稳定 |
| **总计** | **182** | **通过率 100% (182/182)** |

---

## 五、部署与归档自动化测试细分 (Deploy & Backup Test Breakdown)

执行命令：`python -m unittest discover -s deploy/tests -p "test_*.py" -v`

- **`test_backup.py`** (6 tests - R32):
  - TC-R32-01: 未注入 `DB_PASS` 时立即中止并拒绝生成备份
  - TC-R32-02: `DB_PASS` 为空字符串时强制拒绝
  - TC-R32-03: mysqldump 执行非零退出时清理临时文件并记录日志
  - TC-R32-04: mysqldump 产生 0 字节内容时识别异常并清理
  - TC-R32-05: 正常导出场景下生成非空 `.sql.gz`，命名符合规范
  - TC-R32-06: 历史轮转仅删除超过保留期（`RETENTION_DAYS`）的旧文件，安全保留新归档
- **`test_deploy_lifecycle.py`** (R29 四阶段部署与自动回滚)
- **`test_tencent_build.py`** (R28 产物完整性与原子切换)
- **`test_aliyun_build.py`** (构建产物与基线完整性)
- **`test_nginx_gateway.py`** (网关就绪探针与防穿透)

---

## 六、持续回归与缺陷防范规范 (Regression & Quality Control)

1. **防范二次引入（Defect Prevention）**：
   - 每次代码变更必须配套对应测试用例（如 R27 配套 `IdempotencyPurgeJobTest`，R32 配套 `test_backup.py`）。
   - 提交前必须在本地全量跑通 `mvn test`（496 项）与 `node --test`（182 项），0 容忍失败用例。
2. **规范化提交历史（Git Traceability）**：
   - 严格遵循四段式 commit 模板（摘要与背景、根因分析、技术解决方案、自动化测试结果），杜绝一句话提交。
3. **环境与数据审计**：
   - 生产种子隔离（R01）与系统账户安全（R30）由生产环境负责人在独立运维流程中核验，代码层保持零破坏与强前置约束。
