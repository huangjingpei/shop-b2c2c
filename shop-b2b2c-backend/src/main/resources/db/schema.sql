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
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '店铺状态: 0-待开通(已建店未完成协议/验证), 1-正常营业, 2-打烊, 3-已封禁, 4-已注销(终态)',
  `commission_rate` DECIMAL(5,2) NOT NULL DEFAULT 3.00 COMMENT '平台抽成比例(%)；T39 起仅作展示镜像，计费唯一来源为 merchant_fee_policy',
  `shipping_fee` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'F07 实物订单每单固定运费(元)，0=包邮；仅实物单计入实付，不计入服务费基数',
  `monthly_fee` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '月度技术服务费(元/月，前期=0)',
  `fee_free_until` DATETIME NULL COMMENT '免费期截止日期',
  `fee_status` VARCHAR(32) NOT NULL DEFAULT 'NORMAL' COMMENT '服务费配置标记: NORMAL, TRIAL, OVERDUE, EXEMPTED, ARREARS; 不代表自动扣费或停服',
  `payment_channel_type` VARCHAR(16) NOT NULL DEFAULT 'C' COMMENT '支付模式: B(商户自主直收), C(Mock沙箱), A(平台电商收付通); T32 起仅作迁移来源',
  `pay_channel` VARCHAR(32) NOT NULL DEFAULT 'WECHAT' COMMENT 'T32 新增：真实支付渠道 WECHAT/ALIPAY，与 A/B/C 收款模式解耦',
  `custom_pay_qrcode` VARCHAR(512) NULL COMMENT '商户自主微信/支付宝收款码URL',
  `id_card_expiry` DATE NULL COMMENT '身份证有效期快照：审核通过时从入驻申请快照（O9），用于到期提醒/限制上新/逾期冻结',
  `license_expiry` DATE NULL COMMENT '营业执照有效期快照：审核通过时从入驻申请快照（O9），个人店可空',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_store_code` (`store_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户店铺主表';

-- 1a. 店铺状态流转审计（O6）：冻结/解封/关店/注销全留痕，append-only；
--     同一事务内 CAS 更新 merchant_store.status 并写入本表，并发只有一个成功
CREATE TABLE IF NOT EXISTS `merchant_store_status_audit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `action` VARCHAR(16) NOT NULL COMMENT 'OPEN/PAUSE/RESUME/FREEZE/UNFREEZE/CLOSE/CANCEL',
  `from_status` TINYINT NOT NULL,
  `to_status` TINYINT NOT NULL COMMENT '0-待开通 1-营业 2-打烊 3-冻结 4-已注销(终态)',
  `reason_code` VARCHAR(32) NOT NULL,
  `reason` VARCHAR(512) NULL,
  `operator_id` BIGINT NULL COMMENT '为空表示系统任务（如资质过期自动冻结）',
  `confirmed_by` BIGINT NULL COMMENT '复核人；解封/注销双人复核时必填',
  `approval_mode` VARCHAR(16) NULL COMMENT 'SAME_OPERATOR-非严格模式同人放行并告警 / DUAL-双人复核',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_store_status_audit_merchant` (`merchant_id`, `created_at`),
  CONSTRAINT fk_store_status_audit_store FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺状态流转审计';

