-- 文件：V20__f01_payment_callback_audit.sql
-- 用途：F01 支付回调持久化审计增量结构，新增 payment_callback_audit 表（只增不改，不保存回调正文）。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. CREATE TABLE IF NOT EXISTS 保证重复执行不报错；不设外键（拒绝类回调的订单可能不存在）。
--  3. 本表不是资金账本，不回填历史回调、不推断到账、不改动 payment_event 既有数据。
--  4. 不写 DROP/TRUNCATE/DELETE 或删库语句。

CREATE TABLE IF NOT EXISTS `payment_callback_audit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `channel` VARCHAR(32) NOT NULL COMMENT '回调渠道 WECHAT/ALIPAY；缺失或未知写 UNKNOWN',
  `order_sn` VARCHAR(64) NULL COMMENT '回调声明的订单号；验签/解析失败或未提供时为空',
  `pay_trade_no` VARCHAR(128) NULL COMMENT '渠道流水号；验签/解析失败时通常为空',
  `channel_merchant_id` VARCHAR(64) NULL COMMENT '渠道侧商户号，仅审计比对，不能替代平台内部 merchant_id',
  `outcome` VARCHAR(16) NOT NULL COMMENT 'ACCEPTED 受理成功 / REJECTED 验签、归属或资金要素拒绝 / ERROR 未预期异常',
  `processing_result` VARCHAR(32) NULL COMMENT '受理成功后的支付事件状态（SUCCESS/DUPLICATE_PAYMENT 等）；拒绝时为空',
  `reason` VARCHAR(255) NULL COMMENT '拒绝/异常原因摘要，写入前截断，不含报文正文与凭据',
  `payload_sha256` CHAR(64) NOT NULL COMMENT '规范化回调输入的 SHA-256，用于同源重试关联；不保存原文',
  `duration_ms` INT NOT NULL DEFAULT 0 COMMENT '从进入网关到写入审计的耗时（毫秒）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_payment_callback_order` (`channel`, `order_sn`, `created_at`),
  KEY `idx_payment_callback_fingerprint` (`payload_sha256`),
  KEY `idx_payment_callback_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付渠道回调审计：只留指纹与结果，不保存回调正文/凭据';
