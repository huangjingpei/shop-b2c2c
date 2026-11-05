-- 文件：V2__s2_card_fingerprint_state.sql
-- 用途：T14 保留幂等列补齐：为已有的开发库补 fingerprint_unavailable 状态列，不包含历史指纹回填。
-- 维护注意：
--  1. 本执行器只用于保留结构的开发库；新库结构以 db/schema.sql 为唯一入口，两者不得互相替代。
--  2. 幂等：加列前用 information_schema 判断存在性；禁止 DROP TABLE、TRUNCATE、DELETE 或改写卡密/批次数据。
--  3. 本脚本不回填、不改写任何历史业务数据；运行时只对无指纹行做内存解密比对，解密失败标记并计数。

-- 1. 幂等新增指纹状态列
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'product_virtual_card' AND column_name = 'fingerprint_unavailable');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE product_virtual_card ADD COLUMN fingerprint_unavailable TINYINT NOT NULL DEFAULT 0 AFTER card_fingerprint',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
