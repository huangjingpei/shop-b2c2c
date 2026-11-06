-- 文件：V7__s3_public_onboarding.sql
-- 用途：S3 商家自助开店/人工实人认证增量结构（不含历史数据回填）：申请查询凭证、店铺名称、实人认证口令、初始密码密文与证件附件元数据表。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. 全部变更必须幂等：列/索引用 information_schema 判断，表用 CREATE TABLE IF NOT EXISTS，失败重试不得留下半套结构。
--  3. 禁止 DROP TABLE/TRUNCATE/DELETE/删库语句；不回填历史申请，旧申请 access_token_hash 为空时公开接口一律拒绝。
--  4. 证件文件本体不在数据库，落在 shop.onboarding.attachment-dir 私有目录并纳入备份；此表只存随机文件名与摘要。
--  5. 个人店实人认证阶段一为人工审核：face_verify_code 是给申请人录视频朗读的随机数字口令，不是密钥。

-- 1. 店铺名称（申请人填写，审核通过自动开店使用）
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'store_name');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN store_name VARCHAR(128) NULL AFTER subject_name',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 个人店实人认证随机数字口令
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'face_verify_code');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN face_verify_code VARCHAR(16) NULL AFTER store_name',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 查询凭证 SHA-256
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'access_token_hash');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN access_token_hash VARCHAR(128) NULL AFTER contact_email',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 自动开店初始密码密文
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'initial_password_cipher');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN initial_password_cipher VARCHAR(512) NULL AFTER access_token_hash',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. 初始密码一次性展示时间
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'initial_password_revealed_at');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN initial_password_revealed_at DATETIME NULL AFTER initial_password_cipher',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6. 查询凭证索引
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND index_name = 'idx_onboarding_token');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD INDEX idx_onboarding_token (access_token_hash)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 7. 证件附件元数据表
CREATE TABLE IF NOT EXISTS `merchant_onboarding_attachment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `application_id` BIGINT UNSIGNED NOT NULL,
  `kind` VARCHAR(16) NOT NULL COMMENT 'ID_CARD_FRONT/ID_CARD_BACK/LICENSE/FACE_VIDEO/OTHER',
  `file_key` VARCHAR(255) NOT NULL COMMENT '相对私有目录的随机文件名，禁止保存原始文件名',
  `content_type` VARCHAR(64) NOT NULL,
  `size_bytes` BIGINT NOT NULL,
  `sha256` VARCHAR(128) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_onboarding_attachment_file` (`file_key`),
  KEY `idx_onboarding_attachment_app` (`application_id`, `kind`),
  CONSTRAINT fk_onboarding_attachment_app FOREIGN KEY (`application_id`) REFERENCES `merchant_onboarding_application` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家入驻证件附件元数据';