-- 1b. 通知发件箱（O8）：站内通知(INAPP)落库即送达；SMS 通道默认禁用置 SKIPPED，接入服务商前不得发送；
--     uk_notification_event 保证同一业务事件同渠道同模板只发一次（定时任务重扫/失败重试幂等）；
--     payload_json 严禁写入密码、证件号、银行卡号等敏感字段（应用层校验拒绝 password 键）
CREATE TABLE IF NOT EXISTS `merchant_notification_outbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `biz_type` VARCHAR(32) NOT NULL COMMENT 'ONBOARDING_SUBMITTED/ONBOARDING_SUPPLEMENT/ONBOARDING_REJECTED/ONBOARDING_APPROVED/ONBOARDING_OPENED/LICENSE_EXPIRING/REVIEW_SLA_BREACH/STORE_STATUS_CHANGED',
  `biz_id` BIGINT UNSIGNED NOT NULL COMMENT '业务事件ID：开店节点=开店审计行ID，店铺状态=店铺状态审计行ID，SLA=申请ID',
  `channel` VARCHAR(16) NOT NULL COMMENT 'INAPP/SMS',
  `template_code` VARCHAR(64) NOT NULL COMMENT '通知模板；SLA 分 REVIEW_SLA_REMINDER/REVIEW_SLA_ESCALATION/REVIEW_SLA_REVIEWER',
  `recipient` VARCHAR(128) NOT NULL COMMENT '申请人手机号 / MERCHANT:{店铺ID} / ROLE:{角色码}',
  `payload_json` JSON NULL COMMENT '通知内容快照；不含密码/证件/银行卡等敏感字段',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED/SKIPPED',
  `attempts` INT NOT NULL DEFAULT 0,
  `next_retry_at` DATETIME NULL,
  `last_error` VARCHAR(512) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `sent_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_event` (`biz_type`, `biz_id`, `channel`, `template_code`),
  KEY `idx_notification_retry` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知发件箱；SMS 通道默认禁用';

-- 2. 微信/支付宝官方二级商户进件账户表
CREATE TABLE IF NOT EXISTS `merchant_payment_account` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '关联店铺ID',
  `channel` VARCHAR(32) NOT NULL COMMENT '渠道: WECHAT(电商收付通), ALIPAY(直付通)',
  `sub_mchid` VARCHAR(64) NOT NULL COMMENT '官方分配的二级商户号',
  `bank_account_name` VARCHAR(64) NOT NULL COMMENT '结算开户人姓名',
  `bank_account_number` VARCHAR(64) NOT NULL COMMENT '结算银行卡号',
  `bank_name` VARCHAR(128) NOT NULL COMMENT '开户银行支行名称',
  `apply_status` VARCHAR(32) NOT NULL DEFAULT 'AUDITING' COMMENT '进件状态: AUDITING-审核中, PASSED-已开通, REJECTED-已驳回；默认必须是审核中，阶段一不写假PASSED',
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
  `must_change_password` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'D11 首次登录强制改密: 1-须先改密才能访问业务工作台(仅 /api/v1/auth/** 可用), 0-正常；审核开店生成的店长账号落 1',
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
  `violation_reason` VARCHAR(255) DEFAULT NULL COMMENT '最近一次违规处置原因（平台填写，审核恢复后清空）',
  `violated_at` DATETIME DEFAULT NULL COMMENT '最近一次违规下架时间（审核恢复后清空）',
  `sort_order` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_merchant_product` (`merchant_id`, `status`),
  KEY `idx_category` (`category_id`),
  CONSTRAINT fk_product_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`),
  CONSTRAINT fk_product_category FOREIGN KEY (`category_id`) REFERENCES `category` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品主表';

-- 5b. 商品违规处置审计（F11）：平台违规下架(TAKEDOWN)与审核恢复(RESTORE)全量留痕，只增不改；
--     RESTORE 的 to_status 取最近一次 TAKEDOWN 的 from_status（撤销处置回到原状态），应用层 CAS 保证并发只成功一次。
CREATE TABLE IF NOT EXISTS `product_violation_audit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `product_id` BIGINT UNSIGNED NOT NULL COMMENT '被处置商品ID',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '商品所属店铺ID（冗余便于按店筛查）',
  `action` VARCHAR(16) NOT NULL COMMENT 'TAKEDOWN-违规下架 / RESTORE-审核恢复',
  `from_status` TINYINT NOT NULL COMMENT '处置前商品状态: 0-已下架, 1-在售, 2-违规下架',
  `to_status` TINYINT NOT NULL COMMENT '处置后商品状态',
  `reason` VARCHAR(255) NOT NULL COMMENT '处置原因/审核备注',
  `operator_id` BIGINT NOT NULL COMMENT '操作的平台超管 sys_user.id',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_violation_audit_product` (`product_id`, `created_at`),
  KEY `idx_violation_audit_merchant` (`merchant_id`, `created_at`),
  CONSTRAINT fk_violation_audit_product FOREIGN KEY (`product_id`) REFERENCES `product` (`id`),
  CONSTRAINT fk_violation_audit_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品违规处置审计';

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
  `receiver_name` VARCHAR(64) DEFAULT NULL COMMENT 'F07 收件人姓名快照（仅实物订单，数字商品为空）',
  `receiver_phone` VARCHAR(32) DEFAULT NULL COMMENT 'F07 收件人手机号快照（仅实物订单，用于物流面单）',
  `receiver_region` VARCHAR(64) DEFAULT NULL COMMENT 'F07 收货省市区快照（仅实物订单）',
  `receiver_address` VARCHAR(200) DEFAULT NULL COMMENT 'F07 详细收货地址快照（仅实物订单）',
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
  `fee_policy_id` BIGINT UNSIGNED NULL COMMENT 'T34 订单级费率快照来源的政策ID；T39 起写 merchant_fee_policy.id，历史订单可为空',
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
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '订单任意字段（含状态、退款预占、服务费状态）最近变更时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_sn` (`order_sn`),
  UNIQUE KEY `uk_peer_pay_token` (`peer_pay_token`),
  UNIQUE KEY `uk_query_token` (`query_token`),
  KEY `idx_merchant_order` (`merchant_id`, `order_status`),
  KEY `idx_contact` (`buyer_contact`),
  KEY `idx_order_created` (`created_at`, `id`),
  KEY `idx_merchant_created` (`merchant_id`, `created_at`, `id`),
  KEY `idx_order_expiry` (`order_status`, `lock_expire_time`, `id`),
  CONSTRAINT `chk_order_amounts` CHECK (`total_amount` >= 0.00 AND `pay_amount` >= 0.00),
  CONSTRAINT `chk_order_refund_within_paid` CHECK (`refunded_amount` >= 0.00 AND `refunded_amount` <= `pay_amount`
      AND `fee_refunded_amount` >= 0.00 AND `fee_refunded_amount` <= `commission_amount`),
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
  `success_at` DATETIME NULL COMMENT '退款成功时点（不可变，仅 PROCESSING→SUCCESS 时写一次）；T+1 切日与结算冲正按此列，不能用会被改写的 updated_at',
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
  KEY `idx_refund_success` (`merchant_id`, `success_at`),
  CONSTRAINT `fk_refund_order` FOREIGN KEY (`order_id`) REFERENCES `trade_order` (`id`),
  CONSTRAINT `chk_refund_amount` CHECK (`refund_amount` > 0.00 AND `fee_refund_amount` >= 0.00)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单退款与重复付款补偿记录';

