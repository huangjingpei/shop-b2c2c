-- 文件：V4__s2_merchant_sku_code_unique.sql
-- 用途：T17/Q14-A 将 SKU 编码唯一域从全局 sku_code 收窄为店内 (merchant_id, sku_code)。
-- 维护注意：
--  1. 幂等：先按 information_schema 判断旧索引是否存在，存在才删除；新索引不存在才创建，可重复执行。
--  2. 开发期无存量兼容负担；新库结构以 db/schema.sql 为准，本脚本只保证迁移器在新库/已迁移库上重跑不报错。
--  3. 不包含数据回填、DELETE/TRUNCATE/DROP TABLE；失败由 SchemaMigrationRunner 阻断启动。
--  4. 并发的同店同码由数据库唯一键兜底，应用层预检只用于稳定返回 409，不能替代该索引。

-- 1. 删除旧的全局唯一索引（若存在）
SET @old_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'product_sku' AND index_name = 'uk_sku_code');
SET @ddl := IF(@old_exists > 0, 'ALTER TABLE product_sku DROP INDEX uk_sku_code', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 创建店内唯一索引（若不存在）
SET @new_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'product_sku' AND index_name = 'uk_merchant_sku_code');
SET @ddl := IF(@new_exists = 0,
    'ALTER TABLE product_sku ADD UNIQUE KEY uk_merchant_sku_code (merchant_id, sku_code)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
