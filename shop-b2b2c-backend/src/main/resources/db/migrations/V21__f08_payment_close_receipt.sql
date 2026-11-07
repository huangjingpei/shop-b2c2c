-- 文件：V21__f08_payment_close_receipt.sql
-- 用途：F08 渠道关单回执增量结构，新增 payment_close_receipt 表（只增不改，支撑关单失败退避重试）。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. CREATE TABLE IF NOT EXISTS 保证重复执行不报错；不设外键（回执需在订单归档/缺失时仍可保留）。
--  3. 不回填历史关单尝试、不改订单状态、不修改既有 payment_event；历史失败关单仍需人工核对。
--  4. 不写 DROP/TRUNCATE/DELETE 或删库语句。

CREATE TABLE IF NOT EXISTS `payment_close_receipt` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_sn` VARCHAR(64) NOT NULL COMMENT '订单号；用于与 trade_order 关联，不设外键',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '下单商家 ID 快照，用于审计归属',
  `channel` VARCHAR(32) NOT NULL COMMENT '关单渠道 WECHAT/ALIPAY',
  `attempt_no` INT NOT NULL COMMENT '同一订单+渠道的关单尝试序号，从 1 递增',
  `result` VARCHAR(16) NOT NULL COMMENT 'SUCCESS 调用成功 / FAILED 调用失败可重试 / SKIPPED 渠道账户或适配器未就绪',
  `reason` VARCHAR(255) NULL COMMENT '失败/跳过原因摘要，写入前截断，不含凭据与报文正文',
  `attempted_at` DATETIME NOT NULL COMMENT '本次尝试时间，重试退避与窗口以此为准',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_close_receipt_order` (`order_sn`, `channel`, `attempt_no`),
  KEY `idx_close_receipt_retry` (`result`, `attempted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道关单回执：支撑失败退避重试与排障';