-- F05：买家退款申请单（与 trade_refund 实际执行记录分离）。买家发起后进入 PENDING，商家审批同意才生成 trade_refund 并走渠道原路退回；拒绝/撤回即终态。
CREATE TABLE IF NOT EXISTS `refund_request` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `order_sn` VARCHAR(64) NOT NULL,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `buyer_contact` VARCHAR(64) NOT NULL COMMENT '买家联系电话（脱敏存储校验用）',
  `refund_amount` DECIMAL(10,2) NOT NULL COMMENT '申请退款金额，须 <= 订单可退余额',
  `reason` VARCHAR(256) NOT NULL COMMENT '买家退款原因',
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/CANCELLED',
  `reject_reason` VARCHAR(256) DEFAULT NULL,
  `applied_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `decided_at` DATETIME NULL,
  `decided_by` BIGINT UNSIGNED NULL,
  `decided_role` VARCHAR(32) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_rr_order` (`order_id`),
  KEY `idx_rr_merchant_status` (`merchant_id`, `status`, `created_at`),
  CONSTRAINT `fk_rr_order` FOREIGN KEY (`order_id`) REFERENCES `trade_order` (`id`),
  CONSTRAINT `chk_rr_amount` CHECK (`refund_amount` > 0.00)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='买家退款申请单（F05）';

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

-- 12.1 F06 投诉工单追加式操作记录：只插入不更新/删除，保存每次流转的操作人、说明与举证快照。
CREATE TABLE IF NOT EXISTS `complaint_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `complaint_id` BIGINT UNSIGNED NOT NULL COMMENT '关联投诉工单ID',
  `event_type` VARCHAR(32) NOT NULL COMMENT 'SUBMITTED/MERCHANT_REPLIED/ESCALATED/RESOLVED/REJECTED',
  `status_after` VARCHAR(32) NOT NULL COMMENT '流转后工单状态',
  `actor_role` VARCHAR(32) NOT NULL COMMENT 'BUYER/MERCHANT_ADMIN/SUPERADMIN',
  `actor_id` BIGINT UNSIGNED NULL COMMENT '登录操作人ID，买家匿名操作时为空',
  `actor_name` VARCHAR(64) NULL COMMENT '操作人展示名快照',
  `note` TEXT NULL COMMENT '当次说明快照',
  `evidence_images` TEXT NULL COMMENT '当次举证链接 JSON 快照',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_complaint_event` (`complaint_id`, `created_at`),
  CONSTRAINT fk_complaint_event_complaint FOREIGN KEY (`complaint_id`) REFERENCES `complaint` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投诉工单追加式操作记录（不可更新/删除）';

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

