-- 文件：V1__s1_order_lifecycle.sql
-- 用途：T12 版本化增量结构补齐（不含历史数据回填）：订单分页/到期扫描索引、预占所有权与重发字段、预占到期游标索引。
-- 维护注意：
--  1. 本执行器只用于保留结构的开发库；新库结构以 db/schema.sql 为唯一入口，两者不得互相替代。
--  2. MySQL DDL 隐式提交，全部变更必须幂等；用 information_schema 判断索引/列是否存在，失败重试不得留下半套结构。
--  3. 禁止 DROP TABLE、TRUNCATE、DELETE、USE 或删库语句；索引重建仅限索引本身。
--  4. 本脚本不回填订单/卡密等历史业务数据；支付截止由订单创建路径写入，已存在数据不做补算。

-- 1. 订单分页 created_at + id 稳定排序索引
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND index_name = 'idx_order_created');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE trade_order ADD INDEX idx_order_created (created_at, id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 商户维度订单分页 created_at + id 稳定排序索引
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND index_name = 'idx_merchant_created');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE trade_order ADD INDEX idx_merchant_created (merchant_id, created_at, id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 超时未支付订单扫描索引：订单状态 + 数据库截止时间
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND index_name = 'idx_order_expiry');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE trade_order ADD INDEX idx_order_expiry (order_status, lock_expire_time, id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 预占所有权与幂等尝试键分离字段 (T10)
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

-- 5. 预占到期游标索引补齐 order_id；只在定义不符时重建，先建后换，失败不留“无索引”中间态
SET @idx_cols := (SELECT LOWER(GROUP_CONCAT(column_name ORDER BY seq_in_index))
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'inventory_reservation' AND index_name = 'idx_reservation_expiry');
SET @swap_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'inventory_reservation' AND index_name = 'idx_reservation_expiry_v2');
SET @need_swap := IF(@idx_cols IS NULL OR @idx_cols <> 'status,expires_at,order_id', 1, 0);

-- 5.1 先建同列临时索引（已存在则跳过）
SET @ddl := IF(@need_swap = 1 AND @swap_exists = 0,
    'ALTER TABLE inventory_reservation ADD INDEX idx_reservation_expiry_v2 (status, expires_at, order_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5.2 仅当旧索引存在且定义不符时删除旧索引
SET @ddl := IF(@need_swap = 1 AND @idx_cols IS NOT NULL,
    'ALTER TABLE inventory_reservation DROP INDEX idx_reservation_expiry',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5.3 重新读取索引状态后再改名，避免使用 5.2 之前的旧变量
SET @final_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'inventory_reservation' AND index_name = 'idx_reservation_expiry');
SET @swap_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'inventory_reservation' AND index_name = 'idx_reservation_expiry_v2');
SET @ddl := IF(@need_swap = 1 AND @final_exists = 0 AND @swap_exists > 0,
    'ALTER TABLE inventory_reservation RENAME INDEX idx_reservation_expiry_v2 TO idx_reservation_expiry',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
