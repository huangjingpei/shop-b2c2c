-- 文件：V8__o1_onboarding_review_stage.sql
-- 用途：O1 入驻审核状态机补强增量结构（不含历史数据回填）：驳回原因码、补件次数、初审/复审阶段、协议与开通时间、店铺状态 4=注销注释。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. 全部变更必须幂等：列用 information_schema.columns 判断后动态执行；COMMENT 修改本身幂等可重复。
--  3. 禁止 DROP TABLE/TRUNCATE/DELETE/删库语句；不回填历史申请数据，旧行 review_stage 由列默认值 NONE 表达"未进入复审流程"。
--  4. reject_code 字典见 docs/2026-11-07-merchant-onboarding-development-spec.md 5.5；新增原因码必须先更新文档附录 A 再改代码。

-- 1. 申请单：驳回/补件原因码
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'reject_code');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN reject_code VARCHAR(32) NULL COMMENT ''驳回/补件原因码（开发文档 5.5 字典）；OTHER 时 reject_reason 必填'' AFTER verification_source',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 申请单：补件重提次数
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'resubmit_count');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN resubmit_count INT NOT NULL DEFAULT 0 COMMENT ''补件后重新提交次数'' AFTER reject_reason',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 申请单：审核阶段
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'review_stage');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN review_stage VARCHAR(16) NOT NULL DEFAULT ''NONE'' COMMENT ''审核阶段 NONE/PRIMARY/SECONDARY/PASSED；双人复核开启时初审通过后置 SECONDARY 待复审'' AFTER resubmit_count',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 申请单：初审人
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'first_reviewer_id');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN first_reviewer_id BIGINT NULL COMMENT ''初审人；双人复核开启时复审人必须与其不同'' AFTER review_stage',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. 申请单：是否必须先签协议
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'agreement_required');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN agreement_required TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''开通前是否必须完成必需协议签署'' AFTER first_reviewer_id',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6. 申请单：全部必需协议签署完成时间
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'agreed_at');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN agreed_at DATETIME NULL COMMENT ''全部必需协议签署完成时间'' AFTER agreement_required',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 7. 申请单：开通时间
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'opened_at');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN opened_at DATETIME NULL COMMENT ''店铺开通时间（进入 OPENED）'' AFTER agreed_at',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 8. 申请单：逾期撤回/取消原因
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_application' AND column_name = 'cancel_reason');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_application ADD COLUMN cancel_reason VARCHAR(512) NULL COMMENT ''逾期撤回/取消原因（系统 EXPIRE 或人工撤回）'' AFTER opened_at',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 9. 申请单状态注释补充 OPENED（MODIFY 幂等，不改变存量数据语义）
ALTER TABLE merchant_onboarding_application
  MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'DRAFT'
  COMMENT 'DRAFT/SUBMITTED/REVIEWING/APPROVED/OPENED/REJECTED/SUPPLEMENT_REQUIRED/WITHDRAWN；OPENED=已开通可营业';

-- 10. 店铺状态注释补充 4=已注销（MODIFY 幂等，不放宽既有取值的行为约定）
ALTER TABLE merchant_store
  MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1
  COMMENT '店铺状态: 0-待开通(已建店未完成协议/验证), 1-正常营业, 2-打烊, 3-已封禁, 4-已注销(终态)';

-- 11. 审核审计：原因码快照
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_audit' AND column_name = 'reject_code');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_audit ADD COLUMN reject_code VARCHAR(32) NULL COMMENT ''驳回/补件原因码快照，与申请单 reject_code 同字典'' AFTER reason',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 12. 审核审计：审核阶段快照
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_onboarding_audit' AND column_name = 'review_stage');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_onboarding_audit ADD COLUMN review_stage VARCHAR(16) NULL COMMENT ''动作完成后的审核阶段 NONE/PRIMARY/SECONDARY/PASSED'' AFTER reject_code',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