-- F12 补发授权：一张已售卡最多补发一次；新卡同事务从同SKU未售池取出并绑定原订单，源卡保持已售不回滚交付事实。
CREATE TABLE IF NOT EXISTS card_reissue (
  source_card_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  new_card_id BIGINT UNSIGNED NOT NULL,
  merchant_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  order_sn VARCHAR(64) NOT NULL,
  actor_id BIGINT UNSIGNED NOT NULL,
  reason VARCHAR(255) NOT NULL,
  before_stock INT NOT NULL,
  after_stock INT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_card_reissue_new (new_card_id),
  KEY idx_card_reissue_merchant (merchant_id,created_at),
  KEY idx_card_reissue_order (merchant_id,order_sn),
  CONSTRAINT chk_card_reissue_stock CHECK (before_stock >= 1 AND after_stock = before_stock - 1),
  CONSTRAINT fk_card_reissue_source FOREIGN KEY (source_card_id) REFERENCES product_virtual_card (id),
  CONSTRAINT fk_card_reissue_new FOREIGN KEY (new_card_id) REFERENCES product_virtual_card (id),
  CONSTRAINT fk_card_reissue_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id),
  CONSTRAINT fk_card_reissue_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT fk_card_reissue_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),
  CONSTRAINT fk_card_reissue_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卡密补发记录，不含卡密内容';

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
  `funds_received` TINYINT NULL COMMENT 'T+1：渠道侧是否存在该笔资金往来、应当有对应渠道流水（非“此刻仍持有资金”）。1-预期渠道存在流水（新事件占位即 1，且只增不减）；0-明确无资金往来（仅对未标注行/历史或异常行生效，新事件不可达）；NULL-未判定（需人工核对，不得视为无需流水）；刻意不设默认值',
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
  `biz_id` BIGINT UNSIGNED NULL COMMENT '业务主键（订单ID/退款ID/充值单ID）',
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
  CONSTRAINT `chk_ledger_amount` CHECK (`amount` >= 0.00),
  CONSTRAINT `chk_ledger_balance_nonnegative` CHECK (`balance_after` >= 0.00 AND `arrears_after` >= 0.00)
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
  `ledger_entry_id` BIGINT UNSIGNED NULL COMMENT '确认入账对应的账本ID',
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
  CONSTRAINT fk_recharge_ledger_entry FOREIGN KEY (`ledger_entry_id`) REFERENCES `merchant_account_ledger` (`id`),
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
  `payment_event_id` BIGINT UNSIGNED NULL COMMENT '支付事件ID',
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
  CONSTRAINT fk_settlement_item_order FOREIGN KEY (`order_id`) REFERENCES `trade_order` (`id`),
  CONSTRAINT fk_settlement_item_event FOREIGN KEY (`payment_event_id`) REFERENCES `payment_event` (`id`)
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
  `currency` VARCHAR(16) NOT NULL DEFAULT 'CNY' COMMENT '渠道币种；与 payment_event.currency 比对，不一致记 CURRENCY',
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
  `diff_type` VARCHAR(48) NOT NULL COMMENT 'CHANNEL_WITHOUT_EVENT/EVENT_WITHOUT_STATEMENT/AMOUNT_MISMATCH/PAYMENT_EVENT_REJECTED/PAYMENT_EVENT_REVIEW/PAYMENT_EVENT_REFUNDED/PAYMENT_EVENT_CHANNEL_CONFLICT/ORDER_MISMATCH/ORDER_MISSING/FEE_LEDGER_MISSING/FEE_AMOUNT_MISMATCH/REFUND_WITHOUT_STATEMENT/REFUND_AMOUNT_MISMATCH/REFUND_STATEMENT_WITHOUT_REFUND/REFUND_FEE_LEDGER_MISSING/REFUND_FEE_AMOUNT_MISMATCH/CURRENCY_MISMATCH/TIME_CROSS_DAY/PLATFORM_SETTLEMENT_MISMATCH/PLATFORM_SETTLEMENT_BATCH_MISSING/DUPLICATE_PAYMENT/SETTLEMENT_ITEM_MISMATCH/RECHARGE_AMOUNT_MISMATCH',
  `diff_category` VARCHAR(32) NULL COMMENT 'T+1 分类：LONG/SHORT/ONE_SIDED_LOCAL/ONE_SIDED_CHANNEL/PRECISION/CURRENCY/TIME_CROSS_DAY/INTERNAL',
  `biz_type` VARCHAR(24) NOT NULL COMMENT 'ORDER/EVENT/STATEMENT/REFUND/SETTLEMENT/PLATFORM_SETTLEMENT/LEDGER/RECHARGE',
  `biz_id` BIGINT UNSIGNED NULL COMMENT '关联业务主键',
  `order_sn` VARCHAR(64) NULL COMMENT '订单号',
  `channel_trade_no` VARCHAR(128) NULL COMMENT '渠道流水号；退款时可为渠道退款流水号',
  `business_date` DATE NULL COMMENT '差异归属业务日（T+1 跑批，Asia/Shanghai）',
  `expected_amount` DECIMAL(12,2) NULL COMMENT '平台应有金额',
  `actual_amount` DECIMAL(12,2) NULL COMMENT '渠道/实际金额',
  `status` VARCHAR(24) NOT NULL DEFAULT 'DISPUTED' COMMENT 'DISPUTED/RETRY/MANUAL/RESOLVED/IGNORED；MANUAL=自动重试超限转人工',
  `detail` VARCHAR(512) NULL COMMENT '差异说明',
  `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数；达到3次仍不一致转MANUAL',
  `last_retry_date` DATE NULL COMMENT '最近一次重试的业务日；同一业务日重复跑批不重复计数',
  `handled_by` BIGINT NULL COMMENT '处理人',
  `handled_at` DATETIME NULL COMMENT '处理时间',
  `handle_note` VARCHAR(512) NULL COMMENT '处理说明',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_difference_key` (`difference_key`),
  KEY `idx_difference_merchant_status` (`merchant_id`, `status`, `created_at`),
  KEY `idx_difference_business` (`business_date`, `merchant_id`, `status`),
  CONSTRAINT fk_difference_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`),
  CONSTRAINT `chk_difference_amounts` CHECK (`expected_amount` IS NULL OR `expected_amount` >= 0.00)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账差异队列';

-- 27.1 T+1 跑批记录：每店每业务日一条，唯一键保证同一天重复跑不产生第二条；状态机支持续跑与转人工
CREATE TABLE IF NOT EXISTS `reconciliation_batch_run` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `business_date` DATE NOT NULL COMMENT '业务日（Asia/Shanghai）',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '店铺ID',
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/PARTIAL/FAILED/MANUAL；PARTIAL=渠道流水未导入，可续跑',
  `attempts` INT NOT NULL DEFAULT 0 COMMENT '已尝试次数；达到上限转 MANUAL',
  `statement_count` INT NOT NULL DEFAULT 0 COMMENT '本业务日渠道流水数',
  `event_count` INT NOT NULL DEFAULT 0 COMMENT '本业务日支付事件数',
  `refund_count` INT NOT NULL DEFAULT 0 COMMENT '本业务日成功退款数',
  `difference_added` INT NOT NULL DEFAULT 0 COMMENT '新增差异数',
  `difference_refreshed` INT NOT NULL DEFAULT 0 COMMENT '刷新已有未关闭差异数',
  `retry_processed` INT NOT NULL DEFAULT 0 COMMENT '自动重试处理数',
  `retry_failed` INT NOT NULL DEFAULT 0 COMMENT '自动重试异常数；异常摘要追加到 last_error',
  `manual_escalated` INT NOT NULL DEFAULT 0 COMMENT '本次转人工数',
  `last_error` VARCHAR(512) NULL COMMENT '失败/部分完成原因；渠道流水未导入等缺口在此可见',
  `started_at` DATETIME NULL,
  `finished_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reconciliation_run` (`business_date`, `merchant_id`),
  KEY `idx_reconciliation_run_status` (`business_date`, `status`),
  CONSTRAINT fk_reconciliation_run_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='T+1 对账跑批记录';

-- 27.2 差异处理审计：只追加，记录谁在何时把哪条差异从什么状态改成什么状态
CREATE TABLE IF NOT EXISTS `reconciliation_difference_audit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `difference_id` BIGINT UNSIGNED NOT NULL COMMENT '差异ID',
  `action` VARCHAR(24) NOT NULL COMMENT 'CREATE/RETRY/RESOLVE/IGNORE/ESCALATE',
  `from_status` VARCHAR(24) NULL COMMENT '变更前状态；CREATE 为空',
  `to_status` VARCHAR(24) NOT NULL COMMENT '变更后状态',
  `operator_id` BIGINT NULL COMMENT '操作人；NULL 或 0 表示系统任务',
  `note` VARCHAR(512) NULL COMMENT '说明',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_difference_audit_diff` (`difference_id`, `created_at`, `id`),
  CONSTRAINT fk_difference_audit_diff FOREIGN KEY (`difference_id`) REFERENCES `reconciliation_difference` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账差异处理审计（append-only）';

-- 28. 商家入驻申请（T39）：实名材料、审核状态机；身份证/营业执照只存密文与去重指纹，接口一律脱敏
CREATE TABLE IF NOT EXISTS `merchant_onboarding_application` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NULL COMMENT '审核通过后关联店铺；提交时为空',
  `subject_type` VARCHAR(16) NOT NULL COMMENT 'INDIVIDUAL/INDIVIDUAL_BUSINESS/ENTERPRISE；缺省 INDIVIDUAL',
  `subject_name` VARCHAR(128) NOT NULL COMMENT '实名主体：经营者姓名/企业名称',
  `store_name` VARCHAR(128) NULL COMMENT '申请人填写的店铺名称，审核通过自动开店使用',
  `face_verify_code` VARCHAR(16) NULL COMMENT '个人店实人认证随机数字口令，申请人录制朗读视频，人工审核比对',
  `id_card_cipher` VARCHAR(512) NULL COMMENT '身份证号密文（复用 CardCipherService）',
  `id_card_fingerprint` VARCHAR(128) NULL COMMENT '主体去重指纹：个人=身份证，企业=营业执照号',
  `active_fingerprint` VARCHAR(128) GENERATED ALWAYS AS (IF(`status` IN ('DRAFT','SUBMITTED','REVIEWING','SUPPLEMENT_REQUIRED','APPROVED','OPENED'), `id_card_fingerprint`, NULL)) STORED COMMENT '进行中申请的主体指纹（终态行为 NULL）；配合 uk_onboarding_active_subject 并发防重',
  `license_no_cipher` VARCHAR(512) NULL COMMENT '营业执照号密文',
  `license_url` VARCHAR(512) NULL COMMENT '执照影像地址',
  `legal_person` VARCHAR(64) NULL COMMENT '企业法定代表人/个人经营者',
  `contact_phone` VARCHAR(32) NOT NULL,
  `contact_email` VARCHAR(128) NULL,
  `access_token_hash` VARCHAR(128) NULL COMMENT '申请人查询凭证 SHA-256；明文只在创建响应返回一次',
  `initial_password_cipher` VARCHAR(512) NULL COMMENT '自动开店初始密码密文，仅首次进度查询展示一次',
  `initial_password_revealed_at` DATETIME NULL COMMENT '初始密码对外展示时间；非空表示已用过一次性展示',
  `status` VARCHAR(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SUBMITTED/REVIEWING/APPROVED/OPENED/REJECTED/SUPPLEMENT_REQUIRED/WITHDRAWN；OPENED=已开通可营业',
  `verification_method` VARCHAR(24) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/THIRD_PARTY；阶段一固定 MANUAL，不得把人工审核标成第三方实名',
  `verification_source` VARCHAR(32) NULL COMMENT 'MANUAL_REVIEW 等；仅记录实际核验来源',
  `reject_code` VARCHAR(32) NULL COMMENT '驳回/补件原因码（开发文档 5.5 字典）；OTHER 时 reject_reason 必填',
  `reject_reason` VARCHAR(512) NULL,
  `resubmit_count` INT NOT NULL DEFAULT 0 COMMENT '补件后重新提交次数',
  `review_stage` VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT '审核阶段 NONE/PRIMARY/SECONDARY/PASSED；双人复核开启时初审通过后置 SECONDARY 待复审',
  `first_reviewer_id` BIGINT NULL COMMENT '初审人；双人复核开启时复审人必须与其不同',
  `agreement_required` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '开通前是否必须完成必需协议签署',
  `agreed_at` DATETIME NULL COMMENT '全部必需协议签署完成时间',
  `opened_at` DATETIME NULL COMMENT '店铺开通时间（进入 OPENED）',
  `cancel_reason` VARCHAR(512) NULL COMMENT '逾期撤回/取消原因（系统 EXPIRE 或人工撤回）',
  `id_card_expiry` DATE NULL COMMENT '身份证有效期（O9）；可空表示长期/未采集，审核通过时快照到 merchant_store',
  `license_expiry` DATE NULL COMMENT '营业执照有效期（O9）；个人店可空，审核通过时快照到 merchant_store',
  `submitted_at` DATETIME NULL,
  `reviewed_at` DATETIME NULL,
  `reviewer_id` BIGINT NULL,
  `version` INT NOT NULL DEFAULT 0 COMMENT '补件/状态推进版本，配合行锁防并发覆盖',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_onboarding_active_subject` (`subject_type`, `active_fingerprint`),
  KEY `idx_onboarding_subject` (`subject_type`, `id_card_fingerprint`),
  KEY `idx_onboarding_status_submitted` (`status`, `submitted_at`),
  KEY `idx_onboarding_merchant` (`merchant_id`),
  KEY `idx_onboarding_token` (`access_token_hash`),
  CONSTRAINT fk_onboarding_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家入驻申请';

