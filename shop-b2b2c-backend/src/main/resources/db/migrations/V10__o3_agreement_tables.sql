-- 文件：V10__o3_agreement_tables.sql
-- 用途：O3 协议版本与同意记录两张新表（开通闸门的签署证据链）。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. CREATE TABLE IF NOT EXISTS 本身幂等，可重复执行；禁止 DROP TABLE/TRUNCATE/DELETE/删库语句。
--  3. merchant_agreement_consent 为 append-only 证据表：禁止 UPDATE/DELETE；外键指向入驻申请。
--  4. 同类型协议仅一条 PUBLISHED 由服务层在事务内保证（发布新版本时同事务下线旧版本），不依赖数据库约束。

CREATE TABLE IF NOT EXISTS `merchant_agreement_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `protocol_type` VARCHAR(32) NOT NULL COMMENT 'MERCHANT_SERVICE/PRIVACY/SETTLEMENT/PROHIBITED_GOODS',
  `version` VARCHAR(32) NOT NULL COMMENT '语义化版本或发布日期序号',
  `title` VARCHAR(128) NOT NULL,
  `content_hash` VARCHAR(128) NOT NULL COMMENT '协议正文 SHA-256；同意时校验，版本不一致拒绝',
  `content_url` VARCHAR(512) NULL COMMENT '协议快照地址；不得指向可变页面',
  `effective_from` DATETIME NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/OFFLINE',
  `created_by` BIGINT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agreement_type_version` (`protocol_type`, `version`),
  KEY `idx_agreement_type_status` (`protocol_type`, `status`, `effective_from`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协议版本';

CREATE TABLE IF NOT EXISTS `merchant_agreement_consent` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `application_id` BIGINT UNSIGNED NULL COMMENT '申请人维度签署；开通后补签填店铺维度',
  `merchant_id` BIGINT UNSIGNED NULL,
  `protocol_type` VARCHAR(32) NOT NULL,
  `protocol_version` VARCHAR(32) NOT NULL,
  `content_hash` VARCHAR(128) NOT NULL COMMENT '签署时协议正文哈希快照',
  `subject_name` VARCHAR(128) NOT NULL COMMENT '签署主体（实名主体/店铺名）',
  `consent_method` VARCHAR(16) NOT NULL DEFAULT 'CHECKBOX' COMMENT 'CHECKBOX/ESIGN；阶段一固定 CHECKBOX',
  `ip` VARCHAR(64) NULL,
  `user_agent` VARCHAR(255) NULL,
  `agreed_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_consent_app_protocol` (`application_id`, `protocol_type`, `protocol_version`),
  KEY `idx_consent_merchant` (`merchant_id`, `agreed_at`),
  CONSTRAINT fk_consent_app FOREIGN KEY (`application_id`) REFERENCES `merchant_onboarding_application` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协议同意记录（append-only，禁止更新/删除）';
