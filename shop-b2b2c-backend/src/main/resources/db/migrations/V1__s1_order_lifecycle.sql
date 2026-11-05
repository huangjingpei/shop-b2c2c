-- 文件：V1__s1_order_lifecycle.sql
-- 用途：T08/T10/T11/T12 存量库升级：补订单支付截止时间、预占所有权字段、分页与到期扫描索引。
-- 维护注意：
--  1. 本脚本只用于保留数据的存量库；新库结构仍以 db/schema.sql 为唯一入口。
--  2. MySQL DDL 隐式提交，全部变更必须幂等；用 information_schema 判断索引/列是否存在，失败重试不得留下半套结构。
--  3. 禁止加入 DROP TABLE、TRUNCATE、DELETE、USE 或删库语句；索引重建仅限索引本身。
--  4. lock_expire_time 回填仅处理 UNPAID 且为 NULL 的存量单，统一按 created_at + 15 分钟计算 (Q15)。

-- 1. 存量 UNPAID 单回填 15 分钟支付截止时间
UPDATE trade_order
SET lock_expire_time = DATE_ADD(created_at, INTERVAL 15 MINUTE)
WHERE order_status = 'UNPAID' AND lock_expire_time IS NULL;

-- 2. 订单分页 created_at + id 稳定排序索引
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND index_name = 'idx_order_created');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE trade_order ADD INDEX idx_order_created (created_at, id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 商户维度订单分页 created_at + id 稳定排序索引
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND index_name = 'idx_merchant_created');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE trade_order ADD INDEX idx_merchant_created (merchant_id, created_at, id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 超时未支付订单扫描索引：订单状态 + 数据库截止时间
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND index_name = 'idx_order_expiry');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE trade_order ADD INDEX idx_order_expiry (order_status, lock_expire_time, id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. 预占所有权与幂等尝试键分离字段 (T10)
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'inventory_reservation' AND column_name = 'attempt_key');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE inventory_reservation ADD COLUMN attempt_key VARCHAR(64) NULL AFTER owner_token',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'inventory_reservation' AND column_name = 'reissue_count');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE inventory_reservation ADD COLUMN reissue_count INT NOT NULL DEFAULT 0 AFTER attempt_key',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6. 预占到期游标索引补齐 order_id；索引可重建，不删除任何业务数据
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'inventory_reservation' AND index_name = 'idx_reservation_expiry');
SET @ddl := IF(@idx_exists = 0,
    'SELECT 1',
    'ALTER TABLE inventory_reservation DROP INDEX idx_reservation_expiry');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE inventory_reservation ADD INDEX idx_reservation_expiry (status, expires_at, order_id);
