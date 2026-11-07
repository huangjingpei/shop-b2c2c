-- 文件：V19__f10_saas_fee_bill.sql
-- 用途：F10 SaaS 月度技术服务费账单增量结构，新增 saas_fee_bill 表（同店同月唯一）。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. CREATE TABLE IF NOT EXISTS 保证重复执行不报错；本脚本不回填历史月费、不改动既有 merchant_store 数据。
--  3. 本表与交易抽佣/merchant_settlement_batch 分离，不参与订单服务费结算；不做自动扣减余额或自动停复服。
--  4. 不写 DROP/TRUNCATE/DELETE 或删库语句。

CREATE TABLE IF NOT EXISTS `saas_fee_bill` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `bill_no` VARCHAR(64) NOT NULL COMMENT '账单号：SAAS-{merchantId}-{yyyyMM}',
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `billing_month` VARCHAR(7) NOT NULL COMMENT '账期月份 yyyy-MM（自然月，Asia/Shanghai）',
  `standard_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '按店铺月度费配置的标准应收，保留豁免前金额',
  `amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '本账单实际应收；WAIVED 时为 0.00',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PAID/WAIVED/OVERDUE',
  `waive_reason` VARCHAR(128) NULL COMMENT '免收原因：0 月费/EXEMPTED/试用期',
  `due_date` DATE NOT NULL COMMENT '缴费到期日（次月 10 日，代理细化规则待业务确认）',
  `issued_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `paid_at` DATETIME NULL,
  `paid_by` BIGINT NULL COMMENT '发起缴费确认的操作人',
  `confirmed_by` BIGINT NULL COMMENT '复核人；双人复核开关开启时必须与 paid_by 不同',
  `approval_mode` VARCHAR(16) NULL COMMENT 'SAME_OPERATOR/DUAL_APPROVAL',
  `payment_method` VARCHAR(16) NULL COMMENT '本期仅 OFFLINE（线下转账人工确认）',
  `voucher_no` VARCHAR(64) NULL COMMENT '线下转账凭证号',
  `remark` VARCHAR(255) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_saas_fee_bill_no` (`bill_no`),
  UNIQUE KEY `uk_saas_fee_merchant_month` (`merchant_id`, `billing_month`),
  KEY `idx_saas_fee_status_due` (`status`, `due_date`),
  CONSTRAINT fk_saas_fee_bill_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SaaS 月度技术服务费账单（与交易抽佣分离）';
