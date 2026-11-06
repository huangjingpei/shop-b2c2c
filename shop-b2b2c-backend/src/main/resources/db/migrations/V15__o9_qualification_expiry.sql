-- 文件：V15__o9_qualification_expiry.sql
-- 用途：O9 资质有效期与提醒增量结构：merchant_onboarding_application 与 merchant_store 各加
--       id_card_expiry/license_expiry DATE NULL 两列。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. 加列幂等：逐列用 information_schema.columns 判断后动态执行；重复执行不报错、不改已有数据。
--  3. 本脚本只做加列，不回填历史行有效期（历史店铺有效期为空视为“未采集”，不参与提醒/冻结）；
--     不得写 DROP/TRUNCATE/DELETE/删库语句。
--  4. merchant_store 两列是审核通过时的快照；商家后续在资料页更新有效期也写这两列，
--     申请单两列保持申请时填报值，不再回写。

-- 1) 申请单：身份证有效期
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'id_card_expiry');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN id_card_expiry DATE NULL COMMENT ''身份证有效期（O9）；可空表示长期/未采集，审核通过时快照到 merchant_store''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 申请单：营业执照有效期
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'license_expiry');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN license_expiry DATE NULL COMMENT ''营业执照有效期（O9）；个人店可空，审核通过时快照到 merchant_store''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) 店铺：身份证有效期快照
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_store' AND column_name = 'id_card_expiry');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_store ADD COLUMN id_card_expiry DATE NULL COMMENT ''身份证有效期快照：审核通过时从入驻申请快照（O9），用于到期提醒/限制上新/逾期冻结''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4) 店铺：营业执照有效期快照
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_store' AND column_name = 'license_expiry');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_store ADD COLUMN license_expiry DATE NULL COMMENT ''营业执照有效期快照：审核通过时从入驻申请快照（O9），个人店可空''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