-- 29-1. 商家入驻证件附件（S3）：文件本体保存应用私有目录，此表只存元数据
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

-- 29. 入驻审核审计（T39）：每次状态推进、补件、驳回都留痕，不改历史申请
CREATE TABLE IF NOT EXISTS `merchant_onboarding_audit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `application_id` BIGINT UNSIGNED NOT NULL,
  `merchant_id` BIGINT UNSIGNED NULL,
  `action` VARCHAR(32) NOT NULL COMMENT 'CREATE/MATERIAL_UPDATED/SUBMIT/START_REVIEW/APPROVE/REJECT/SUPPLEMENT_REQUIRED/WITHDRAW/OPEN/EXPIRE',
  `from_status` VARCHAR(24) NULL,
  `to_status` VARCHAR(24) NULL,
  `operator_id` BIGINT NULL,
  `reason` VARCHAR(512) NULL,
  `reject_code` VARCHAR(32) NULL COMMENT '驳回/补件原因码快照，与申请单 reject_code 同字典',
  `review_stage` VARCHAR(16) NULL COMMENT '动作完成后的审核阶段 NONE/PRIMARY/SECONDARY/PASSED',
  `material_hash` VARCHAR(128) NULL COMMENT '材料摘要，不复制证件原文',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_onboarding_audit_app` (`application_id`, `created_at`),
  CONSTRAINT fk_onboarding_audit_app FOREIGN KEY (`application_id`) REFERENCES `merchant_onboarding_application` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家入驻审核审计';

