-- 文件：V12__o5_settlement_account_verification.sql
-- 用途：O5 结算账户验证与变更增量结构：merchant_settlement_account 增加 verify_method/replaces_account_id，
--      新建 merchant_payout_verification 打款验证表（含金额/次数 CHECK 与 (account_id, attempt_no) 唯一）。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. 全部变更幂等：列/表用 information_schema.columns、tables、statistics 判断后动态执行。
--  3. 禁止 DROP TABLE/TRUNCATE/DELETE/删库语句；本脚本不回填、不清理历史数据。
--  4. 执行前提：无（新列均有默认值或允许 NULL，历史行自动按 MANUAL 处理）。
--  5. 打款验证金额由服务端生成并仅平台可见；应用层必须保证商家端接口不返回 amount 字段。

-- 1. 收款账户：核验方式列
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_settlement_account' AND column_name = 'verify_method');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_settlement_account ADD COLUMN verify_method VARCHAR(16) NOT NULL DEFAULT ''MANUAL'' COMMENT ''O5 核验方式: MANUAL-人工核验, PAYOUT-小额打款验证；记录账户通过核验的方式'' AFTER default_merchant_id',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 收款账户：变更指向旧账户列
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_settlement_account' AND column_name = 'replaces_account_id');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_settlement_account ADD COLUMN replaces_account_id BIGINT UNSIGNED NULL COMMENT ''O5 变更申请指向的旧账户；核验通过事务内切换默认并停用旧账户默认位'' AFTER verify_method',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 收款账户：变更指向索引
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'merchant_settlement_account' AND index_name = 'idx_settlement_replaces');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE merchant_settlement_account ADD KEY idx_settlement_replaces (replaces_account_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 打款验证表
SET @tbl_exists := (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'merchant_payout_verification');
SET @ddl := IF(@tbl_exists = 0,
    'CREATE TABLE merchant_payout_verification (
      id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
      account_id BIGINT UNSIGNED NOT NULL,
      merchant_id BIGINT UNSIGNED NOT NULL,
      attempt_no INT NOT NULL COMMENT ''第几次验证尝试，1-3'',
      amount DECIMAL(12,2) NOT NULL COMMENT ''服务端生成的校验金额，0.01-0.99；仅平台可见，不得透传给商家端'',
      currency CHAR(3) NOT NULL DEFAULT ''CNY'',
      status VARCHAR(16) NOT NULL DEFAULT ''PENDING'' COMMENT ''PENDING/SUCCESS/FAILED/EXPIRED'',
      transfer_voucher_no VARCHAR(128) NULL COMMENT ''线下转账凭证号；通道接入前使用'',
      requested_by BIGINT NULL COMMENT ''发起人（超管）；确认人不得与发起人同一人'',
      requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      expires_at DATETIME NOT NULL,
      confirmed_at DATETIME NULL,
      confirmed_by BIGINT NULL,
      failure_code VARCHAR(32) NULL COMMENT ''AMOUNT_MISMATCH/EXPIRED/ATTEMPTS_EXCEEDED'',
      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (id),
      UNIQUE KEY uk_payout_account_attempt (account_id, attempt_no),
      KEY idx_payout_pending_expire (status, expires_at),
      KEY idx_payout_merchant (merchant_id, created_at),
      CONSTRAINT fk_payout_account FOREIGN KEY (account_id) REFERENCES merchant_settlement_account (id),
      CONSTRAINT chk_payout_amount CHECK (amount > 0.00 AND amount <= 1.00),
      CONSTRAINT chk_payout_attempt CHECK (attempt_no BETWEEN 1 AND 3)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''收款账户打款验证''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
