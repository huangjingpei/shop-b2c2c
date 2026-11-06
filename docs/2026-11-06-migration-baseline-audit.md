# 迁移与基线审计（2026-11-06）

<!--
文件：2026-11-06-migration-baseline-audit.md
用途：记录第 ① 卡“迁移与基线审计”的逐条结论、`schema.sql` 与 V 系列迁移的等价性差异清单，以及可在真实 MySQL 执行的演练步骤。
维护注意：
 1. 本文件只记录静态核对与修复结果；未执行 mvn/编译/测试/构建，未连接任何数据库。所有 MySQL 结论均为“已实现·待人工验证”。
 2. 迁移脚本的权威入口仍是 `db/schema.sql`（新库）与 `db/migrations/V*.sql`（保留数据的存量库）；两者都不允许删库、删表、静默丢数据。
 3. 任何新增表/列/索引/外键，必须同时判断是否属于“旧表增量”，属于则补进新的 V 脚本；本文件 §3 的差异清单要同步更新。
 4. 演练步骤只用于隔离/预发实例，禁止在未备份的生产库直接执行；执行结果由用户回填，代理不得自标“已验证”。
-->

## 0. 审计范围与前提

- 审计对象：`shop-b2b2c-backend/src/main/resources/db/schema.sql`、`db/migrations/V1__s1_order_lifecycle.sql` 至 `V6__s1_legacy_structure_reconcile.sql`、`SchemaMigrationRunner`、`SchemaMigrationContractTest`、`SchemaMySqlTest`。
- 存量基线定义：V1 引入前的结构（提交 `22d0bc4` 的父提交）。V6 只覆盖“这批旧表”到当前基线的增量。
- 两条路径：
  - **路径 A 新库**：`mysql < db/schema.sql`，由 `schema.sql` 一次写全 38 张表。
  - **路径 B 存量库**：先导入 `db/schema.sql`（部署脚本 `--migrate`，`CREATE TABLE IF NOT EXISTS` 只补新表），再启动应用由 `SchemaMigrationRunner` 执行 V1–V6 补旧表增量。
- 明确不支持的路径：**只跑 V 系列、不导入 `schema.sql`**。V 系列不创建 T33/T39/T40 等 19 张新表；缺少基线表时会因 `ALTER TABLE` 报“表不存在”而阻断启动，属明确失败，不会静默半迁移。

## 1. V1–V6 逐条审计

结论均为静态核对 + 文本断言，**未在 MySQL 执行**。

