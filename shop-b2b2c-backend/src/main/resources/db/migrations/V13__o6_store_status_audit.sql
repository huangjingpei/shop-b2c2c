-- 文件：V13__o6_store_status_audit.sql
-- 用途：O6 店铺状态机增量结构：新建 merchant_store_status_audit 店铺状态流转审计表。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. 建表幂等：用 information_schema.tables 判断后动态执行；外键依赖 merchant_store（既有表）。
--  3. 禁止 DROP TABLE/TRUNCATE/DELETE/删库语句；本脚本不回填、不清理历史数据。
--  4. merchant_store.status 取值扩展为 0-待开通/1-营业/2-打烊/3-冻结/4-已注销（终态），
--     列定义不变（TINYINT），无需迁移；应用层 CAS 保证非法流转被拒绝并写审计。

SET @tbl_exists := (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'merchant_store_status_audit');
SET @ddl := IF(@tbl_exists = 0,
    'CREATE TABLE merchant_store_status_audit (
      id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
      merchant_id BIGINT UNSIGNED NOT NULL,
      action VARCHAR(16) NOT NULL COMMENT ''OPEN/PAUSE/RESUME/FREEZE/UNFREEZE/CLOSE/CANCEL'',
      from_status TINYINT NOT NULL,
      to_status TINYINT NOT NULL COMMENT ''0-待开通 1-营业 2-打烊 3-冻结 4-已注销(终态)'',
      reason_code VARCHAR(32) NOT NULL,
      reason VARCHAR(512) NULL,
      operator_id BIGINT NULL COMMENT ''为空表示系统任务（如资质过期自动冻结）'',
      confirmed_by BIGINT NULL COMMENT ''复核人；解封/注销双人复核时必填'',
      approval_mode VARCHAR(16) NULL COMMENT ''SAME_OPERATOR-非严格模式同人放行并告警 / DUAL-双人复核'',
      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (id),
      KEY idx_store_status_audit_merchant (merchant_id, created_at),
      CONSTRAINT fk_store_status_audit_store FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''店铺状态流转审计''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