-- 29-2. 协议版本（O3）：管理端发布/下线；同类型仅一条 PUBLISHED 为当前生效
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

-- 29-3. 协议同意记录（O3）：append-only，禁止更新/删除；开通闸门的签署证据
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
  `default_merchant_id` BIGINT UNSIGNED GENERATED ALWAYS AS (IF(`is_default` = 1, `merchant_id`, NULL)) STORED COMMENT '默认账户占位：仅默认账户非空，配合 uk_settlement_default 保证同店默认唯一',
  `verify_method` VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'O5 核验方式: MANUAL-人工核验, PAYOUT-小额打款验证；记录账户通过核验的方式',
  `replaces_account_id` BIGINT UNSIGNED NULL COMMENT 'O5 变更申请指向的旧账户；核验通过事务内切换默认并停用旧账户默认位',
  `effective_from` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `effective_to` DATETIME NULL COMMENT '换账户时停用旧账户，不原地覆盖历史',
  `verified_at` DATETIME NULL,
  `verified_by` BIGINT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_account_fingerprint` (`merchant_id`, `account_fingerprint`),
  UNIQUE KEY `uk_settlement_default` (`default_merchant_id`),
  KEY `idx_settlement_merchant_status` (`merchant_id`, `status`, `is_default`),
  KEY `idx_settlement_replaces` (`replaces_account_id`),
  CONSTRAINT fk_settlement_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家收款账户';

-- 31. 收款账户审核审计（T39）：登记/核验/驳回/停用留痕，不复制账号明文
CREATE TABLE IF NOT EXISTS `merchant_settlement_account_audit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `account_id` BIGINT UNSIGNED NOT NULL,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `action` VARCHAR(32) NOT NULL COMMENT 'REGISTER/VERIFY/REJECT/DISABLE；O5 新增 CHANGE_REQUESTED/CHANGE_VERIFIED/CHANGE_SWITCHED',
  `account_type` VARCHAR(16) NULL,
  `account_fingerprint` VARCHAR(128) NULL,
  `operator_id` BIGINT NULL,
  `reason` VARCHAR(512) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_settlement_audit_account` (`account_id`, `created_at`),
  CONSTRAINT fk_settlement_audit_account FOREIGN KEY (`account_id`) REFERENCES `merchant_settlement_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收款账户审核审计';