| V 编号 | 检查项 | 结论 | 修复或缺口 |
| --- | --- | --- | --- |
| V1 | 幂等（连跑两次） | 原有第 5 段“存在即 DROP、随后无条件 ADD”会在**每次执行**都删并重建 `idx_reservation_expiry`，虽然不报错、不重复建，但不是无副作用。已改为：先比对现有索引列 `status,expires_at,order_id`，仅在不符时重建；重建时先在 `idx_reservation_expiry_v2` 下建好同列索引，再删旧索引、再 `RENAME` 回基线名。重复执行在结构已符合时全部走 `SELECT 1`。 | 已修（V1 第 5 段） |
| V1 | 执行顺序依赖 | 依赖 `trade_order`、`inventory_reservation` 已由 `schema.sql` 创建；列 `attempt_key`、`reissue_count` 用 `AFTER owner_token`/`AFTER` 定位，要求前置列存在（基线均有）。 | 无缺口；已在 §0 写明前置条件 |
| V1 | 失败后可恢复 | 列/索引均为独立语句，MySQL 8 DDL 原子；任一步失败不记账，重跑时按存在性判断续做。索引换名方案保证“删除旧索引”之前新索引已可用，不会出现无索引窗口。 | 已修 |
| V1 | `schema_migration` 记账 | 脚本全部成功后才插入版本行；中途失败不写版本，不会出现“记账了但没做完”。 | 无缺口 |
| V1 | 是否引用未建表/列 | 引用的 `trade_order`、`inventory_reservation` 及 `owner_token`、`lock_expire_time` 等均属基线，存在。 | 无缺口 |
| V2 | 幂等 | 单列 `fingerprint_unavailable` 按 `information_schema.columns` 判断后追加，重复执行无操作。 | 无缺口 |
| V2 | 顺序依赖 | `AFTER card_fingerprint` 要求基线已有该列；V2 必须在 `schema.sql` 建表之后执行。 | 无缺口 |
| V2 | 失败后可恢复 | 单条 `ALTER`，失败不记账，重跑安全。 | 无缺口 |
| V2 | 记账 | 成功后记账，失败不记。 | 无缺口 |
| V2 | 未建表/列 | 仅引用 `product_virtual_card` 及其基线列。 | 无缺口 |
| V3 | 幂等 | `CREATE TABLE IF NOT EXISTS`；已建表再跑不重复建。 | 无缺口 |
| V3 | 顺序依赖 | 无前置 V 版本；依赖 `merchant_store`、`product_sku`、`sys_user` 已存在（`CREATE TABLE` 时未声明外键，故建表不依赖它们）。 | 见下一行缺口 |
| V3 | 失败后可恢复 | 单表创建，失败不记账。 | 无缺口 |
| V3 | 记账 | 成功后记账，失败不记。 | 无缺口 |
| V3 | 未建表/列 / 结构等价 | **建表时未声明 `schema.sql` 中的 3 个外键**（`fk_audit_merchant`、`fk_audit_sku`、`fk_audit_actor`）。先跑 V3 的库存量与新建库不等价。 | 未改 V3（已发布脚本不重放）；由 **V6 §三** 幂等补齐 |
| V4 | 幂等 | 新索引不存在才建、旧索引存在才删，重复执行无操作。 | 无缺口 |
| V4 | 执行顺序依赖 | 依赖 `product_sku.merchant_id`、`sku_code` 基线列；必须先有 `merchant_id` 才能建店内唯一键。 | 无缺口 |
| V4 | 失败后可恢复 | **原顺序是“先删旧全局唯一键，再建店内唯一键”**：若旧库存在同店重复 `sku_code`，新索引创建失败会留下“完全没有唯一键”的中间态。已改为**先建新索引、后删旧索引**，新键建立失败时旧唯一性保护仍在。 | 已修（V4 §1/§2 顺序） |
| V4 | 记账 | 成功后记账，失败不记；失败后重跑仍在同一位置重试。 | 无缺口 |
| V4 | 未建表/列 | 仅引用 `product_sku` 基线列。 | 无缺口 |
| V5 | 幂等 | 两个新列与 `idx_event_reconcile` 均按 `information_schema` 判断后追加，重复执行无操作。 | 无缺口 |
| V5 | 顺序依赖 | `AFTER raw_payload`、`AFTER reconcile_attempts` 要求前序列存在；V5 排在 V4 之后。 | 无缺口 |
| V5 | 失败后可恢复 | 每条独立 DDL，失败不记账，重跑续做。 | 无缺口 |
| V5 | 记账 | 成功后记账，失败不记。 | 无缺口 |
| V5 | 未建表/列 | 仅引用 `payment_event` 基线列。 | 无缺口 |
| V6 | 幂等 | 32 个补列、2 个列属性调整、37 个外键全部按 `information_schema.columns` / `table_constraints` 判断；结构已符合时全部 `SELECT 1`。在新库上连续执行两次必须零变更。 | 新增脚本，待人工执行 |
| V6 | 顺序依赖 | 必须在 V3 之后（`card_access_audit` 需已建表）、V4 之后（`product_sku` 店内唯一键可作为 `merchant_id` 外键索引）、V5 之后（不冲突）。版本号按数字排序，V6 排在 V5 之后。 | 已实现：`SchemaMigrationRunner` 由字典序改为数字序 |
| V6 | 失败后可恢复 | 每条 DDL 独立且原子；失败不记账。补列中途失败重跑只补剩余列；外键逐条补，已建的外键不会重复建。 | 无缺口 |
| V6 | 记账 | 全部语句成功后才记一行版本。 | 无缺口 |
| V6 | 未建表/列 | 只引用旧表；不创建 19 张新表（见 §0 路径定义）。`payment_event.merchant_id` 改符号位后立即建外键，顺序在脚本内已保证。 | 已实现 |
| 执行器 | 版本排序 | 原 `Comparator.comparing(version)` 为字典序，`V10` 会排在 `V2` 之前；全量新迁移时可能破坏 V 之间的依赖。已改为按 `V` 后数字段排序，数字相同再按完整版本名，非数字版本排最后。 | 已修，并有 `compareVersions` 单测 |
| 执行器 | 记账并发 | 单实例启动假设。两个实例同时启动会对 `schema_migration` 主键冲突并阻断启动（快速失败，不会静默双跑）。 | 登记 G40，不在本卡改多实例锁 |
| 执行器 | 执行时机 | `ApplicationRunner` 在 Web 容器已监听后执行，迁移期间存在“端口已开、结构未完成”的窗口。 | 登记 G38，需运维用就绪探针门禁 |
| 执行器 | 空库/缺基线表 | 只跑 V 系列会因缺表在第一条 `ALTER` 报错并阻断启动，错误信息含表名。 | 见 §0；登记 G39 |

