-- 文件：schema.sql
-- 用途：开发期唯一运行时结构入口，完整创建全部业务表、主键/唯一键/非空/外键/默认值/检查约束与索引，并写入商品分类参考数据；演示账号/商品/卡密见demo-seed.sql。
-- 维护注意：
--  1. 本脚本面向全新开发库，不包含兼容旧库的放宽约束、列升级或历史业务数据回填；开发重建会丢失旧数据。
--  2. 结构变更同步到本文件；需要作用到保留数据的开发库时，只在db/migrations/V*.sql中写幂等增量DDL，不得回填历史业务数据。
--  3. 只操作当前连接库，不包含删库/删表语句；生产不得依赖演示账号，且不得导入demo-seed.sql。
-- ========================================================
-- Shop B2B2C 全局全量初始化数据库脚本 (Single Source of Truth)
-- 适用: 开发期启动自愈 / 删库一键重建 (Drop & Re-create)
-- 位置: src/main/resources/db/schema.sql
-- ========================================================

-- 连接库由数据源URL或mysql命令指定；本脚本不创建或切换数据库。

SET FOREIGN_KEY_CHECKS = 0;

-- 1. 商户店铺表
CREATE TABLE IF NOT EXISTS `merchant_store` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '店铺主键ID',
  `store_code` VARCHAR(64) NOT NULL COMMENT '店铺唯一编码 (字母数字)',
  `store_name` VARCHAR(128) NOT NULL COMMENT '店铺名称',
  `logo_url` VARCHAR(255) DEFAULT NULL COMMENT '店铺Logo',
  `notice` VARCHAR(512) DEFAULT NULL COMMENT '店铺首页公告',
  `merchant_type` TINYINT NOT NULL DEFAULT 1 COMMENT '商户类型: 1-个人, 2-个体工商户, 3-企业',
  `owner_name` VARCHAR(64) NOT NULL COMMENT '经营者姓名 / 法人姓名',
  `id_card_number` VARCHAR(32) NOT NULL COMMENT '经营者身份证号码 (脱敏)',
  `contact_phone` VARCHAR(32) NOT NULL COMMENT '联系手机号',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '店铺状态: 0-待审, 1-正常营业, 2-打烊, 3-已封禁',
  `commission_rate` DECIMAL(5,2) NOT NULL DEFAULT 3.00 COMMENT '平台抽成比例(%)；T39 起仅作展示镜像，计费唯一来源为 merchant_fee_policy',
  `monthly_fee` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '月度技术服务费(元/月，前期=0)',
  `fee_free_until` DATETIME NULL COMMENT '免费期截止日期',
  `fee_status` VARCHAR(32) NOT NULL DEFAULT 'NORMAL' COMMENT '服务费配置标记: NORMAL, TRIAL, OVERDUE, EXEMPTED, ARREARS; 不代表自动扣费或停服',
  `payment_channel_type` VARCHAR(16) NOT NULL DEFAULT 'C' COMMENT '支付模式: B(商户自主直收), C(Mock沙箱), A(平台电商收付通); T32 起仅作迁移来源',
  `pay_channel` VARCHAR(32) NOT NULL DEFAULT 'WECHAT' COMMENT 'T32 新增：真实支付渠道 WECHAT/ALIPAY，与 A/B/C 收款模式解耦',
  `custom_pay_qrcode` VARCHAR(512) NULL COMMENT '商户自主微信/支付宝收款码URL',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_store_code` (`store_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户店铺主表';

-- 2. 微信/支付宝官方二级商户进件账户表
CREATE TABLE IF NOT EXISTS `merchant_payment_account` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '关联店铺ID',
  `channel` VARCHAR(32) NOT NULL COMMENT '渠道: WECHAT(电商收付通), ALIPAY(直付通)',
  `sub_mchid` VARCHAR(64) NOT NULL COMMENT '官方分配的二级商户号',
  `bank_account_name` VARCHAR(64) NOT NULL COMMENT '结算开户人姓名',
  `bank_account_number` VARCHAR(64) NOT NULL COMMENT '结算银行卡号',
  `bank_name` VARCHAR(128) NOT NULL COMMENT '开户银行支行名称',
  `apply_status` VARCHAR(32) NOT NULL DEFAULT 'PASSED' COMMENT '进件状态: AUDITING-审核中, PASSED-已开通, REJECTED-已驳回；阶段一不写假PASSED',
  `reject_reason` VARCHAR(255) DEFAULT NULL COMMENT '驳回原因',
  `settlement_account_id` BIGINT UNSIGNED NULL COMMENT 'T39 阶段二预留：关联 merchant_settlement_account，阶段一先不写值',
  `binding_status` VARCHAR(24) NOT NULL DEFAULT 'UNBOUND' COMMENT 'T39 阶段二预留：UNBOUND/BINDING/BOUND/REJECTED/DISABLED，只允许渠道真实结果推进',
  `channel_merchant_id` VARCHAR(64) NULL COMMENT 'T39 阶段二预留：渠道真实商户号返回值',
  `bound_at` DATETIME NULL COMMENT 'T39 阶段二预留：绑定完成时间',
  `effective_from` DATETIME NULL COMMENT 'T39 阶段二预留：绑定生效时间',
  `effective_to` DATETIME NULL COMMENT 'T39 阶段二预留：绑定失效时间',
  `raw_result` TEXT NULL COMMENT 'T39 阶段二预留：渠道原始返回，供人工核对',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_channel` (`merchant_id`, `channel`),
  CONSTRAINT fk_payment_account_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户二级收付通进件账户表';

-- 3. 系统后台用户表 (RBAC 商家隔离)
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '所属店铺ID (平台超管为NULL)',
  `username` VARCHAR(64) NOT NULL COMMENT '登录账号/手机号',
  `password` VARCHAR(128) NOT NULL COMMENT '密码 (BCrypt加密)',
  `real_name` VARCHAR(64) DEFAULT NULL COMMENT '真实姓名',
  `role_code` VARCHAR(32) NOT NULL COMMENT '角色: SUPERADMIN-平台超管, MERCHANT_ADMIN-店长, MERCHANT_STAFF-员工',
  `is_active` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 1-启用, 0-禁用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  CONSTRAINT fk_user_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统管理用户表';

-- 4. 商品分类表
CREATE TABLE IF NOT EXISTS `category` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `name` VARCHAR(64) NOT NULL,
  `icon` VARCHAR(255) DEFAULT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

-- 5. 商品主表
CREATE TABLE IF NOT EXISTS `product` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID',
  `category_id` BIGINT UNSIGNED NOT NULL COMMENT '所属分类ID',
  `title` VARCHAR(255) NOT NULL COMMENT '商品标题',
  `sub_title` VARCHAR(255) DEFAULT NULL COMMENT '副标题/卖点',
  `goods_type` TINYINT NOT NULL DEFAULT 1 COMMENT '类型: 1-实物物流发货, 2-自动卡密, 3-定制服务',
  `main_image` VARCHAR(255) NOT NULL COMMENT '主图封面URL',
  `detail_images` JSON DEFAULT NULL COMMENT '轮播与富文本内容',
  `service_desc` TEXT NULL COMMENT '线上服务交付说明及不可逆防退款强警示',
  `min_price` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '最低显示售价',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 0-已下架, 1-在售, 2-违规下架',
  `sort_order` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_merchant_product` (`merchant_id`, `status`),
  KEY `idx_category` (`category_id`),
  CONSTRAINT fk_product_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`),
  CONSTRAINT fk_product_category FOREIGN KEY (`category_id`) REFERENCES `category` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品主表';

-- 6. 商品规格 SKU 表
CREATE TABLE IF NOT EXISTS `product_sku` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `product_id` BIGINT UNSIGNED NOT NULL COMMENT '关联商品ID',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID',
  `sku_code` VARCHAR(64) NOT NULL COMMENT 'SKU编码',
  `spec_data` JSON NOT NULL COMMENT '规格KV JSON (如: {"时长":"1个月 VIP"})',
  `price` DECIMAL(10,2) NOT NULL COMMENT '售价',
  `stock_quantity` INT NOT NULL DEFAULT 0 COMMENT '可用物理库存',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_sku_code` (`merchant_id`, `sku_code`),
  KEY `idx_product_id` (`product_id`),
  CONSTRAINT `chk_sku_stock` CHECK (`stock_quantity` >= 0),
  CONSTRAINT `chk_sku_price` CHECK (`price` >= 0.00),
  CONSTRAINT fk_sku_product FOREIGN KEY (`product_id`) REFERENCES `product` (`id`),
  CONSTRAINT fk_sku_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品规格SKU表';

