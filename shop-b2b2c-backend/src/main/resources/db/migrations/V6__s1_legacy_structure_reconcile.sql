-- 文件：V6__s1_legacy_structure_reconcile.sql
-- 用途：把 V1 引入前的存量库结构补齐到当前 db/schema.sql 基线（只补结构，不回填业务数据）。
--      范围：V1 基线后新增的 T32/T33/T34/T39 列、payment_event 关联列符号位、trade_order.payment_channel 非空、
--      旧表缺失的 37 个外键（含 V3 已建表但没有外键的 card_access_audit）。
-- 维护注意：
--  1. 仅用于保留数据的存量库；新库结构以 db/schema.sql 为唯一入口。存量库升级必须先导入 db/schema.sql（部署 --migrate）再启动应用，
--     否则 V 系列只补旧表增量、不会创建 T33/T39/T40 等新表，应用会因缺表失败。
--  2. 全部变更幂等：列与外键均按 information_schema 存在性判断，失败重试可续做，不得留下半套结构。
--  3. 禁止 DROP TABLE、TRUNCATE、DELETE、USE、DROP DATABASE；不写 UPDATE，不回填历史金额/订单/事件数据。
--  4. 新增列一律追加到表尾（不写 AFTER），使 MySQL 8 可用 INSTANT 加列；列序与 schema.sql 不同，但名称/类型/可空/默认值一致。
--     结构等价以 information_schema 字段属性为准，不以 ordinal_position 为准。
--  5. 外键会校验现有数据：存在孤儿行时脚本报错并阻断启动，这是明确失败语义；不得为通过校验而删除或改写业务数据，须人工核对后处理。
--  6. payment_event.merchant_id 由 BIGINT 改为 BIGINT UNSIGNED 以匹配 merchant_store.id；存在负数/越界值时显式失败。
--  7. trade_order.payment_channel 改为 NOT NULL DEFAULT 'WECHAT'；存量有 NULL 行时显式失败，须人工确认后先补值再重跑。

-- ===== 一、旧表缺失列（V1 基线之后的 T32/T33/T34/T39 增量） =====

