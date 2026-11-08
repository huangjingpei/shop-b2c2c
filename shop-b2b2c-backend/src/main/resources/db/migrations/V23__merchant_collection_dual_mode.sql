-- 文件：V23__merchant_collection_dual_mode.sql
-- 用途：M1 商家收款双轨增量结构：merchant_store.collection_mode、平台代收全局总闸、切换审计表。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的存量开发库，两者不得互相替代。
--  2. 列变更用 information_schema 存在性判断 + 动态 DDL，表和默认行用 IF NOT EXISTS/幂等插入，重复执行不报错。
--  3. 不回填、不改写任何历史订单的 trade_order.collection_mode；切换只影响新订单。
--  4. 不写 DROP/TRUNCATE/DELETE/USE 或删库语句；不复制任何商户号、卡号、密钥。

SET @collection_mode_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'merchant_store' AND column_name = 'collection_mode'
);
SET @collection_mode_ddl = IF(
  @collection_mode_exists = 0,
  'ALTER TABLE `merchant_store` ADD COLUMN `collection_mode` VARCHAR(24) NOT NULL DEFAULT ''MERCHANT_DIRECT'' COMMENT ''M1 收款模式：MERCHANT_DIRECT 商家直收 / PLATFORM_COLLECT 平台代收；只决定新订单快照'' AFTER `pay_channel`',
  'SELECT 1'
);
PREPARE collection_mode_stmt FROM @collection_mode_ddl;
EXECUTE collection_mode_stmt;
DEALLOCATE PREPARE collection_mode_stmt;

CREATE TABLE IF NOT EXISTS `platform_collection_config` (
  `id` BIGINT UNSIGNED NOT NULL COMMENT '单行配置，固定为1',
  `enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '平台代收全局总闸：0关闭(新订单按商家直收)/1允许按商家模式生效',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '配置版本号，每次更新+1',
  `updated_by` BIGINT UNSIGNED NULL COMMENT '最近操作平台账号ID',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台代收全局总闸（单行配置）';

INSERT IGNORE INTO `platform_collection_config` (`id`, `enabled`, `version`) VALUES (1, 0, 0);

CREATE TABLE IF NOT EXISTS `merchant_collection_switch_audit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID',
  `from_mode` VARCHAR(24) NOT NULL COMMENT '切换前模式',
  `to_mode` VARCHAR(24) NOT NULL COMMENT '切换后模式',
  `reason` VARCHAR(256) NULL COMMENT '运营填写的切换原因',
  `operator_id` BIGINT UNSIGNED NULL COMMENT '操作人平台账号ID；系统任务可空',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_collection_switch_merchant` (`merchant_id`, `created_at`),
  CONSTRAINT fk_collection_switch_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家收款模式切换审计（只增不改）';