-- 7. 虚拟卡密库存表 (R05/R25: 分级加密存储与SHA-256指纹唯一去重)
CREATE TABLE IF NOT EXISTS `product_virtual_card` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `product_id` BIGINT UNSIGNED NOT NULL,
  `sku_id` BIGINT UNSIGNED NOT NULL,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `card_fingerprint` CHAR(64) DEFAULT NULL COMMENT '卡密SHA-256去重指纹 (同店铺唯一)',
  `fingerprint_unavailable` TINYINT NOT NULL DEFAULT 0 COMMENT '指纹不可用标记: 1-缺少明文或解密失败，导入去重时跳过并计数',
  `card_content` TEXT NOT NULL COMMENT '卡密明细文本 (AES-256-GCM加密载荷)',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0-未售出, 1-已售出核销, 2-作废',
  `order_sn` VARCHAR(64) DEFAULT NULL COMMENT '售出关联订单号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_fingerprint` (`merchant_id`, `card_fingerprint`),
  KEY `idx_sku_status` (`sku_id`, `status`),
  CONSTRAINT fk_card_product FOREIGN KEY (`product_id`) REFERENCES `product` (`id`),
  CONSTRAINT fk_card_sku FOREIGN KEY (`sku_id`) REFERENCES `product_sku` (`id`),
  CONSTRAINT fk_card_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='虚拟商品卡密库存表';

-- 7.1 卡密明文访问审计表 (T15: 导出等明文外带操作留痕；只追加，不保存卡密正文/指纹)
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
  KEY `idx_audit_sku_created` (`sku_id`, `created_at`),
  CONSTRAINT fk_audit_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`),
  CONSTRAINT fk_audit_sku FOREIGN KEY (`sku_id`) REFERENCES `product_sku` (`id`),
  CONSTRAINT fk_audit_actor FOREIGN KEY (`actor_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卡密明文访问审计表';

-- 8. 交易订单主表 (含两段式代付字段)
CREATE TABLE IF NOT EXISTS `trade_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_sn` VARCHAR(64) NOT NULL COMMENT '全局订单号',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID',
  `buyer_contact` VARCHAR(64) NOT NULL COMMENT '买家手机号或邮箱',
  `goods_type` TINYINT NOT NULL DEFAULT 1 COMMENT '商品类型: 1-实物, 2-自动卡密, 3-定制服务',
  `total_amount` DECIMAL(10,2) NOT NULL COMMENT '订单总金额',
  `pay_amount` DECIMAL(10,2) NOT NULL COMMENT '实付金额',
  `commission_rate` DECIMAL(5,2) NOT NULL DEFAULT 3.00 COMMENT '抽佣比率(%)',
  `commission_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '平台抽佣服务费',
  `merchant_settle_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '商户应收净货款',
  `refunded_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T33 已退款商品金额（含渠道处理中预占）',
  `fee_refunded_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T33 已冲正/预占技术服务费（含处理中预占）',
  `fee_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'T33/T34 服务费状态 PENDING-未收费, ACCRUED-已入账, ARREARS-欠费, PARTIAL_REVERSED-部分冲正, REVERSED-全额冲正',
  `goods_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 计费基数：商品成交额（不含运费/税费）',
  `merchant_discount_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 商家优惠金额',
  `platform_discount_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 平台优惠金额',
  `shipping_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 运费，不计入服务费基数',
  `order_tax_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 订单税额（价税分离）',
  `fee_policy_id` BIGINT NULL COMMENT 'T34 订单级费率快照来源的政策ID；T39 起写 merchant_fee_policy.id，历史订单可为空',
  `fee_rate_bps` INT NOT NULL DEFAULT 0 COMMENT 'T34 订单级费率快照，万分比；300 表示 3%',
  `fee_rate_type` VARCHAR(24) NOT NULL DEFAULT 'NORMAL' COMMENT 'T34 费率类型 NORMAL/PROMOTION；PROMOTION 表示推广期 0 费率',
  `fee_base_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 服务费计费基数=商品成交额-商家优惠',
  `fee_standard_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 标准服务费=基数×费率',
  `fee_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 应收服务费（推广期可为0）',
  `fee_waived_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 减免金额，推广期等于标准服务费',
  `fee_net_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 服务费不含税额',
  `fee_tax_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 服务费税额',
  `fee_tax_rate` DECIMAL(7,4) NOT NULL DEFAULT 0.0000 COMMENT 'T34 服务费税率快照，如 0.0600',
  `order_status` VARCHAR(32) NOT NULL DEFAULT 'UNPAID' COMMENT 'UNPAID, PAID, SHIPPED, COMPLETED, REFUNDED, CLOSED',
  `sharing_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSING, FINISHED, FAILED',
  `payment_channel` VARCHAR(32) NOT NULL DEFAULT 'WECHAT' COMMENT 'WECHAT, ALIPAY; T32 起只写真实渠道，不再写 A/B/C',
  `collection_mode` VARCHAR(24) NOT NULL DEFAULT 'MERCHANT_DIRECT' COMMENT 'T32 新增：MERCHANT_DIRECT/PLATFORM_COLLECT，阶段一固定商家直收',
  `pay_channel` VARCHAR(32) NOT NULL DEFAULT 'WECHAT' COMMENT 'T32 新增：真实支付渠道快照 WECHAT/ALIPAY',
  `pay_trade_no` VARCHAR(128) DEFAULT NULL COMMENT '第三方支付交易流水号',
  `platform_trade_no` VARCHAR(128) NULL COMMENT 'T40 阶段二预留：平台代收渠道流水号；阶段一为空，不得用商家直收流水回填',
  `logistics_company` VARCHAR(64) DEFAULT NULL COMMENT '快递物流公司',
  `tracking_number` VARCHAR(128) DEFAULT NULL COMMENT '快递单号',
  `delivery_time` DATETIME DEFAULT NULL COMMENT '发货时间',
  `finish_time` DATETIME DEFAULT NULL COMMENT '完成时间',
  `query_token` VARCHAR(64) NOT NULL COMMENT '买家免密查单安全凭证',
  `peer_pay_token` VARCHAR(64) NULL COMMENT '代付防撞唯一短码令牌',
  `peer_pay_type` TINYINT NOT NULL DEFAULT 0 COMMENT '代付类型: 0-自主支付, 1-买家求助代付, 2-卖家代客推单',
  `peer_payer_id` VARCHAR(128) DEFAULT NULL COMMENT '实际代付付款人标识 (OpenID/UID/流水号)',
  `peer_status` VARCHAR(32) NOT NULL DEFAULT 'INIT' COMMENT 'INIT, RESERVED_LOCK, PAID, EXPIRED, CANCELLED',
  `peer_message` VARCHAR(255) DEFAULT NULL COMMENT '买家个性求助留言',
  `poster_url` MEDIUMTEXT DEFAULT NULL COMMENT '生成的带参代付海报图片访问路径或Base64',
  `service_voucher` TEXT DEFAULT NULL COMMENT '线上服务交付凭证 (卡密/网盘/交付报告)',
  `lock_expire_time` DATETIME DEFAULT NULL COMMENT '两段式库存预扣到期时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_sn` (`order_sn`),
  UNIQUE KEY `uk_peer_pay_token` (`peer_pay_token`),
  KEY `idx_merchant_order` (`merchant_id`, `order_status`),
  KEY `idx_contact` (`buyer_contact`),
  KEY `idx_query_token` (`query_token`),
  KEY `idx_order_created` (`created_at`, `id`),
  KEY `idx_merchant_created` (`merchant_id`, `created_at`, `id`),
  KEY `idx_order_expiry` (`order_status`, `lock_expire_time`, `id`),
  CONSTRAINT `chk_order_amounts` CHECK (`total_amount` >= 0.00 AND `pay_amount` >= 0.00),
  CONSTRAINT fk_order_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易订单主表';