-- merchant_store：补齐 1 列（缺失才加，全部追加到表尾）
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_store' AND column_name = 'pay_channel');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_store ADD COLUMN pay_channel VARCHAR(32) NOT NULL DEFAULT ''WECHAT'' COMMENT ''T32 新增：真实支付渠道 WECHAT/ALIPAY，与 A/B/C 收款模式解耦'' AFTER payment_channel_type',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- merchant_payment_account：补齐 7 列（缺失才加，全部追加到表尾）
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_payment_account' AND column_name = 'settlement_account_id');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_payment_account ADD COLUMN settlement_account_id BIGINT UNSIGNED NULL COMMENT ''T39 阶段二预留：关联 merchant_settlement_account，阶段一先不写值'' AFTER reject_reason',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_payment_account' AND column_name = 'binding_status');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_payment_account ADD COLUMN binding_status VARCHAR(24) NOT NULL DEFAULT ''UNBOUND'' COMMENT ''T39 阶段二预留：UNBOUND/BINDING/BOUND/REJECTED/DISABLED，只允许渠道真实结果推进'' AFTER settlement_account_id',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_payment_account' AND column_name = 'channel_merchant_id');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_payment_account ADD COLUMN channel_merchant_id VARCHAR(64) NULL COMMENT ''T39 阶段二预留：渠道真实商户号返回值'' AFTER binding_status',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_payment_account' AND column_name = 'bound_at');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_payment_account ADD COLUMN bound_at DATETIME NULL COMMENT ''T39 阶段二预留：绑定完成时间'' AFTER channel_merchant_id',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_payment_account' AND column_name = 'effective_from');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_payment_account ADD COLUMN effective_from DATETIME NULL COMMENT ''T39 阶段二预留：绑定生效时间'' AFTER bound_at',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_payment_account' AND column_name = 'effective_to');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_payment_account ADD COLUMN effective_to DATETIME NULL COMMENT ''T39 阶段二预留：绑定失效时间'' AFTER effective_from',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_payment_account' AND column_name = 'raw_result');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_payment_account ADD COLUMN raw_result TEXT NULL COMMENT ''T39 阶段二预留：渠道原始返回，供人工核对'' AFTER effective_to',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- trade_order：补齐 21 列（缺失才加，全部追加到表尾）
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'refunded_amount');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN refunded_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''T33 已退款商品金额（含渠道处理中预占）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'fee_refunded_amount');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN fee_refunded_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''T33 已冲正/预占技术服务费（含处理中预占）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'fee_status');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN fee_status VARCHAR(24) NOT NULL DEFAULT ''PENDING'' COMMENT ''T33/T34 服务费状态 PENDING-未收费, ACCRUED-已入账, ARREARS-欠费, PARTIAL_REVERSED-部分冲正, REVERSED-全额冲正''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'goods_amount');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN goods_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''T34 计费基数：商品成交额（不含运费/税费）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'merchant_discount_amount');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN merchant_discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''T34 商家优惠金额''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'platform_discount_amount');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN platform_discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''T34 平台优惠金额''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'shipping_amount');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN shipping_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''T34 运费，不计入服务费基数''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'order_tax_amount');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN order_tax_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''T34 订单税额（价税分离）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'fee_policy_id');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN fee_policy_id BIGINT NULL COMMENT ''T34 订单级费率快照来源的政策ID；T39 起写 merchant_fee_policy.id，历史订单可为空''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'fee_rate_bps');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN fee_rate_bps INT NOT NULL DEFAULT 0 COMMENT ''T34 订单级费率快照，万分比；300 表示 3%''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'fee_rate_type');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN fee_rate_type VARCHAR(24) NOT NULL DEFAULT ''NORMAL'' COMMENT ''T34 费率类型 NORMAL/PROMOTION；PROMOTION 表示推广期 0 费率''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'fee_base_amount');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN fee_base_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''T34 服务费计费基数=商品成交额-商家优惠''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'fee_standard_amount');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN fee_standard_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''T34 标准服务费=基数×费率''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'fee_amount');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN fee_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''T34 应收服务费（推广期可为0）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'fee_waived_amount');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN fee_waived_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''T34 减免金额，推广期等于标准服务费''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'fee_net_amount');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN fee_net_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''T34 服务费不含税额''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'fee_tax_amount');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN fee_tax_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''T34 服务费税额''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'fee_tax_rate');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN fee_tax_rate DECIMAL(7,4) NOT NULL DEFAULT 0.0000 COMMENT ''T34 服务费税率快照，如 0.0600''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'collection_mode');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN collection_mode VARCHAR(24) NOT NULL DEFAULT ''MERCHANT_DIRECT'' COMMENT ''T32 新增：MERCHANT_DIRECT/PLATFORM_COLLECT，阶段一固定商家直收''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'pay_channel');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN pay_channel VARCHAR(32) NOT NULL DEFAULT ''WECHAT'' COMMENT ''T32 新增：真实支付渠道快照 WECHAT/ALIPAY''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'platform_trade_no');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN platform_trade_no VARCHAR(128) NULL COMMENT ''T40 阶段二预留：平台代收渠道流水号；阶段一为空，不得用商家直收流水回填''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- payment_event：补齐 3 列（缺失才加，全部追加到表尾）
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'payment_event' AND column_name = 'collection_mode');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE payment_event ADD COLUMN collection_mode VARCHAR(24) NOT NULL DEFAULT ''MERCHANT_DIRECT'' COMMENT ''T32 新增：MERCHANT_DIRECT/PLATFORM_COLLECT'' AFTER merchant_id',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'payment_event' AND column_name = 'channel_merchant_id');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE payment_event ADD COLUMN channel_merchant_id VARCHAR(64) NULL COMMENT ''T32 新增：渠道侧商户号（微信mchid/支付宝seller_id），与内部商户ID分离'' AFTER collection_mode',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'payment_event' AND column_name = 'platform_trade_no');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE payment_event ADD COLUMN platform_trade_no VARCHAR(128) NULL COMMENT ''T32 新增：阶段二平台流水；阶段一为空'' AFTER channel_merchant_id',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ===== 二、列属性对齐（符号位与非空） =====

-- payment_event.merchant_id：旧库为有符号 BIGINT，无法与 merchant_store.id（BIGINT UNSIGNED）建外键
SET @col_missing := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'payment_event' AND column_name = 'merchant_id');
SET @col_signed := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'payment_event' AND column_name = 'merchant_id'
      AND LOWER(column_type) NOT LIKE '%unsigned%');