## 2. `schema.sql` ↔ V 系列等价性核对

基线对比方式：取 V1 引入前（`22d0bc4^`）的 `schema.sql`，与当前 `schema.sql` 逐表比对列、索引、约束；再核对 V1–V6 是否覆盖全部差异。

### 2.1 已对齐项（无需修改）

| 对象 | 基线状态 | 当前 `schema.sql` | 覆盖脚本 | 结论 |
| --- | --- | --- | --- | --- |
| `trade_order` 三个索引 | 已有 `idx_order_created`、`idx_order_expiry` | 同 | V1（幂等） | 等价 |
| `inventory_reservation.attempt_key` | 无 | 有（可空） | V1 | 等价 |
| `trade_order.reissue_count` | 无 | 有（默认 0） | V1 | 等价 |
| `inventory_reservation` 到期索引 | `(status, expires_at)` | `(status, expires_at, order_id)` | V1（已修） | 等价 |
| `product_virtual_card.fingerprint_unavailable` | 无 | 有（默认 0） | V2 | 等价 |
| `product_sku` 唯一键 | 全局 `uk_sku_code` | 店内 `uk_merchant_sku_code` | V4（已修顺序） | 等价 |
| `payment_event.reconcile_attempts` / `next_reconcile_at` | 无 | 有 | V5 | 等价 |
| `payment_event.idx_event_reconcile` | 无 | `(event_status, next_reconcile_at)` | V5 | 等价 |
| 旧表 CHECK 约束 | 基线已存在 | 同名同定义 | 无变更 | 等价（未改动） |

### 2.2 修前差异清单（已由 V6 补齐）

| 对象 | 修前差异 | 影响 | 处理 |
| --- | --- | --- | --- |
| `merchant_store` | 缺 `pay_channel` | T32 起下单/支付读写该列会报未知列 | V6 补列 |
| `merchant_payment_account` | 缺 `settlement_account_id`、`binding_status`、`channel_merchant_id`、`bound_at`、`effective_from`、`effective_to`、`raw_result`（7 列） | T39 账户绑定/阶段二预留字段缺失 | V6 补列 |
| `trade_order` | 缺 21 列：`refunded_amount`、`fee_refunded_amount`、`fee_status`、`goods_amount`、`merchant_discount_amount`、`platform_discount_amount`、`shipping_amount`、`order_tax_amount`、`fee_policy_id`、`fee_rate_bps`、`fee_rate_type`、`fee_base_amount`、`fee_standard_amount`、`fee_amount`、`fee_waived_amount`、`fee_net_amount`、`fee_tax_amount`、`fee_tax_rate`、`collection_mode`、`pay_channel`、`platform_trade_no` | 退款、服务费、结算、平台代收全链路查询失败（**财务相关，最高风险**） | V6 逐列补齐 |
| `trade_order.payment_channel` | 可空、默认 `'WECHAT'` | 与基线 `NOT NULL` 不一致，存在 NULL 时下游统计/对账分桶缺失 | V6 `MODIFY ... NOT NULL` |
| `payment_event` | 缺 `collection_mode`、`channel_merchant_id`、`platform_trade_no` | T32 渠道对账无法区分内部商户与渠道商户 | V6 补列 |
| `payment_event.merchant_id` | `BIGINT`（有符号） | 与 `merchant_store.id`（`BIGINT UNSIGNED`）符号位不一致，**外键无法建立** | V6 `MODIFY ... UNSIGNED` |
| 旧表外键 | 缺 37 个（`billing_statement`、`complaint`、`card_import_batch`、`inventory_adjustment`、`inventory_reservation`、`merchant_payment_account`、`payment_event`、`product`、`product_sku`、`product_virtual_card`、`sys_user`、`trade_order`、`trade_order_item`、`virtual_card_void`、`card_access_audit`） | 存量库缺少引用完整性，脏数据可写入（财务/卡密尤其危险） | V6 逐条补齐，名称与 `schema.sql` 一致 |
| 19 张新表 | 基线不存在（`trade_refund`、`merchant_account`、`merchant_account_ledger` 等） | 由 `schema.sql` 导入创建 | 不在 V6 重复建表，见 §0 路径 B 前置 |