-- 9. 订单商品明细表
CREATE TABLE IF NOT EXISTS `trade_order_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `order_sn` VARCHAR(64) NOT NULL,
  `product_id` BIGINT UNSIGNED NOT NULL,
  `sku_id` BIGINT UNSIGNED NOT NULL,
  `sku_code` VARCHAR(64) NOT NULL,
  `product_title` VARCHAR(255) NOT NULL,
  `spec_info` VARCHAR(255) DEFAULT NULL,
  `price` DECIMAL(10,2) NOT NULL,
  `quantity` INT NOT NULL,
  `subtotal` DECIMAL(10,2) NOT NULL,
  `card_details` TEXT DEFAULT NULL COMMENT '核销卡密文本快照',
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_order_sn` (`order_sn`),
  CONSTRAINT `chk_item_quantity` CHECK (`quantity` > 0 AND `price` >= 0.00 AND `subtotal` >= 0.00),
  CONSTRAINT fk_item_order FOREIGN KEY (`order_id`) REFERENCES `trade_order` (`id`),
  CONSTRAINT fk_item_product FOREIGN KEY (`product_id`) REFERENCES `product` (`id`),
  CONSTRAINT fk_item_sku FOREIGN KEY (`sku_id`) REFERENCES `product_sku` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- 9.1 订单退款与重复付款补偿记录表（T33）
CREATE TABLE IF NOT EXISTS `trade_refund` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `refund_no` VARCHAR(64) NOT NULL COMMENT '平台退款单号',
  `order_id` BIGINT UNSIGNED NOT NULL,
  `order_sn` VARCHAR(64) NOT NULL,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `collection_mode` VARCHAR(24) NOT NULL DEFAULT 'MERCHANT_DIRECT' COMMENT 'MERCHANT_DIRECT/PLATFORM_COLLECT',
  `channel` VARCHAR(32) NOT NULL COMMENT 'WECHAT/ALIPAY',
  `source` VARCHAR(24) NOT NULL DEFAULT 'ORDER' COMMENT 'ORDER=商品退款; DUPLICATE_PAYMENT=重复付款补偿',
  `source_event_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '重复付款事件ID；普通退款为空',
  `idempotency_key` VARCHAR(64) NOT NULL COMMENT '同一订单内的退款幂等键',
  `refund_amount` DECIMAL(10,2) NOT NULL COMMENT '本次退款金额',
  `fee_refund_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '本次按订单快照比例冲正的服务费',
  `fee_rounding_adjustment` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '尾差归属：本次退款吸收的服务费舍入差，正数表示比比例值多冲正、负数表示少冲正',
  `status` VARCHAR(24) NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING/SUCCESS/FAILED',
  `channel_trade_no` VARCHAR(128) DEFAULT NULL COMMENT '被退款的渠道原流水号',
  `channel_refund_no` VARCHAR(128) DEFAULT NULL COMMENT '渠道退款流水号',
  `reason` VARCHAR(256) DEFAULT NULL,
  `failure_reason` VARCHAR(256) DEFAULT NULL,
  `requested_by` BIGINT UNSIGNED DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  UNIQUE KEY `uk_refund_order_key` (`order_id`, `idempotency_key`),
  UNIQUE KEY `uk_refund_source_event` (`source_event_id`),
  KEY `idx_refund_order` (`order_id`),
  KEY `idx_refund_status` (`status`),
  KEY `idx_refund_merchant` (`merchant_id`, `created_at`),
  CONSTRAINT `fk_refund_order` FOREIGN KEY (`order_id`) REFERENCES `trade_order` (`id`),
  CONSTRAINT `chk_refund_amount` CHECK (`refund_amount` > 0.00 AND `fee_refund_amount` >= 0.00)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单退款与重复付款补偿记录';

-- 10. 代付成功页【我也想开店】平台运营广告配置表
CREATE TABLE IF NOT EXISTS `platform_ad_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `ad_location` VARCHAR(64) NOT NULL DEFAULT 'PAYER_SUCCESS' COMMENT '广告位置标识',
  `ad_title` VARCHAR(128) NOT NULL COMMENT '主标题，如：我也想开店',
  `ad_sub_title` VARCHAR(255) DEFAULT NULL COMMENT '副标题，如：0元免照开店，像TA一样私域极速推单收款',
  `banner_url` VARCHAR(512) DEFAULT NULL COMMENT '宣传海报图/横幅URL',
  `target_type` VARCHAR(32) NOT NULL DEFAULT 'MERCHANT_APPLY' COMMENT '跳转类型: MERCHANT_APPLY, CUSTOM_URL',
  `target_url` VARCHAR(512) DEFAULT NULL COMMENT '跳转目标链接或动作参数',
  `is_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用: 1-启用, 0-禁用',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台运营引流广告配置表';

-- 11. 商户日/月财务对账账单表
CREATE TABLE IF NOT EXISTS `billing_statement` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `shop_id` BIGINT UNSIGNED NOT NULL COMMENT '商户店铺ID',
  `store_name` VARCHAR(128) NOT NULL COMMENT '商户店铺名称',
  `period_date` DATE NOT NULL COMMENT '对账归属日期 (YYYY-MM-DD)',
  `period_type` VARCHAR(16) NOT NULL DEFAULT 'DAILY' COMMENT '周期类型: DAILY, MONTHLY',
  `gmv` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '当日/月总交易GMV',
  `peer_pay_gmv` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '代付渠道成交额',
  `direct_pay_gmv` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '自主支付成交额',
  `orders_cnt` INT NOT NULL DEFAULT 0 COMMENT '有效订单笔数',
  `refund_amt` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '退款总金额',
  `fee_amt` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '平台软件技术服务费扣收额',
  `status` VARCHAR(32) NOT NULL DEFAULT 'SETTLED' COMMENT '账单状态: PENDING, SETTLED, DISPUTED',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_shop_period` (`shop_id`, `period_date`, `period_type`),
  CONSTRAINT fk_statement_merchant FOREIGN KEY (`shop_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户财务对账账单汇总表';

