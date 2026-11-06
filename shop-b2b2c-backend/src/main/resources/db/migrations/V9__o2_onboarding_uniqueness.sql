-- 文件：V9__o2_onboarding_uniqueness.sql
-- 用途：O2 唯一索引与并发防重增量结构：入驻申请主体防重生成列、费率同店同生效时点唯一、默认收款账户生成列唯一。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. 全部变更幂等：列用 information_schema.columns、索引用 information_schema.statistics 判断后动态执行。
--  3. 禁止 DROP TABLE/TRUNCATE/DELETE/删库语句；本脚本不回填、不清理历史数据。
--  4. 执行前提（不满足时脚本会明确失败，不得强行跳过）：保留数据库中不得存在
--     a) 同一 subject_type + id_card_fingerprint 且状态为进行中的多张申请；
--     b) 同一 merchant_id 多条 is_default=1 的收款账户；
--     c) 同一 merchant_id + effective_from 的多条费率政策。
--     如有冲突须先人工归并数据再执行；开发重建库直接用 schema.sql 无需本前提。
--  5. 生成列依赖 MySQL 8 STORED 生成列语法；唯一冲突由应用翻译为 409，不向客户端泄漏 SQL。

-- 1. 入驻申请：进行中状态主体指纹生成列
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'active_fingerprint');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN active_fingerprint VARCHAR(128) GENERATED ALWAYS AS (IF(status IN (''DRAFT'',''SUBMITTED'',''REVIEWING'',''SUPPLEMENT_REQUIRED'',''APPROVED'',''OPENED''), id_card_fingerprint, NULL)) STORED COMMENT ''进行中申请的主体指纹（终态行为 NULL）；配合 uk_onboarding_active_subject 并发防重'' AFTER id_card_fingerprint',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 入驻申请：主体防重唯一索引（终态行生成列为 NULL，不参与唯一）
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND index_name = 'uk_onboarding_active_subject');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD UNIQUE KEY uk_onboarding_active_subject (subject_type, active_fingerprint)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 收款账户：默认账户占位生成列
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_settlement_account' AND column_name = 'default_merchant_id');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_settlement_account ADD COLUMN default_merchant_id BIGINT UNSIGNED GENERATED ALWAYS AS (IF(is_default = 1, merchant_id, NULL)) STORED COMMENT ''默认账户占位：仅默认账户非空，配合 uk_settlement_default 保证同店默认唯一'' AFTER is_default',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 收款账户：默认唯一索引（非默认账户生成列为 NULL，不参与唯一）
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'merchant_settlement_account' AND index_name = 'uk_settlement_default');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE merchant_settlement_account ADD UNIQUE KEY uk_settlement_default (default_merchant_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. 费率政策：同店同生效时点唯一索引
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'merchant_fee_policy' AND index_name = 'uk_fee_policy_merchant_effective');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE merchant_fee_policy ADD UNIQUE KEY uk_fee_policy_merchant_effective (merchant_id, effective_from)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