### 2.3 已知且接受的差异

| 差异 | 原因 | 是否影响等价性 |
| --- | --- | --- |
| V6 新增列追加在表尾，`ordinal_position` 与 `schema.sql` 不同 | 追加位置可用 MySQL 8 `INSTANT` 加列，避免逐列 `AFTER` 触发表重建 | 不影响按列名读写的应用；结构比对必须按列名而非列序 |
| 部分列 `COMMENT` 文本不同（如 `merchant_store.commission_rate`、`payment_event.event_status`、`merchant_payment_account.apply_status`） | 基线后仅注释措辞变化，未改类型/默认值 | 不影响执行与数据语义 |
| 存量库多一张 `schema_migration` | 迁移器记账表，`schema.sql` 不创建 | 结构比对时排除 |

## 3. MySQL 8 兼容性硬检查（静态）

| 检查项 | 结论 | 依据/备注 |
| --- | --- | --- |
| 外键两端类型/符号 | **0 处不一致**。所有外键子列与父列均为 `BIGINT UNSIGNED`（`payment_event.merchant_id` 的符号位差异由 V6 修正）。 | 对 38 张表、66 个外键做脚本比对 |
| 外键两端字符集/排序规则 | 所有表统一 `DEFAULT CHARSET=utf8mb4` 且不写 `COLLATE`，同一 MySQL 8 实例下均为该字符集默认排序规则（`utf8mb4_0900_ai_ci`）。 | **风险**：由 MySQL 5.7 延续的旧库可能保留 `utf8mb4_general_ci`，新表与旧表排序规则不同会导致建外键失败。见 §5 前置检查与 G41 |
| 唯一键/索引长度 | 无超限。最宽为 `reconciliation_difference.uk_difference_key`（VARCHAR(191)×4=764 字节）与 `payment_event.uk_channel_trade_no`（约 640 字节），均低于 InnoDB `DYNAMIC` 行格式 3072 字节上限。 | 逐键按 utf8mb4 4 字节/字符保守估算 |
| `information_schema` 判断可靠性 | `table_schema = DATABASE()` 依赖连接 URL 带库名（应用配置均带库名），MySQL 8 下可靠。`statistics` 按索引列多行返回，故用 `COUNT(*) > 0` 判存在、`GROUP_CONCAT` 判定义。 | 不使用 `information_schema` 的已废弃列；未依赖 `SHOW` 输出格式 |
| `CHECK` 约束是否真正执行 | MySQL **8.0.16 之前只解析不执行**；8.0.16+ 强制执行。项目要求 MySQL ≥ 8.0.16。 | 服务器版本低于 8.0.16 时，`chk_*` 不生效，金额/库存非法值可能写入。见 §5 前置检查 |
| 金额列精度 | 全部金额列 `DECIMAL` 且小数位统一为 2；订单/退款级为 `(10,2)`，账本/结算/分账级为 `(12,2)`。`(12,2)` 是 `(10,2)` 的扩宽，汇总写入不会截断；未发现 `FLOAT/DOUBLE` 金额列。 | 费率/税率为 `DECIMAL(5,2)/(7,4)`，非金额。无需修改 |
| 迁移语句的事务语义 | MySQL DDL 隐式提交，迁移不能依赖单事务回滚；因此每条 DDL 必须自身幂等，失败后重跑可续做。V1–V6 均按此设计。 | 与 `SchemaMigrationRunner`“成功后才记账”配合 |