-- 31a. 收款账户打款验证（O5 可选增强）：平台向账户打随机小额，商家回填金额核验归属；
--      金额只由服务端生成，(account_id, attempt_no) 唯一防重，每账户最多 3 次尝试
CREATE TABLE IF NOT EXISTS `merchant_payout_verification` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `account_id` BIGINT UNSIGNED NOT NULL,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `attempt_no` INT NOT NULL COMMENT '第几次验证尝试，1-3',
  `amount` DECIMAL(12,2) NOT NULL COMMENT '服务端生成的校验金额，0.01-0.99；仅平台可见，不得透传给商家端',
  `currency` CHAR(3) NOT NULL DEFAULT 'CNY',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED/EXPIRED',
  `transfer_voucher_no` VARCHAR(128) NULL COMMENT '线下转账凭证号；通道接入前使用',
  `requested_by` BIGINT NULL COMMENT '发起人（超管）；确认人不得与发起人同一人',
  `requested_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at` DATETIME NOT NULL,
  `confirmed_at` DATETIME NULL,
  `confirmed_by` BIGINT NULL,
  `failure_code` VARCHAR(32) NULL COMMENT 'AMOUNT_MISMATCH/EXPIRED/ATTEMPTS_EXCEEDED',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payout_account_attempt` (`account_id`, `attempt_no`),
  KEY `idx_payout_pending_expire` (`status`, `expires_at`),
  KEY `idx_payout_merchant` (`merchant_id`, `created_at`),
  CONSTRAINT fk_payout_account FOREIGN KEY (`account_id`) REFERENCES `merchant_settlement_account` (`id`),
  CONSTRAINT chk_payout_amount CHECK (`amount` > 0.00 AND `amount` <= 1.00),
  CONSTRAINT chk_payout_attempt CHECK (`attempt_no` BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收款账户打款验证';

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
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '政策行最近变更时间，用于发现生效区间被事后改动',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_fee_policy_merchant_effective` (`merchant_id`, `effective_from`),
  KEY `idx_fee_policy_merchant_effective` (`merchant_id`, `effective_from`, `effective_to`),
  CONSTRAINT `chk_fee_policy_rate` CHECK ((`rate_type` = 'NORMAL' AND `rate_bps` BETWEEN 100 AND 1000)
      OR (`rate_type` = 'PROMOTION' AND `rate_bps` = 0)),
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
      AND `refunded_amount` <= `merchant_payable_amount`
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

