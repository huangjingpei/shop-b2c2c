-- 文件：V3__s2_card_access_audit.sql
-- 用途：T15 卡密明文访问审计表（导出留痕）；同时作为新库基线之外的存量库结构补齐脚本。
-- 维护注意：
--  1. 脚本必须幂等：使用 CREATE TABLE IF NOT EXISTS，可重复执行且不修改已有数据。
--  2. 只追加审计，不保存卡密正文/指纹；不得加入 DELETE/TRUNCATE/DROP 或数据回填。
--  3. 项目处于开发阶段，无兼容负担；新库以 db/schema.sql 为准，本脚本仅保证迁移器幂等重跑。
CREATE TABLE IF NOT EXISTS `card_access_audit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID',
  `actor_id` BIGINT UNSIGNED NOT NULL COMMENT '操作人用户ID（取自已校验会话）',
  `action` VARCHAR(32) NOT NULL COMMENT '动作: EXPORT_PLAINTEXT',
  `sku_id` BIGINT UNSIGNED NOT NULL COMMENT '明文涉及的SKU',
  `card_count` INT NOT NULL COMMENT '本次返回的明文条数',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_audit_merchant_created` (`merchant_id`, `created_at`),
  KEY `idx_audit_sku_created` (`sku_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卡密明文访问审计表';