-- 12. 买家维权投诉与超管仲裁工单表
CREATE TABLE IF NOT EXISTS `complaint` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '关联订单ID',
  `order_sn` VARCHAR(64) NOT NULL COMMENT '关联订单编号',
  `buyer_id` VARCHAR(64) NOT NULL COMMENT '买家唯一标识 (手机号/UID)',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '被投诉商户ID',
  `complaint_type` VARCHAR(64) NOT NULL COMMENT '投诉类型: QUALITY, FAKE, SERVICE_NOT_DELIVERED, OTHER',
  `content` TEXT NOT NULL COMMENT '买家投诉详细说明',
  `evidence_images` JSON DEFAULT NULL COMMENT '买家举证凭证图数组',
  `merchant_reply` TEXT DEFAULT NULL COMMENT '商家答辩说明与举证',
  `merchant_reply_time` DATETIME DEFAULT NULL COMMENT '商家答复时间',
  `admin_arbitration` TEXT DEFAULT NULL COMMENT '平台超管官方仲裁结论',
  `admin_arbitration_time` DATETIME DEFAULT NULL COMMENT '超管仲裁时间',
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '工单状态: PENDING, MERCHANT_REPLIED, ARBITRATING, RESOLVED, REJECTED',
  `refund_decision` VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT '退款判定: NONE, FULL_REFUND, PARTIAL_REFUND',
  `refund_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '判定退款金额',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_order_id` (`order_id`),
  KEY `idx_merchant_id` (`merchant_id`),
  KEY `idx_status` (`status`),
  CONSTRAINT fk_complaint_order FOREIGN KEY (`order_id`) REFERENCES `trade_order` (`id`),
  CONSTRAINT fk_complaint_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='买家维权投诉与平台官方仲裁表';

-- 13. 库存预占流水；索引随建表创建，避免启动时重复 CREATE INDEX。
-- T11：到期游标索引补齐 order_id，支撑 status + expires_at + order_id 的稳定批量扫描。
CREATE TABLE IF NOT EXISTS inventory_reservation (
  order_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  sku_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  merchant_id BIGINT UNSIGNED NOT NULL,
  quantity INT NOT NULL,
  owner_token VARCHAR(64) NOT NULL,
  attempt_key VARCHAR(64) NULL,
  reissue_count INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL,
  expires_at DATETIME NOT NULL,
  KEY idx_reservation_expiry (status, expires_at, order_id),
  CONSTRAINT chk_reservation_quantity CHECK (quantity > 0),
  CONSTRAINT fk_reservation_order FOREIGN KEY (order_id) REFERENCES trade_order (id),
  CONSTRAINT fk_reservation_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),
  CONSTRAINT fk_reservation_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT fk_reservation_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 14. 业务幂等摘要及原响应快照（保留原表名；建单与人工库存调整用不同作用域）
-- T27/Q18-B：幂等轻量映射表。保留 30 天（IdempotencyPurgeJob 清理）；response_json 由应用脱敏后写入，
-- 不含 queryToken/buyerContact/posterBase64，重放时从 trade_order 回填；未完成占位（response_json IS NULL）不清理。
CREATE TABLE IF NOT EXISTS order_idempotency (
  request_key VARCHAR(64) NOT NULL PRIMARY KEY,
  request_hash VARCHAR(64) NOT NULL,
  response_json LONGTEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_idempotency_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET FOREIGN_KEY_CHECKS = 1;

-- ========================================================
-- 平台运行参考数据：商品分类（非演示账号，不含口令/身份证/银行卡/卡密）
-- 演示账号、店铺、商品、卡密与广告种子已移至 db/demo-seed.sql，
-- 仅在显式开发/演示开关（spring.sql.init.data-locations）下加载，生产 profile 不执行。
-- ========================================================
INSERT IGNORE INTO `category` (`id`, `parent_id`, `name`, `icon`, `sort_order`) VALUES
(1, 0, '数字权益 / 充值卡券', 'tickets', 1),
(2, 0, '游戏点卡 / CDKey', 'gamepad', 2),
(3, 0, '数码周边 / 现货硬件', 'laptop', 3),
(4, 0, '3C 数码 / 智能硬件', 'laptop', 4),
(5, 0, '服装鞋包 / 时尚潮流', 'shirt', 5),
(6, 0, '家居日用 / 百货批发', 'home', 6),
(7, 0, '食品生鲜 / 休闲零食', 'coffee', 7);

-- 15. 人工库存调整记录：与SKU增减、业务幂等结果同事务，只追加，不保存原始重试键。
CREATE TABLE IF NOT EXISTS inventory_adjustment (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  merchant_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  actor_id BIGINT UNSIGNED NOT NULL,
  request_key VARCHAR(64) NOT NULL,
  delta INT NOT NULL,
  before_stock INT NOT NULL,
  after_stock INT NOT NULL,
  reason VARCHAR(255) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_inventory_adjustment_request (request_key),
  KEY idx_inventory_adjustment_merchant (merchant_id,id),
  KEY idx_inventory_adjustment_sku (merchant_id,sku_id,id),
  CONSTRAINT chk_adjustment_delta CHECK (delta <> 0),
  CONSTRAINT chk_adjustment_stock CHECK (before_stock >= 0 AND after_stock >= 0 AND after_stock = before_stock + delta),
  CONSTRAINT fk_adjustment_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id),
  CONSTRAINT fk_adjustment_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT fk_adjustment_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),
  CONSTRAINT fk_adjustment_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工可售库存调整记录，不含全部库存事件';

-- 16. 卡密安全作废记录：以卡ID自然幂等，与状态迁移和扣减库存同事务。
CREATE TABLE IF NOT EXISTS virtual_card_void (
  card_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  merchant_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  actor_id BIGINT UNSIGNED NOT NULL,
  reason VARCHAR(255) NOT NULL,
  before_stock INT NOT NULL,
  after_stock INT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_card_void_merchant (merchant_id,sku_id,card_id),
  CONSTRAINT chk_card_void_stock CHECK (before_stock >= 1 AND after_stock = before_stock - 1),
  CONSTRAINT fk_card_void_card FOREIGN KEY (card_id) REFERENCES product_virtual_card (id),
  CONSTRAINT fk_card_void_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id),
  CONSTRAINT fk_card_void_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT fk_card_void_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),
  CONSTRAINT fk_card_void_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卡密作废操作记录，不含卡密内容';

-- 17/18. 成功卡密导入批次及成员：不保存卡密正文，不回填旧记录。
CREATE TABLE IF NOT EXISTS card_import_batch (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  merchant_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  actor_id BIGINT UNSIGNED NOT NULL,
  imported_count INT NOT NULL,
  before_stock INT NOT NULL,
  after_stock INT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_card_batch_merchant (merchant_id,id),
  KEY idx_card_batch_sku (merchant_id,sku_id,id),
  CONSTRAINT chk_card_batch_quantity CHECK (imported_count BETWEEN 1 AND 1000 AND before_stock >= 0 AND after_stock = before_stock + imported_count),
  CONSTRAINT fk_card_batch_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id),
  CONSTRAINT fk_card_batch_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT fk_card_batch_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),
  CONSTRAINT fk_card_batch_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成功卡密导入批次';
