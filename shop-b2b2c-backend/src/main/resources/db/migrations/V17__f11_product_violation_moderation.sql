-- 文件：V17__f11_product_violation_moderation.sql
-- 用途：F11 商品违规下架/审核增量结构：product 增加 violation_reason/violated_at 两列，新建 product_violation_audit 处置审计表。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. 全部幂等：用 information_schema 判断后动态执行；外键依赖 product 与 merchant_store（既有表）。
--  3. 禁止 DROP TABLE/TRUNCATE/DELETE/删库语句；本脚本不回填、不清理历史数据，历史商品两列保持 NULL。
--  4. product.status 取值不变（0-已下架/1-在售/2-违规下架），违规处置由应用层 CAS（status 条件更新）保证并发只成功一次。

SET @col_reason := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'product' AND column_name = 'violation_reason');
SET @ddl_reason := IF(@col_reason = 0,
    'ALTER TABLE product ADD COLUMN violation_reason VARCHAR(255) DEFAULT NULL COMMENT ''最近一次违规处置原因（平台填写，审核恢复后清空）'' AFTER status',
    'SELECT 1');
PREPARE stmt FROM @ddl_reason;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_at := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'product' AND column_name = 'violated_at');
SET @ddl_at := IF(@col_at = 0,
    'ALTER TABLE product ADD COLUMN violated_at DATETIME DEFAULT NULL COMMENT ''最近一次违规下架时间（审核恢复后清空）'' AFTER violation_reason',
    'SELECT 1');
PREPARE stmt FROM @ddl_at;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @tbl_exists := (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'product_violation_audit');
SET @ddl := IF(@tbl_exists = 0,
    'CREATE TABLE product_violation_audit (
      id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
      product_id BIGINT UNSIGNED NOT NULL COMMENT ''被处置商品ID'',
      merchant_id BIGINT UNSIGNED NOT NULL COMMENT ''商品所属店铺ID（冗余便于按店筛查）'',
      action VARCHAR(16) NOT NULL COMMENT ''TAKEDOWN-违规下架 / RESTORE-审核恢复'',
      from_status TINYINT NOT NULL COMMENT ''处置前商品状态: 0-已下架, 1-在售, 2-违规下架'',
      to_status TINYINT NOT NULL COMMENT ''处置后商品状态'',
      reason VARCHAR(255) NOT NULL COMMENT ''处置原因/审核备注'',
      operator_id BIGINT NOT NULL COMMENT ''操作的平台超管 sys_user.id'',
      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (id),
      KEY idx_violation_audit_product (product_id, created_at),
      KEY idx_violation_audit_merchant (merchant_id, created_at),
      CONSTRAINT fk_violation_audit_product FOREIGN KEY (product_id) REFERENCES product (id),
      CONSTRAINT fk_violation_audit_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''商品违规处置审计''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