## 4. 安全性核对

| 检查项 | 结论 |
| --- | --- |
| 删库/删表/清空 | V1–V6 均无 `DROP TABLE`、`TRUNCATE`、`DELETE`、`USE`、`DROP DATABASE`；V4 仅 `DROP INDEX`（索引，非数据），且有文本用例断言。 |
| 静默丢数据 | 无 `UPDATE` 回填，无改写历史金额/订单/事件。V6 只改结构。 |
| 无法自动迁移时的失败语义 | 三类情况显式失败并阻断启动：① 缺基线表 → `ALTER TABLE` 报“表不存在”；② 旧数据有孤儿行 → 加外键失败（错误含约束名/表名）；③ `trade_order.payment_channel` 存在 NULL → `MODIFY NOT NULL` 失败。失败即不记账，重跑可续做。 |
| 财务表审计字段（操作人/时间/前后值） | 逐表核对：`trade_refund`（`requested_by`、`created_at/updated_at`）；`merchant_account_ledger`（`balance_after/arrears_after`、`created_at`，出入账通过 `biz_type + biz_id` 关联订单/退款/充值单，操作人由对应单据的 `confirmed_by/requested_by` 追溯）；`merchant_recharge_order`（`requested_by`、`confirmed_by`、`confirmed_at`、`approval_mode`）；`merchant_settlement_batch`（`prepared_by`、`confirmed_by`、`confirmed_at`、`approval_mode`）；`merchant_fee_policy` / `merchant_fee_audit`（`operator_id`、`confirmed_by`、`from/to` 前后值）；`merchant_collection_status_audit`（`operator_id`、`arrears_at_action`）；`payment_channel_statement`（`imported_by`）；`reconciliation_difference`（`handled_by/handled_at/handle_note`、预期/实际值）；`split_reverse`（`operator_id`）；`inventory_adjustment` / `virtual_card_void` / `card_import_batch` / `card_access_audit` 均有操作人与前后值。**结论：无需补列。** |
| 审计字段的唯一例外 | `merchant_account_ledger` 没有表内 `operator_id`，且 `entry_type` 声明了 `ADJUSTMENT` 但代码尚无人工调整入口。当前全部入账由业务单据驱动，可由单据追溯；若后续开放人工调整，必须同时补 `operator_id` 与调整原因字段。登记 G42（未实现功能，不在本卡补空列）。 |

## 5. 真实 MySQL 演练步骤（由用户执行）

前置条件：

- MySQL ≥ **8.0.16**（`CHECK` 强制生效的最低版本）。
- 在隔离/预发实例执行，禁止直接对生产库操作；全程使用带 `--defaults-extra-file` 的凭据文件，避免口令进入 shell 历史。
- 准备两份数据库名：`shop_rehearsal_fresh`（路径 A）、`shop_rehearsal_legacy`（路径 B，从备份恢复的真实存量结构）。

### 5.1 备份与前置检查

```bash
# 1) 备份存量库（示例库名 shop_b2b2c，凭据文件权限 600）
mysqldump --defaults-extra-file=/tmp/mysql-backup.cnf --single-transaction --routines \
  --triggers shop_b2b2c | gzip > /tmp/shop_b2b2c_$(date +%F_%H%M).sql.gz
gzip -t /tmp/shop_b2b2c_*.sql.gz && echo BACKUP_OK
```

预期：打印 `BACKUP_OK`；备份文件非空（`ls -lh`）。

