-- 文件：V5__g34_payment_event_reconcile_retry.sql
-- 用途：G34 补齐陈旧 PROCESSING 支付事件的可重试补偿字段：重试次数、下次回查时间，并补扫描索引；人工队列使用 event_status='MANUAL_REVIEW'。
-- 维护注意：
--  1. 仅用于保留数据的存量库；新库结构以 db/schema.sql 为唯一入口，两者不得互相替代。
--  2. 全部变更幂等：用 information_schema 判断列/索引是否存在，失败重试不得留下半套结构。
--  3. 禁止 DROP TABLE、TRUNCATE、DELETE、USE 或删库语句；不回填历史事件，历史 PROCESSING 行由补偿扫描按默认 0 次重试继续收敛。
--  4. next_reconcile_at 为 NULL 表示立即可扫；超过 payment.reconcile.max-attempts 后由应用写 MANUAL_REVIEW，不在本脚本批量改状态。

-- 1. 重试次数
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'payment_event' AND column_name = 'reconcile_attempts');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE payment_event ADD COLUMN reconcile_attempts INT NOT NULL DEFAULT 0 COMMENT ''G34：陈旧 PROCESSING 渠道回查已重试次数'' AFTER raw_payload',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 下次允许回查时间（指数退避）
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'payment_event' AND column_name = 'next_reconcile_at');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE payment_event ADD COLUMN next_reconcile_at DATETIME NULL COMMENT ''G34：下次允许回查时间，NULL 表示立即可扫'' AFTER reconcile_attempts',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 补偿扫描索引：按状态与下次回查时间筛选
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'payment_event' AND index_name = 'idx_event_reconcile');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE payment_event ADD INDEX idx_event_reconcile (event_status, next_reconcile_at)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