CREATE TABLE IF NOT EXISTS card_import_item (
  card_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  batch_id BIGINT UNSIGNED NOT NULL,
  KEY idx_card_import_item_batch (batch_id,card_id),
  CONSTRAINT fk_card_import_item_batch FOREIGN KEY (batch_id) REFERENCES card_import_batch(id),
  CONSTRAINT fk_card_import_item_card FOREIGN KEY (card_id) REFERENCES product_virtual_card(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入批次成员，不复制卡密正文';

-- 19. 支付渠道到账事件流水表 (解耦外部资金事实与本地履约)
CREATE TABLE IF NOT EXISTS `payment_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `channel` VARCHAR(32) NOT NULL COMMENT '支付渠道: WECHAT, ALIPAY, STRIPE, MOCK',
  `pay_trade_no` VARCHAR(128) NOT NULL COMMENT '第三方支付交易流水号',
  `order_sn` VARCHAR(64) NOT NULL COMMENT '关联内部订单号',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '商户ID',
  `collection_mode` VARCHAR(24) NOT NULL DEFAULT 'MERCHANT_DIRECT' COMMENT 'T32 新增：MERCHANT_DIRECT/PLATFORM_COLLECT',
  `channel_merchant_id` VARCHAR(64) NULL COMMENT 'T32 新增：渠道侧商户号（微信mchid/支付宝seller_id），与内部商户ID分离',
  `platform_trade_no` VARCHAR(128) NULL COMMENT 'T32 新增：阶段二平台流水；阶段一为空',
  `pay_amount` DECIMAL(10,2) NOT NULL COMMENT '实付金额',
  `currency` VARCHAR(16) NOT NULL DEFAULT 'CNY' COMMENT '交易币种',
  `payer_id` VARCHAR(128) NULL COMMENT '付款人渠道标识',
  `event_status` VARCHAR(32) NOT NULL COMMENT 'PROCESSING, SUCCESS, MANUAL_REVIEW, DUPLICATE_PAYMENT, AMOUNT_MISMATCH, CURRENCY_MISMATCH, MERCHANT_MISMATCH, LATE_PAYMENT, FULFILLMENT_FAILED',
  `error_message` VARCHAR(512) NULL COMMENT '异常描述',
  `raw_payload` TEXT NULL COMMENT '渠道回调原始报文快照',
  `reconcile_attempts` INT NOT NULL DEFAULT 0 COMMENT 'G34：陈旧 PROCESSING 渠道回查已重试次数',
  `next_reconcile_at` DATETIME NULL COMMENT 'G34：下次允许回查时间，NULL 表示立即可扫；超过上限转 MANUAL_REVIEW',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_channel_trade_no` (`channel`, `pay_trade_no`),
  KEY `idx_order_sn` (`order_sn`),
  KEY `idx_merchant_event` (`merchant_id`, `event_status`),
  KEY `idx_event_reconcile` (`event_status`, `next_reconcile_at`),
  CONSTRAINT fk_payment_event_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付渠道到账事件流水';

-- 20. 商家服务费账户（T34）：余额、欠费与信用额度；后续 T35 增列止收状态
CREATE TABLE IF NOT EXISTS `merchant_account` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '关联店铺ID',
  `balance_available` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '可用余额（元）',
  `balance_frozen` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '冻结余额，当前阶段不启用',
  `arrears` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '欠费累计金额',
  `credit_limit` DECIMAL(12,2) NOT NULL DEFAULT 500.00 COMMENT '欠费额度，默认500元，管理员可配',
  `grace_days` INT NULL COMMENT '欠费宽限期；允许7/21/30，NULL 表示管理员尚未确认',
  `arrears_due_at` DATETIME NULL COMMENT '当前欠费到期时间；NULL 表示无未结清欠费',
  `currency` VARCHAR(16) NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `collection_stopped` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'T35 是否已停止新单收款：1-停收，0-正常',
  `stop_reason` VARCHAR(32) NULL COMMENT 'T35 停收原因 ARREARS_LIMIT/GRACE_EXPIRED/MANUAL',
  `stopped_at` DATETIME NULL COMMENT 'T35 停收时间',
  `recovered_at` DATETIME NULL COMMENT 'T35 最近恢复收款时间',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_merchant` (`merchant_id`),
  CONSTRAINT fk_account_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`),
  CONSTRAINT `chk_account_amounts` CHECK (`balance_available` >= 0.00 AND `balance_frozen` >= 0.00 AND `arrears` >= 0.00 AND `credit_limit` >= 0.00)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家服务费账户';

-- 21. 服务费账本（T34）：所有余额/欠费变更的唯一入账流水；idempotency_key 保证同一订单/退款/充值只入账一次
CREATE TABLE IF NOT EXISTS `merchant_account_ledger` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID',
  `entry_type` VARCHAR(32) NOT NULL COMMENT 'SERVICE_FEE_CHARGE/SERVICE_FEE_ARREARS/SERVICE_FEE_WAIVED/REFUND_REVERSAL/RECHARGE/ADJUSTMENT',
  `direction` VARCHAR(8) NOT NULL COMMENT 'DEBIT 扣减余额或增加欠费；CREDIT 增加余额或减少欠费',
  `amount` DECIMAL(12,2) NOT NULL COMMENT '发生额，始终为非负数',
  `balance_after` DECIMAL(12,2) NOT NULL COMMENT '入账后可用余额',
  `arrears_after` DECIMAL(12,2) NOT NULL COMMENT '入账后欠费余额',
  `biz_type` VARCHAR(24) NOT NULL COMMENT 'ORDER/REFUND/RECHARGE/ADJUSTMENT',
  `biz_id` BIGINT NULL COMMENT '业务主键（订单ID/退款ID/充值单ID）',
  `order_sn` VARCHAR(64) NULL COMMENT '关联订单号',
  `refund_no` VARCHAR(64) NULL COMMENT '关联退款单号',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '入账幂等键，全表唯一',
  `remark` VARCHAR(255) NULL COMMENT '备注',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ledger_idempotency` (`idempotency_key`),
  KEY `idx_ledger_merchant_created` (`merchant_id`, `created_at`, `id`),
  KEY `idx_ledger_order` (`order_sn`),
  CONSTRAINT fk_ledger_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`),
  CONSTRAINT `chk_ledger_amount` CHECK (`amount` >= 0.00)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务费账本';

-- 21.1 服务费账本 append-only 兜底（财务基线 7）：账本只允许 INSERT，更正必须追加红冲分录，禁止 UPDATE/DELETE。
-- 说明：触发器是 MySQL 运行时约束；H2 单测夹具不复制触发器，真实 MySQL 行为须由用户在手工验证时确认。
DROP TRIGGER IF EXISTS `trg_ledger_no_update`;
CREATE TRIGGER `trg_ledger_no_update` BEFORE UPDATE ON `merchant_account_ledger`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'merchant_account_ledger is append-only: update forbidden';

DROP TRIGGER IF EXISTS `trg_ledger_no_delete`;
CREATE TRIGGER `trg_ledger_no_delete` BEFORE DELETE ON `merchant_account_ledger`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'merchant_account_ledger is append-only: delete forbidden';

