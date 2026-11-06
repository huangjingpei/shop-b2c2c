-- 文件：V11__d11_must_change_password.sql
-- 用途：D11 首次登录强制改密增量结构：sys_user.must_change_password 标记列。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. 变更必须幂等：列用 information_schema.columns 判断后动态执行；存量账号默认 0（不强制改密），
--     只有本脚本执行后新生成的开店店长账号由服务层置 1，历史数据不回填。
--  3. 禁止 DROP/TRUNCATE/DELETE；不改写存量口令。

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND column_name = 'must_change_password');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE sys_user ADD COLUMN must_change_password TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''D11 首次登录强制改密: 1-须先改密才能访问业务工作台(仅 /api/v1/auth/** 可用), 0-正常；审核开店生成的店长账号落 1'' AFTER is_active',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
