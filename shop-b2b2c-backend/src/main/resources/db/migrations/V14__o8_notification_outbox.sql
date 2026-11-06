-- 文件：V14__o8_notification_outbox.sql
-- 用途：O8 通知与SLA增量结构：新建 merchant_notification_outbox 通知发件箱表。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. 建表幂等：用 information_schema.tables 判断后动态执行；本表无外键依赖（通知允许先于业务行存在）。
--  3. 禁止 DROP TABLE/TRUNCATE/DELETE/删库语句；本脚本不回填、不清理历史数据。
--  4. uk_notification_event(biz_type,biz_id,channel,template_code) 是“每节点只发一次”的唯一兜底，
--     重复插入由应用层捕获 DuplicateKeyException 视为已通知，不得吞掉其它唯一冲突。

SET @tbl_exists := (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'merchant_notification_outbox');
SET @ddl := IF(@tbl_exists = 0,
    'CREATE TABLE merchant_notification_outbox (
      id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
      biz_type VARCHAR(32) NOT NULL COMMENT ''ONBOARDING_SUBMITTED/ONBOARDING_SUPPLEMENT/ONBOARDING_REJECTED/ONBOARDING_APPROVED/ONBOARDING_OPENED/LICENSE_EXPIRING/REVIEW_SLA_BREACH/STORE_STATUS_CHANGED'',
      biz_id BIGINT UNSIGNED NOT NULL COMMENT ''业务事件ID：开店节点=开店审计行ID，店铺状态=店铺状态审计行ID，SLA=申请ID'',
      channel VARCHAR(16) NOT NULL COMMENT ''INAPP/SMS'',
      template_code VARCHAR(64) NOT NULL COMMENT ''通知模板；SLA 分 REVIEW_SLA_REMINDER/REVIEW_SLA_ESCALATION/REVIEW_SLA_REVIEWER'',
      recipient VARCHAR(128) NOT NULL COMMENT ''申请人手机号 / MERCHANT:{店铺ID} / ROLE:{角色码}'',
      payload_json JSON NULL COMMENT ''通知内容快照；不含密码/证件/银行卡等敏感字段'',
      status VARCHAR(16) NOT NULL DEFAULT ''PENDING'' COMMENT ''PENDING/SENT/FAILED/SKIPPED'',
      attempts INT NOT NULL DEFAULT 0,
      next_retry_at DATETIME NULL,
      last_error VARCHAR(512) NULL,
      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      sent_at DATETIME NULL,
      PRIMARY KEY (id),
      UNIQUE KEY uk_notification_event (biz_type, biz_id, channel, template_code),
      KEY idx_notification_retry (status, next_retry_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''通知发件箱；SMS 通道默认禁用''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