-- 22. 商家充值单（T34）：线下转账 + 人工确认入账；凭证号唯一幂等
CREATE TABLE IF NOT EXISTS `merchant_recharge_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `recharge_no` VARCHAR(64) NOT NULL COMMENT '平台充值单号',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID',
  `requested_by` BIGINT NULL COMMENT '充值申请人用户ID；确认人必须与其不同（职责分离）',
  `amount` DECIMAL(12,2) NOT NULL COMMENT '申请充值金额',
  `confirmed_amount` DECIMAL(12,2) NULL COMMENT '人工确认到账金额',
  `voucher_no` VARCHAR(128) NOT NULL COMMENT '线下转账凭证号，全表唯一',
  `voucher_proof_url` VARCHAR(512) NULL COMMENT '转账凭证图片/文件地址',
  `payer_name` VARCHAR(128) NULL COMMENT '付款人名称',
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/REJECTED',
  `ledger_entry_id` BIGINT NULL COMMENT '确认入账对应的账本ID',
  `confirmed_by` BIGINT NULL COMMENT '确认人用户ID',
  `confirmed_at` DATETIME NULL COMMENT '确认时间',
  `approval_mode` VARCHAR(24) NULL COMMENT '复核模式：SAME_OPERATOR=单管理员开关关闭下同人复核；DUAL_APPROVAL=双人复核',
  `reject_reason` VARCHAR(255) NULL COMMENT '驳回原因',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recharge_no` (`recharge_no`),
  UNIQUE KEY `uk_recharge_voucher` (`voucher_no`),
  KEY `idx_recharge_merchant_status` (`merchant_id`, `status`),
  CONSTRAINT fk_recharge_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`),
  CONSTRAINT `chk_recharge_amount` CHECK (`amount` > 0.00)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家充值单';

-- 23. 结算批次（T34）：按账期每店每月一批，确认后不可重算
CREATE TABLE IF NOT EXISTS `merchant_settlement_batch` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `batch_no` VARCHAR(64) NOT NULL COMMENT '结算批次号',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID',
  `period_start` DATE NOT NULL COMMENT '账期开始日（含）',
  `period_end` DATE NOT NULL COMMENT '账期结束日（不含）',
  `settlement_type` VARCHAR(24) NOT NULL DEFAULT 'SERVICE_FEE' COMMENT '当前只做服务费月结',
  `gross_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '商品成交额合计',
  `refund_base_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '退款商品金额合计',
  `fee_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '应收服务费合计',
  `adjustment_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '退款冲正合计（负数红字）',
  `net_fee_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '净应收服务费',
  `paid_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '本期账本实收合计',
  `arrears_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '本期欠费合计',
  `status` VARCHAR(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED',
  `prepared_by` BIGINT NULL COMMENT '制单人/最近一次重算人用户ID；双人复核开关打开时确认人必须与其不同',
  `confirmed_by` BIGINT NULL COMMENT '确认人用户ID',
  `confirmed_at` DATETIME NULL COMMENT '确认时间',
  `approval_mode` VARCHAR(24) NULL COMMENT '复核模式：SAME_OPERATOR=单管理员开关关闭下同人复核；DUAL_APPROVAL=双人复核',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_period` (`merchant_id`, `period_start`, `period_end`, `settlement_type`),
  KEY `idx_settlement_status` (`status`, `period_start`),
  CONSTRAINT fk_settlement_batch_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算批次';

-- 24. 结算明细（T34）：逐笔可追订单/支付事件/退款
CREATE TABLE IF NOT EXISTS `merchant_settlement_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '结算批次ID',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID',
  `item_type` VARCHAR(24) NOT NULL COMMENT 'ORDER_FEE/REFUND_REVERSAL',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '订单ID',
  `order_sn` VARCHAR(64) NOT NULL COMMENT '订单号',
  `goods_type` TINYINT NOT NULL DEFAULT 1 COMMENT '商品类型快照：1-实物,2-卡密,3-定制服务',
  `payment_event_id` BIGINT NULL COMMENT '支付事件ID',
  `refund_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '退款ID；非退款明细为0',
  `refund_no` VARCHAR(64) NULL COMMENT '退款单号；非退款明细为空',
  `billable_base` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '计费基数',
  `fee_rate_bps` INT NOT NULL DEFAULT 0 COMMENT '订单费率快照（万分比）',
  `fee_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '应收服务费；退款明细为冲正金额（负数）',
  `fee_refunded_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '已冲正服务费',
  `net_fee` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '明细净额',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_item` (`batch_id`, `item_type`, `order_id`, `refund_id`),
  KEY `idx_settlement_item_order` (`order_sn`),
  CONSTRAINT fk_settlement_item_batch FOREIGN KEY (`batch_id`) REFERENCES `merchant_settlement_batch` (`id`),
  CONSTRAINT fk_settlement_item_order FOREIGN KEY (`order_id`) REFERENCES `trade_order` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算明细';

-- 25. 收款状态审计（T35）：停收/恢复/策略确认留痕；系统自动动作 operator_id 为空
CREATE TABLE IF NOT EXISTS `merchant_collection_status_audit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID',
  `action` VARCHAR(24) NOT NULL COMMENT 'STOP/RECOVER/POLICY',
  `reason` VARCHAR(64) NULL COMMENT 'ARREARS_LIMIT/GRACE_EXPIRED/POLICY_CONFIRMED',
  `arrears_at_action` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '动作时欠费',
  `credit_limit` DECIMAL(12,2) NULL COMMENT '动作时信用额度',
  `grace_days` INT NULL COMMENT '动作时账期',
  `due_at` DATETIME NULL COMMENT '动作时欠费到期时间',
  `operator_id` BIGINT NULL COMMENT '操作人；系统任务为空',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_collection_audit_merchant` (`merchant_id`, `created_at`),
  CONSTRAINT fk_collection_audit_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收款状态审计';

-- 26. 渠道流水（T35）：人工导入的渠道对账文件明细，只读比对，不直接改订单
CREATE TABLE IF NOT EXISTS `payment_channel_statement` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `channel` VARCHAR(32) NOT NULL COMMENT 'WECHAT/ALIPAY',
  `channel_trade_no` VARCHAR(128) NOT NULL COMMENT '渠道交易流水号',
  `out_trade_no` VARCHAR(64) NULL COMMENT '渠道侧商户订单号；可为空用于发现无订单流水',
  `order_sn` VARCHAR(64) NULL COMMENT '平台订单号（导入时按 out_trade_no 映射）',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '归属店铺',
  `amount` DECIMAL(12,2) NOT NULL COMMENT '渠道金额',
  `trade_status` VARCHAR(24) NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/REFUND/FAIL',
  `trade_time` DATETIME NULL COMMENT '渠道交易时间',
  `imported_by` BIGINT NULL COMMENT '导入人',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_statement_trade` (`channel`, `channel_trade_no`),
  KEY `idx_statement_merchant_time` (`merchant_id`, `trade_time`),
  CONSTRAINT fk_payment_channel_statement_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道流水';

-- 27. 对账差异队列（T35）：差异只记录不自动平衡，处理动作留痕
CREATE TABLE IF NOT EXISTS `reconciliation_difference` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `difference_key` VARCHAR(191) NOT NULL COMMENT '稳定差异键，防重复入队',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID',
  `channel` VARCHAR(32) NULL COMMENT '渠道',
  `diff_type` VARCHAR(48) NOT NULL COMMENT 'CHANNEL_WITHOUT_EVENT/EVENT_WITHOUT_STATEMENT/AMOUNT_MISMATCH/ORDER_MISMATCH/ORDER_MISSING/FEE_LEDGER_MISSING/DUPLICATE_PAYMENT/SETTLEMENT_ITEM_MISMATCH/RECHARGE_AMOUNT_MISMATCH',
  `biz_type` VARCHAR(24) NOT NULL COMMENT 'ORDER/EVENT/STATEMENT/SETTLEMENT/RECHARGE',
  `biz_id` BIGINT NULL COMMENT '关联业务主键',
  `order_sn` VARCHAR(64) NULL COMMENT '订单号',
  `channel_trade_no` VARCHAR(128) NULL COMMENT '渠道流水号',
  `expected_amount` DECIMAL(12,2) NULL COMMENT '平台应有金额',
  `actual_amount` DECIMAL(12,2) NULL COMMENT '渠道/实际金额',
  `status` VARCHAR(24) NOT NULL DEFAULT 'DISPUTED' COMMENT 'DISPUTED/RETRY/MANUAL/RESOLVED/IGNORED；MANUAL=自动重试超限转人工',
  `detail` VARCHAR(512) NULL COMMENT '差异说明',
  `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数；达到3次仍不一致转MANUAL',
  `handled_by` BIGINT NULL COMMENT '处理人',
  `handled_at` DATETIME NULL COMMENT '处理时间',
  `handle_note` VARCHAR(512) NULL COMMENT '处理说明',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_difference_key` (`difference_key`),
  KEY `idx_difference_merchant_status` (`merchant_id`, `status`, `created_at`),
  CONSTRAINT fk_difference_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账差异队列';

-- 28. 商家入驻申请（T39）：实名材料、审核状态机；身份证/营业执照只存密文与去重指纹，接口一律脱敏
CREATE TABLE IF NOT EXISTS `merchant_onboarding_application` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NULL COMMENT '审核通过后关联店铺；提交时为空',
  `subject_type` VARCHAR(16) NOT NULL COMMENT 'INDIVIDUAL/ENTERPRISE',
  `subject_name` VARCHAR(128) NOT NULL COMMENT '实名主体：经营者姓名/企业名称',
  `id_card_cipher` VARCHAR(512) NULL COMMENT '身份证号密文（复用 CardCipherService）',
  `id_card_fingerprint` VARCHAR(128) NULL COMMENT '主体去重指纹：个人=身份证，企业=营业执照号',
  `license_no_cipher` VARCHAR(512) NULL COMMENT '营业执照号密文',
  `license_url` VARCHAR(512) NULL COMMENT '执照影像地址',
  `legal_person` VARCHAR(64) NULL COMMENT '企业法定代表人/个人经营者',
  `contact_phone` VARCHAR(32) NOT NULL,
  `contact_email` VARCHAR(128) NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SUBMITTED/REVIEWING/APPROVED/REJECTED/SUPPLEMENT_REQUIRED/WITHDRAWN',
  `verification_method` VARCHAR(24) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/THIRD_PARTY；阶段一固定 MANUAL，不得把人工审核标成第三方实名',
  `verification_source` VARCHAR(32) NULL COMMENT 'MANUAL_REVIEW 等；仅记录实际核验来源',
  `reject_reason` VARCHAR(512) NULL,
  `submitted_at` DATETIME NULL,
  `reviewed_at` DATETIME NULL,
  `reviewer_id` BIGINT NULL,
  `version` INT NOT NULL DEFAULT 0 COMMENT '补件/状态推进版本，配合行锁防并发覆盖',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_onboarding_subject` (`subject_type`, `id_card_fingerprint`),
  KEY `idx_onboarding_status_submitted` (`status`, `submitted_at`),
  KEY `idx_onboarding_merchant` (`merchant_id`),
  CONSTRAINT fk_onboarding_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家入驻申请';

-- 29. 入驻审核审计（T39）：每次状态推进、补件、驳回都留痕，不改历史申请
CREATE TABLE IF NOT EXISTS `merchant_onboarding_audit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `application_id` BIGINT UNSIGNED NOT NULL,
  `merchant_id` BIGINT UNSIGNED NULL,
  `action` VARCHAR(32) NOT NULL COMMENT 'CREATE/MATERIAL_UPDATED/SUBMIT/START_REVIEW/APPROVE/REJECT/SUPPLEMENT_REQUIRED/WITHDRAW',
  `from_status` VARCHAR(24) NULL,
  `to_status` VARCHAR(24) NULL,
  `operator_id` BIGINT NULL,
  `reason` VARCHAR(512) NULL,
  `material_hash` VARCHAR(128) NULL COMMENT '材料摘要，不复制证件原文',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_onboarding_audit_app` (`application_id`, `created_at`),
  CONSTRAINT fk_onboarding_audit_app FOREIGN KEY (`application_id`) REFERENCES `merchant_onboarding_application` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家入驻审核审计';

-- 30. 商家收款账户（T39 阶段一）：登记 + 人工核验；户名必须等于实名主体，不写渠道进件假值
CREATE TABLE IF NOT EXISTS `merchant_settlement_account` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `account_type` VARCHAR(16) NOT NULL COMMENT 'BANK/WECHAT/ALIPAY',
  `holder_name` VARCHAR(128) NOT NULL COMMENT '开户名/收款人，硬校验必须等于实名主体',
  `bank_name` VARCHAR(128) NULL,
  `bank_branch` VARCHAR(128) NULL,
  `account_no_cipher` VARCHAR(512) NOT NULL COMMENT '银行卡号/收款账号密文',
  `account_fingerprint` VARCHAR(128) NOT NULL COMMENT '账号去重指纹',
  `account_no_masked` VARCHAR(64) NOT NULL COMMENT '后四位脱敏展示值',
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/VERIFIED/REJECTED/DISABLED',
  `reject_reason` VARCHAR(512) NULL,
  `is_default` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '同一商户默认账户唯一',
  `effective_from` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `effective_to` DATETIME NULL COMMENT '换账户时停用旧账户，不原地覆盖历史',
  `verified_at` DATETIME NULL,
  `verified_by` BIGINT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_account_fingerprint` (`merchant_id`, `account_fingerprint`),
  KEY `idx_settlement_merchant_status` (`merchant_id`, `status`, `is_default`),
  CONSTRAINT fk_settlement_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家收款账户';

-- 31. 收款账户审核审计（T39）：登记/核验/驳回/停用留痕，不复制账号明文
CREATE TABLE IF NOT EXISTS `merchant_settlement_account_audit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `account_id` BIGINT UNSIGNED NOT NULL,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `action` VARCHAR(32) NOT NULL COMMENT 'REGISTER/VERIFY/REJECT/DISABLE',
  `account_type` VARCHAR(16) NULL,
  `account_fingerprint` VARCHAR(128) NULL,
  `operator_id` BIGINT NULL,
  `reason` VARCHAR(512) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_settlement_audit_account` (`account_id`, `created_at`),
  CONSTRAINT fk_settlement_audit_account FOREIGN KEY (`account_id`) REFERENCES `merchant_settlement_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收款账户审核审计';

-- 32. 技术服务费率政策（T39）：1%~10% 正常费率 + 0% 独立推广状态；同商户同一时刻仅一套生效政策
CREATE TABLE IF NOT EXISTS `merchant_fee_policy` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `rate_type` VARCHAR(16) NOT NULL COMMENT 'NORMAL-1%~10% / PROMOTION-0%',
  `rate_bps` INT NOT NULL COMMENT '整数基点：NORMAL 100–1000；PROMOTION 固定 0',
  `promotion_reason` VARCHAR(512) NULL COMMENT '推广期 0 费率的业务原因',
  `effective_from` DATETIME NOT NULL COMMENT '生效时间；降费即时、调高为次日 00:00',
  `effective_to` DATETIME NULL COMMENT '失效时间；NULL 表示当前有效',
  `source` VARCHAR(24) NOT NULL COMMENT 'ONBOARDING/ADMIN_ADJUST/PROMOTION/MIGRATION',
  `reason` VARCHAR(512) NULL COMMENT '调价原因',
  `confirmed_by` BIGINT NULL COMMENT '调价确认人',
  `operator_id` BIGINT NULL COMMENT '操作人',
  `approval_mode` VARCHAR(24) NULL COMMENT '复核模式：SAME_OPERATOR/DUAL_APPROVAL；由 FinanceApprovalGuard 统一判定',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_fee_policy_merchant_effective` (`merchant_id`, `effective_from`, `effective_to`),
  CONSTRAINT fk_fee_policy_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技术服务费率政策';

-- 33. 费率政策审计（T39）：记录前后值、原因、操作人/确认人与生效时间
CREATE TABLE IF NOT EXISTS `merchant_fee_audit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `policy_id` BIGINT UNSIGNED NOT NULL,
  `from_rate_type` VARCHAR(16) NULL,
  `from_rate_bps` INT NULL,
  `to_rate_type` VARCHAR(16) NOT NULL,
  `to_rate_bps` INT NOT NULL,
  `reason` VARCHAR(512) NULL,
  `operator_id` BIGINT NULL,
  `confirmed_by` BIGINT NULL,
  `approval_mode` VARCHAR(24) NULL COMMENT '复核模式：SAME_OPERATOR（单管理员）/DUAL_APPROVAL',
  `effective_from` DATETIME NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_fee_audit_merchant` (`merchant_id`, `created_at`),
  CONSTRAINT fk_fee_audit_policy FOREIGN KEY (`policy_id`) REFERENCES `merchant_fee_policy` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='费率政策审计';

-- ========================================================
-- T40 阶段二平台代收与分账骨架（默认不启用；取得支付/分账资质并完成通道联调后才可写入真实结果）
-- 说明：以下表只承载本地账本与结算骨架，不调用微信/支付宝分账接口，不产生真实资金流转。
-- ========================================================

-- 34. 平台代收结算台账（T40）：一笔平台代收订单一条，记录实收、平台留成与商家应结，金额必须守恒
CREATE TABLE IF NOT EXISTS `platform_collection_settlement` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '平台代收订单ID',
  `order_sn` VARCHAR(64) NOT NULL COMMENT '订单号快照',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID',
  `payment_event_id` BIGINT UNSIGNED NULL COMMENT '渠道到账事件ID',
  `platform_trade_no` VARCHAR(128) NOT NULL COMMENT '平台代收渠道流水号',
  `gross_amount` DECIMAL(12,2) NOT NULL COMMENT '渠道实收金额',
  `platform_retain_amount` DECIMAL(12,2) NOT NULL COMMENT '平台留成（含技术服务费）',
  `merchant_payable_amount` DECIMAL(12,2) NOT NULL COMMENT '应分给商家的净额',
  `settled_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '已结算给商家的金额；骨架阶段保持0',
  `refunded_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '已退分/已退款冲减金额',
  `settlement_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/HOLD/SETTLED/DIFFERENCE',
  `settle_batch_id` BIGINT UNSIGNED NULL COMMENT '关联 merchant_settlement_batch；阶段二出账后回填',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_platform_settlement_order` (`order_id`),
  KEY `idx_platform_settlement_merchant` (`merchant_id`, `settlement_status`),
  KEY `idx_platform_settlement_trade` (`platform_trade_no`),
  CONSTRAINT `chk_platform_settlement_amounts` CHECK (`gross_amount` >= 0.00 AND `platform_retain_amount` >= 0.00
      AND `merchant_payable_amount` >= 0.00 AND `settled_amount` >= 0.00 AND `refunded_amount` >= 0.00
      AND `merchant_payable_amount` + `platform_retain_amount` = `gross_amount`),
  CONSTRAINT fk_platform_settlement_order FOREIGN KEY (`order_id`) REFERENCES `trade_order` (`id`),
  CONSTRAINT fk_platform_settlement_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`),
  CONSTRAINT fk_platform_settlement_event FOREIGN KEY (`payment_event_id`) REFERENCES `payment_event` (`id`),
  CONSTRAINT fk_platform_settlement_batch FOREIGN KEY (`settle_batch_id`) REFERENCES `merchant_settlement_batch` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台代收结算台账（骨架，不产生真实资金）';

