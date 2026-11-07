<!--
文件：SPRINT_39_D31初始化与版本迁移复核.md
用途：D31「数据库初始化与版本迁移」复核记录：确认台账三项修复建议已由既有实现覆盖，无新增代码。
维护注意：
 1. 本轮为纯复核，不改动任何结构/配置/代码；所有结论均为"待人工验证"。
 2. 版本迁移唯一入口是 SchemaMigrationRunner（T12）+ db/migrations/V*.sql；schema.sql 只负责新库建结构，不得互相替代。
 3. H2 隔离测试只验证执行器不误跑 MySQL 脚本，真实 MySQL 8 逐版本演练必须由用户执行并记录。
-->
# Sprint 39：D31 初始化与版本迁移复核

- 日期：2026-11-07
- 主台账：D31
- 状态：已修复 · 待人工验证
- 执行方：代码代理复核与文档更新；本轮无代码改动，相关契约测试由代理按既有授权运行，结果仅作参考，验收由用户管理。

## 1. 台账问题与复核结论

台账 D31 原文：`schema.sql` 使用 CREATE TABLE IF NOT EXISTS + INSERT IGNORE；默认 init always 且 continue-on-error=true —— 旧表不会自动增加新字段，初始化错误可能被掩盖；不能将其视为结构自愈。修复建议：引入版本迁移和失败阻断；演示种子与生产结构分开。

台账 2026-11-03 基线整理段落留结论"不提供存量表版本升级，仍为部分修复"。复核发现该结论已过时：T12 `SchemaMigrationRunner` 于 2026-11-06 落地（提交 22d0bc4），V6 存量结构对齐脚本同日补齐（提交 724d1da），三项建议实际全部到位。

| 台账修复建议 | 现状 | 证据 |
| --- | --- | --- |
| 引入版本迁移 | 已落地 | `SchemaMigrationRunner` 按数字序执行 `classpath:db/migrations` 下 V1–V19 幂等增量 DDL，成功后记账 `schema_migration(version)`，已应用版本跳过；脚本用 information_schema 存在性判断保证重试幂等 |
| 失败阻断 | 已落地 | `spring.sql.init.continue-on-error: false`（[application.yml](../shop-b2b2c-backend/src/main/resources/application.yml)）；迁移任一脚本失败抛 IllegalStateException 阻断启动，禁止吞异常 |
| 演示种子与生产结构分开 | 已落地 | 演示账号/店铺/商品/卡密种子独立 `db/demo-seed.sql`，由 `APP_DEMO_SEED_LOCATIONS` 显式开关加载；`application-prod.yml` 设 `spring.sql.init.mode: never`，生产不执行任何 schema/种子 |

## 2. 版本迁移机制要点（既有实现，本轮未改）

- 执行器：`config/SchemaMigrationRunner.java`（ApplicationRunner，仅 MySQL 执行；H2 跳过并记日志）。
- 记账表：`schema_migration(version PK, applied_at)`，由执行器自建；部分失败不记账，修复脚本后可按版本重试。
- 排序：`compareVersions` 按 V 后数字段比较，避免字典序把 V10 排到 V2 之前。
- 脚本约束：禁止 DROP TABLE/TRUNCATE/DELETE/USE/DROP DATABASE，禁止回填历史业务数据（由 `SchemaMigrationContractTest` 静态断言锁死）。
- 新库与存量库分工：`schema.sql` 只在允许重建的开发库建全量结构；存量库升级只走 V 系列增量，两者不得互相替代（AGENTS.md 开发期数据库基线段）。

## 3. 测试（代理执行，仅作参考）

| 命令 | 结果 |
| --- | --- |
| `mvn -f shop-b2b2c-backend/pom.xml test -Dtest="SchemaMigrationContractTest,SchemaMySqlTest"` | 通过（exit 0） |

`SchemaMigrationContractTest` 已覆盖：V1–V6 幂等/非破坏性静态断言、配置接线（enabled/locations）、H2 不建 schema_migration 表、V6 对 20 张旧表的补列与 37 个外键对齐、版本号自然排序。本轮无新增测试需求（无行为变化）。

## 4. 未验证与边界

- 真实 MySQL 8 上 V1–V19 对存量库的逐版本演练未执行：H2 跳过逻辑只证明执行器不误跑，静态断言不证明 MySQL 可执行；用户须按 [2026-11-06-migration-baseline-audit.md](2026-11-06-migration-baseline-audit.md) 的演练步骤在独立库验证。
- `schema.sql` 不升级已有列的边界不变：保留数据的环境只能走 V 系列增量，新增列/索引必须同步产出下一个 V 脚本，不能只改 schema.sql。
- 生产首次启动的迁移窗口与备份要求属运维事项，不在本 Sprint 范围。