-- 37. SaaS 月度技术服务费账单（F10）：与交易抽佣/结算批次分离，按月对店铺收取固定月费；
--     同店同月唯一，重复生成幂等返回既有账单；WAIVED 表示试用/豁免/0 月费，不产生应收；
--     本表只记录账单与人工线下缴费确认，不自动扣减 merchant_account，也不自动停复服。
CREATE TABLE IF NOT EXISTS `saas_fee_bill` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `bill_no` VARCHAR(64) NOT NULL COMMENT '账单号：SAAS-{merchantId}-{yyyyMM}',
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `billing_month` VARCHAR(7) NOT NULL COMMENT '账期月份 yyyy-MM（自然月，Asia/Shanghai）',
  `standard_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '按店铺月度费配置的标准应收，保留豁免前金额',
  `amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '本账单实际应收；WAIVED 时为 0.00',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PAID/WAIVED/OVERDUE',
  `waive_reason` VARCHAR(128) NULL COMMENT '免收原因：0 月费/EXEMPTED/试用期',
  `due_date` DATE NOT NULL COMMENT '缴费到期日（次月 10 日，代理细化规则待业务确认）',
  `issued_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `paid_at` DATETIME NULL,
  `paid_by` BIGINT NULL COMMENT '发起缴费确认的操作人',
  `confirmed_by` BIGINT NULL COMMENT '复核人；双人复核开关开启时必须与 paid_by 不同',
  `approval_mode` VARCHAR(16) NULL COMMENT 'SAME_OPERATOR/DUAL_APPROVAL',
  `payment_method` VARCHAR(16) NULL COMMENT '本期仅 OFFLINE（线下转账人工确认）',
  `voucher_no` VARCHAR(64) NULL COMMENT '线下转账凭证号',
  `remark` VARCHAR(255) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_saas_fee_bill_no` (`bill_no`),
  UNIQUE KEY `uk_saas_fee_merchant_month` (`merchant_id`, `billing_month`),
  KEY `idx_saas_fee_status_due` (`status`, `due_date`),
  CONSTRAINT fk_saas_fee_bill_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SaaS 月度技术服务费账单（与交易抽佣分离）';
