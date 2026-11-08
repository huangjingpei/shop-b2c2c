-- 文件：V22__f08_payment_exception_disposition.sql
-- 用途：F08 支付异常人工处置增量结构，新增 payment_exception_disposition 表（只增不改、event_id 唯一）。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. CREATE TABLE IF NOT EXISTS 保证重复执行不报错；不设外键，事件归档后处置记录仍保留。
--  3. 本表只记录人工核对结论与操作人快照，不回填历史异常、不改写 payment_event 的 event_status/funds_received，
--     也不改订单、退款或资金账本；退款/补偿仍须走渠道或既有退款流程。
--  4. 不写 DROP/TRUNCATE/DELETE 或删库语句。

CREATE TABLE IF NOT EXISTS `payment_exception_disposition` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `event_id` BIGINT UNSIGNED NOT NULL COMMENT 'payment_event.id；唯一，同一事件只允许一次处置',
  `order_sn` VARCHAR(64) NULL COMMENT '订单号快照，事件归档后仍可定位',
  `action` VARCHAR(32) NOT NULL COMMENT 'REFUND_CONFIRMED/DUPLICATE_RESOLVED/CHANNEL_VERIFIED/NO_ACTION_REQUIRED',
  `note` VARCHAR(200) NOT NULL COMMENT '人工核对依据与结论，2-200 字，不含凭据与报文正文',
  `operator_id` BIGINT UNSIGNED NOT NULL COMMENT '处置操作的平台账号 ID 快照',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_disposition_event` (`event_id`),
  KEY `idx_payment_disposition_action` (`action`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付异常人工处置审计：只记录结论，不改写资金与订单状态';