-- 35. 分账明细账本（T40）：同一订单+接收方幂等；分账金额与平台留成必须等于实收
CREATE TABLE IF NOT EXISTS `split_detail` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '平台代收订单ID',
  `order_sn` VARCHAR(64) NOT NULL,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `payment_event_id` BIGINT UNSIGNED NULL,
  `payment_account_id` BIGINT UNSIGNED NOT NULL COMMENT '分账接收方：复用 merchant_payment_account 绑定记录，不另建接收方表',
  `channel` VARCHAR(32) NOT NULL COMMENT 'WECHAT/ALIPAY',
  `platform_trade_no` VARCHAR(128) NOT NULL,
  `gross_amount` DECIMAL(12,2) NOT NULL COMMENT '该笔分账对应的渠道实收',
  `platform_retain_amount` DECIMAL(12,2) NOT NULL COMMENT '平台留成',
  `fee_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '留成中的技术服务费，来自订单快照',
  `split_amount` DECIMAL(12,2) NOT NULL COMMENT '应分给接收方的金额',
  `split_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/FINISHED/FAILED/REVERSED',
  `freeze_until` DATETIME NULL COMMENT '渠道冻结截止时间；以渠道能力为准，骨架阶段为空',
  `reversed_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '累计退分金额',
  `channel_split_no` VARCHAR(128) NULL COMMENT '渠道分账单号；未联调前为空',
  `failure_reason` VARCHAR(255) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_split_order_receiver` (`order_id`, `payment_account_id`),
  KEY `idx_split_merchant_status` (`merchant_id`, `split_status`),
  KEY `idx_split_platform_trade` (`platform_trade_no`),
  CONSTRAINT `chk_split_amounts` CHECK (`gross_amount` >= 0.00 AND `platform_retain_amount` >= 0.00
      AND `fee_amount` >= 0.00 AND `split_amount` >= 0.00 AND `reversed_amount` >= 0.00
      AND `split_amount` + `platform_retain_amount` = `gross_amount`
      AND `reversed_amount` <= `split_amount`),
  CONSTRAINT fk_split_order FOREIGN KEY (`order_id`) REFERENCES `trade_order` (`id`),
  CONSTRAINT fk_split_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`),
  CONSTRAINT fk_split_event FOREIGN KEY (`payment_event_id`) REFERENCES `payment_event` (`id`),
  CONSTRAINT fk_split_account FOREIGN KEY (`payment_account_id`) REFERENCES `merchant_payment_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分账明细账本（骨架，不调用真实分账通道）';