```bash
# 2) 版本与默认排序规则检查（两个库都要看）
mysql --defaults-extra-file=/tmp/mysql-backup.cnf -e "
  SELECT VERSION() AS mysql_version;
  SELECT @@character_set_database, @@collation_database;
  SELECT table_name, table_collation FROM information_schema.tables
   WHERE table_schema='shop_b2b2c' AND table_type='BASE TABLE'
     AND table_collation <> 'utf8mb4_0900_ai_ci';"
```

预期：`mysql_version` ≥ 8.0.16；第二条查询**无结果行**。若有结果，说明旧表排序规则与新表不一致，加外键会失败，需按 G41 先重建该库（开发期允许重建）。

```bash
# 3) 前置数据完整性检查（避免加外键时才发现孤儿行）
mysql --defaults-extra-file=/tmp/mysql-backup.cnf shop_b2b2c -e "
  SELECT COUNT(*) FROM trade_order o LEFT JOIN merchant_store m ON o.merchant_id=m.id WHERE m.id IS NULL;
  SELECT COUNT(*) FROM trade_order WHERE payment_channel IS NULL;
  SELECT COUNT(*) FROM payment_event e LEFT JOIN merchant_store m ON e.merchant_id=m.id WHERE m.id IS NULL;"
```

预期：三条均为 `0`。非 0 时先人工核对并修正，**不得**靠删除行“凑”过迁移。

### 5.2 路径 A：新库（`schema.sql`）

```bash
mysql --defaults-extra-file=/tmp/mysql-backup.cnf -e "CREATE DATABASE shop_rehearsal_fresh CHARACTER SET utf8mb4;"
mysql --defaults-extra-file=/tmp/mysql-backup.cnf shop_rehearsal_fresh < shop-b2b2c-backend/src/main/resources/db/schema.sql
mysql --defaults-extra-file=/tmp/mysql-backup.cnf shop_rehearsal_fresh -e \
  "SELECT COUNT(*) AS tables FROM information_schema.tables WHERE table_schema='shop_rehearsal_fresh' AND table_type='BASE TABLE';
   SELECT COUNT(*) AS fks FROM information_schema.table_constraints WHERE constraint_schema='shop_rehearsal_fresh' AND constraint_type='FOREIGN KEY';"
```

预期：`tables = 38`，`fks = 66`。

### 5.3 路径 B：存量库（备份恢复 → 跑两遍 V1–V6）

```bash
mysql --defaults-extra-file=/tmp/mysql-backup.cnf -e "CREATE DATABASE shop_rehearsal_legacy CHARACTER SET utf8mb4;"
gunzip -c /tmp/shop_b2b2c_*.sql.gz | mysql --defaults-extra-file=/tmp/mysql-backup.cnf shop_rehearsal_legacy
```

预期：无报错；`schema_migration` 不存在或行数不变（取决于备份来源）。

```bash
# 第一遍：用应用启动触发迁移（只指向演练库，务必确认 URL 中的库名）
SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/shop_rehearsal_legacy?useSSL=false&serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8' \
APP_SCHEMA_MIGRATION_ENABLED=true \
java -jar shop-b2b2c-backend/target/shop-b2b2c-backend.jar
# 应用完成启动后停止进程（Ctrl+C 或 systemctl stop）

# 第二遍：原样再启动一次，再停止
```

预期：第一遍日志出现 V1–V6 执行记录；第二遍每个版本都输出“已应用版本，跳过”，无任何 `ALTER TABLE` 执行。两次均正常启动。

```bash
mysql --defaults-extra-file=/tmp/mysql-backup.cnf shop_rehearsal_legacy -e "
  SELECT version, COUNT(*) AS times FROM schema_migration GROUP BY version ORDER BY version;"
```

预期：6 行，版本 `V1_...` 到 `V6_...` 各 `times = 1`。

### 5.4 结构等价校验