SET @ddl := IF(@col_missing = 1 AND @col_signed = 1,
    'ALTER TABLE payment_event MODIFY COLUMN merchant_id BIGINT UNSIGNED NOT NULL COMMENT ''商户ID''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- trade_order.payment_channel：旧库可空，当前基线 NOT NULL DEFAULT 'WECHAT'
SET @col_nullable := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'payment_channel'
      AND is_nullable = 'YES');
SET @ddl := IF(@col_nullable = 1,
    'ALTER TABLE trade_order MODIFY COLUMN payment_channel VARCHAR(32) NOT NULL DEFAULT ''WECHAT'' COMMENT ''WECHAT, ALIPAY; T32 起只写真实渠道，不再写 A/B/C''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ===== 三、旧表缺失外键（逐条存在性判断，名称与 db/schema.sql 一致） =====
-- 说明：card_import_item 的 2 个外键在 V1 基线已存在，此处不重复；本节 37 条用于补齐其余旧表。

-- merchant_payment_account.fk_payment_account_merchant
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'merchant_payment_account' AND constraint_name = 'fk_payment_account_merchant');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE merchant_payment_account ADD CONSTRAINT fk_payment_account_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- sys_user.fk_user_merchant
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND constraint_name = 'fk_user_merchant');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE sys_user ADD CONSTRAINT fk_user_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- product.fk_product_merchant
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'product' AND constraint_name = 'fk_product_merchant');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE product ADD CONSTRAINT fk_product_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- product.fk_product_category
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'product' AND constraint_name = 'fk_product_category');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE product ADD CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- product_sku.fk_sku_product
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'product_sku' AND constraint_name = 'fk_sku_product');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE product_sku ADD CONSTRAINT fk_sku_product FOREIGN KEY (product_id) REFERENCES product (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- product_sku.fk_sku_merchant
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'product_sku' AND constraint_name = 'fk_sku_merchant');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE product_sku ADD CONSTRAINT fk_sku_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- product_virtual_card.fk_card_product
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'product_virtual_card' AND constraint_name = 'fk_card_product');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE product_virtual_card ADD CONSTRAINT fk_card_product FOREIGN KEY (product_id) REFERENCES product (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- product_virtual_card.fk_card_sku
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'product_virtual_card' AND constraint_name = 'fk_card_sku');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE product_virtual_card ADD CONSTRAINT fk_card_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- product_virtual_card.fk_card_merchant
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'product_virtual_card' AND constraint_name = 'fk_card_merchant');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE product_virtual_card ADD CONSTRAINT fk_card_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- card_access_audit.fk_audit_merchant
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'card_access_audit' AND constraint_name = 'fk_audit_merchant');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE card_access_audit ADD CONSTRAINT fk_audit_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- card_access_audit.fk_audit_sku
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'card_access_audit' AND constraint_name = 'fk_audit_sku');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE card_access_audit ADD CONSTRAINT fk_audit_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- card_access_audit.fk_audit_actor
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'card_access_audit' AND constraint_name = 'fk_audit_actor');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE card_access_audit ADD CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- trade_order.fk_order_merchant
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND constraint_name = 'fk_order_merchant');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE trade_order ADD CONSTRAINT fk_order_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- trade_order_item.fk_item_order
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'trade_order_item' AND constraint_name = 'fk_item_order');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE trade_order_item ADD CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES trade_order (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- trade_order_item.fk_item_product
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'trade_order_item' AND constraint_name = 'fk_item_product');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE trade_order_item ADD CONSTRAINT fk_item_product FOREIGN KEY (product_id) REFERENCES product (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- trade_order_item.fk_item_sku
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'trade_order_item' AND constraint_name = 'fk_item_sku');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE trade_order_item ADD CONSTRAINT fk_item_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- billing_statement.fk_statement_merchant
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'billing_statement' AND constraint_name = 'fk_statement_merchant');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE billing_statement ADD CONSTRAINT fk_statement_merchant FOREIGN KEY (shop_id) REFERENCES merchant_store (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- complaint.fk_complaint_order
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'complaint' AND constraint_name = 'fk_complaint_order');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE complaint ADD CONSTRAINT fk_complaint_order FOREIGN KEY (order_id) REFERENCES trade_order (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- complaint.fk_complaint_merchant
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'complaint' AND constraint_name = 'fk_complaint_merchant');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE complaint ADD CONSTRAINT fk_complaint_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- inventory_reservation.fk_reservation_order
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'inventory_reservation' AND constraint_name = 'fk_reservation_order');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE inventory_reservation ADD CONSTRAINT fk_reservation_order FOREIGN KEY (order_id) REFERENCES trade_order (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- inventory_reservation.fk_reservation_sku
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'inventory_reservation' AND constraint_name = 'fk_reservation_sku');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE inventory_reservation ADD CONSTRAINT fk_reservation_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- inventory_reservation.fk_reservation_product
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'inventory_reservation' AND constraint_name = 'fk_reservation_product');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE inventory_reservation ADD CONSTRAINT fk_reservation_product FOREIGN KEY (product_id) REFERENCES product (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- inventory_reservation.fk_reservation_merchant
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'inventory_reservation' AND constraint_name = 'fk_reservation_merchant');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE inventory_reservation ADD CONSTRAINT fk_reservation_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- inventory_adjustment.fk_adjustment_merchant
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'inventory_adjustment' AND constraint_name = 'fk_adjustment_merchant');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE inventory_adjustment ADD CONSTRAINT fk_adjustment_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- inventory_adjustment.fk_adjustment_product
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'inventory_adjustment' AND constraint_name = 'fk_adjustment_product');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE inventory_adjustment ADD CONSTRAINT fk_adjustment_product FOREIGN KEY (product_id) REFERENCES product (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- inventory_adjustment.fk_adjustment_sku
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'inventory_adjustment' AND constraint_name = 'fk_adjustment_sku');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE inventory_adjustment ADD CONSTRAINT fk_adjustment_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- inventory_adjustment.fk_adjustment_actor
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'inventory_adjustment' AND constraint_name = 'fk_adjustment_actor');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE inventory_adjustment ADD CONSTRAINT fk_adjustment_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- virtual_card_void.fk_card_void_card
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'virtual_card_void' AND constraint_name = 'fk_card_void_card');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE virtual_card_void ADD CONSTRAINT fk_card_void_card FOREIGN KEY (card_id) REFERENCES product_virtual_card (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- virtual_card_void.fk_card_void_merchant
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'virtual_card_void' AND constraint_name = 'fk_card_void_merchant');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE virtual_card_void ADD CONSTRAINT fk_card_void_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- virtual_card_void.fk_card_void_product
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'virtual_card_void' AND constraint_name = 'fk_card_void_product');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE virtual_card_void ADD CONSTRAINT fk_card_void_product FOREIGN KEY (product_id) REFERENCES product (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- virtual_card_void.fk_card_void_sku
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'virtual_card_void' AND constraint_name = 'fk_card_void_sku');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE virtual_card_void ADD CONSTRAINT fk_card_void_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- virtual_card_void.fk_card_void_actor
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'virtual_card_void' AND constraint_name = 'fk_card_void_actor');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE virtual_card_void ADD CONSTRAINT fk_card_void_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- card_import_batch.fk_card_batch_merchant
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'card_import_batch' AND constraint_name = 'fk_card_batch_merchant');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE card_import_batch ADD CONSTRAINT fk_card_batch_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- card_import_batch.fk_card_batch_product
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'card_import_batch' AND constraint_name = 'fk_card_batch_product');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE card_import_batch ADD CONSTRAINT fk_card_batch_product FOREIGN KEY (product_id) REFERENCES product (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- card_import_batch.fk_card_batch_sku
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'card_import_batch' AND constraint_name = 'fk_card_batch_sku');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE card_import_batch ADD CONSTRAINT fk_card_batch_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- card_import_batch.fk_card_batch_actor
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'card_import_batch' AND constraint_name = 'fk_card_batch_actor');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE card_import_batch ADD CONSTRAINT fk_card_batch_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- payment_event.fk_payment_event_merchant
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'payment_event' AND constraint_name = 'fk_payment_event_merchant');
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE payment_event ADD CONSTRAINT fk_payment_event_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