-- 36. 退分账本（T40）：退款前必须先退分；同一分账明细+退款单幂等
CREATE TABLE IF NOT EXISTS `split_reverse` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `split_detail_id` BIGINT UNSIGNED NOT NULL,
  `refund_id` BIGINT UNSIGNED NOT NULL COMMENT '关联 trade_refund',
  `refund_no` VARCHAR(64) NOT NULL,
  `order_sn` VARCHAR(64) NOT NULL,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `reverse_amount` DECIMAL(12,2) NOT NULL COMMENT '本次退分金额',
  `reverse_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/FINISHED/FAILED',
  `channel_reverse_no` VARCHAR(128) NULL COMMENT '渠道退分单号；未联调前为空',
  `failure_reason` VARCHAR(255) NULL,
  `operator_id` BIGINT NULL COMMENT '发起退分的操作人；系统任务可为空，但不得省略来源退款单号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_split_reverse_refund` (`split_detail_id`, `refund_id`),
  KEY `idx_split_reverse_merchant` (`merchant_id`, `reverse_status`),
  CONSTRAINT `chk_split_reverse_amount` CHECK (`reverse_amount` > 0.00),
  CONSTRAINT fk_split_reverse_detail FOREIGN KEY (`split_detail_id`) REFERENCES `split_detail` (`id`),
  CONSTRAINT fk_split_reverse_refund FOREIGN KEY (`refund_id`) REFERENCES `trade_refund` (`id`),
  CONSTRAINT fk_split_reverse_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退分账本（骨架，不调用真实退分通道）';