```bash
# 列：名称/类型/可空/默认值（不比较列序与注释）
mysql --defaults-extra-file=/tmp/mysql-backup.cnf -N -e "
  SELECT table_name,column_name,column_type,is_nullable,IFNULL(column_default,'<NULL>')
    FROM information_schema.columns
   WHERE table_schema='shop_rehearsal_fresh' AND table_name<>'schema_migration'
   ORDER BY table_name,column_name" > /tmp/fresh_columns.txt
mysql --defaults-extra-file=/tmp/mysql-backup.cnf -N -e "
  SELECT table_name,column_name,column_type,is_nullable,IFNULL(column_default,'<NULL>')
    FROM information_schema.columns
   WHERE table_schema='shop_rehearsal_legacy' AND table_name<>'schema_migration'
   ORDER BY table_name,column_name" > /tmp/legacy_columns.txt
diff /tmp/fresh_columns.txt /tmp/legacy_columns.txt && echo COLUMNS_EQUAL

# 索引
mysql --defaults-extra-file=/tmp/mysql-backup.cnf -N -e "
  SELECT table_name,index_name,seq_in_index,column_name,non_unique
    FROM information_schema.statistics
   WHERE table_schema='shop_rehearsal_fresh' AND table_name<>'schema_migration'
   ORDER BY table_name,index_name,seq_in_index" > /tmp/fresh_indexes.txt
mysql --defaults-extra-file=/tmp/mysql-backup.cnf -N -e "
  SELECT table_name,index_name,seq_in_index,column_name,non_unique
    FROM information_schema.statistics
   WHERE table_schema='shop_rehearsal_legacy' AND table_name<>'schema_migration'
   ORDER BY table_name,index_name,seq_in_index" > /tmp/legacy_indexes.txt
diff /tmp/fresh_indexes.txt /tmp/legacy_indexes.txt && echo INDEXES_EQUAL

# 外键（含父表与父列）
mysql --defaults-extra-file=/tmp/mysql-backup.cnf -N -e "
  SELECT k.table_name,k.constraint_name,k.column_name,
         u.referenced_table_name,u.referenced_column_name
    FROM information_schema.key_column_usage k
    JOIN information_schema.referential_constraints r
      ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name
    JOIN information_schema.key_column_usage u
      ON u.constraint_schema=r.unique_constraint_schema AND u.constraint_name=r.unique_constraint_name
     AND u.ordinal_position=k.position_in_unique_constraint
   WHERE k.table_schema='shop_rehearsal_fresh' AND k.referenced_table_name IS NOT NULL
   ORDER BY k.table_name,k.constraint_name" > /tmp/fresh_fks.txt
mysql --defaults-extra-file=/tmp/mysql-backup.cnf -N -e "
  SELECT k.table_name,k.constraint_name,k.column_name,
         u.referenced_table_name,u.referenced_column_name
    FROM information_schema.key_column_usage k
    JOIN information_schema.referential_constraints r
      ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name
    JOIN information_schema.key_column_usage u
      ON u.constraint_schema=r.unique_constraint_schema AND u.constraint_name=r.unique_constraint_name
     AND u.ordinal_position=k.position_in_unique_constraint
   WHERE k.table_schema='shop_rehearsal_legacy' AND k.referenced_table_name IS NOT NULL
   ORDER BY k.table_name,k.constraint_name" > /tmp/legacy_fks.txt
diff /tmp/fresh_fks.txt /tmp/legacy_fks.txt && echo FKS_EQUAL
```

预期：依次打印 `COLUMNS_EQUAL`、`INDEXES_EQUAL`、`FKS_EQUAL`，`diff` 无输出。若有差异，把差异文件保留下来作为缺口证据，**不要**手工改库“对齐”而不修脚本。

补充（可选）：`SELECT COUNT(*) FROM shop_rehearsal_legacy.schema_migration;` 应为 `6`；表总数应为 `39`（38 + 记账表）。

### 5.5 从备份恢复

```bash
# 在演练库上验证恢复：先删演练库（仅演练库），再从备份重建
mysql --defaults-extra-file=/tmp/mysql-backup.cnf -e "DROP DATABASE shop_rehearsal_legacy;"
mysql --defaults-extra-file=/tmp/mysql-backup.cnf -e "CREATE DATABASE shop_rehearsal_legacy CHARACTER SET utf8mb4;"
gunzip -c /tmp/shop_b2b2c_*.sql.gz | mysql --defaults-extra-file=/tmp/mysql-backup.cnf shop_rehearsal_legacy
mysql --defaults-extra-file=/tmp/mysql-backup.cnf shop_rehearsal_legacy -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='shop_rehearsal_legacy' AND table_type='BASE TABLE';
   SELECT COUNT(*) FROM trade_order;"
```

预期：恢复过程无报错；表数与 `trade_order` 行数与备份前一致（`V6` 不得增加业务数据）。恢复后如仍要复用演练库，需再次启动应用让迁移器补记版本。

> 说明：`DROP DATABASE` 仅出现在演练文档，用于重建**演练**库；`db/schema.sql` 与所有 V 脚本中均不得出现该语句。

## 6. 未验证项与登记缺口

| 编号 | 内容 | 原因/归属 |
| --- | --- | --- |
| G38 | `SchemaMigrationRunner` 在 `ApplicationRunner` 阶段执行，此时 Web 端口已监听，迁移期间可能接到请求 | 改成更早的生命周期会与 `spring.sql.init` 抢执行顺序；需运维用 `/readyz` 门禁或单实例停机发布，登记待决策 |
| G39 | V 系列不创建 19 张新表，存量库必须先导入 `db/schema.sql`（部署 `--migrate`） | 若把 19 张表复制进 V 脚本会产生两份权威结构，长期更易漂移；维持“`schema.sql` 建新表 + V 补旧表增量”的单一来源，已在 §0 写明前置 |
| G40 | 两个应用实例同时启动时，`schema_migration` 主键冲突会阻断后启动的实例 | 单实例部署下不发生；多实例需迁移锁或先发布后扩副本，登记待决策 |
| G41 | MySQL 5.7 延续来的旧库若排序规则为 `utf8mb4_general_ci`，新表/旧表外键会因排序规则不同建立失败 | 开发期允许重建库；§5.1 已加入前置检查。生产前必须确认服务器默认排序规则 |
| G42 | `merchant_account_ledger` 无表内 `operator_id`，`ADJUSTMENT` 入账类型尚无代码入口 | 当前入账均来自业务单据可追溯；开放人工调整时再补列与原因字段，不在本卡补空列 |

未验证（必须由用户执行后回填）：

- V1–V6 在真实 MySQL 8.0.x 的语法与执行结果（含 `GROUP_CONCAT`/`RENAME INDEX`/`MODIFY ... UNSIGNED`）。
- 两个路径的结构等价 `diff`（§5.4）。
- `SchemaMySqlTest` 新增的“连续执行 V1–V6 两次”段落（需 `-Dschema.mysql.bin`）。
- `SchemaMigrationContractTest` 的 V6 覆盖率断言（需运行后端单测）。
- 备份/恢复耗时、备份文件大小、磁盘空间与保留策略。

## 7. 本轮改动清单

> V1/V4 采用原地修正：已应用旧版 V1/V4 的库在旧脚本成功时已达到与新脚本相同的终态，新增语句本身幂等，重放无副作用；V3 不改动，因为它缺的是新增语句，已记账的库不会重放，统一由 V6 幂等补齐。

| 文件 | 改动 |
| --- | --- |
| `db/migrations/V6__s1_legacy_structure_reconcile.sql` | 新增：32 列补齐、2 列属性对齐、37 个外键补齐，全幂等、无破坏性语句 |
| `db/migrations/V1__s1_order_lifecycle.sql` | 索引重建改为“定义不符才重建、先建后换、再改名” |
| `db/migrations/V4__s2_merchant_sku_code_unique.sql` | 改为“先建店内唯一键、再删全局唯一键” |
| `config/SchemaMigrationRunner.java` | 版本号由字典序改为数字序，新增 `compareVersions` |
| `test/.../SchemaMigrationContractTest.java` | 追加 V6 覆盖率、幂等/非破坏、索引换名与数字排序断言 |
| `test/.../SchemaMySqlTest.java` | 表数 38、外键 66；新增同一库连续执行 V1–V6 两次与记账唯一断言 |
| `docs/README.md` | 导航补本文件 |
| `docs/2026-11-06-dev-gap-log.md` | 登记 G38–G42 |
</content>
