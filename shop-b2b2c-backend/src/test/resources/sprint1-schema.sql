-- ========================================================
-- Shop B2B2C 全局全量初始化数据库脚本 (Single Source of Truth)
-- 适用: 开发期启动自愈 / 删库一键重建 (Drop & Re-create)
-- 位置: src/main/resources/schema.sql
-- ========================================================






-- 1. 商户店铺表
CREATE TABLE IF NOT EXISTS `merchant_store` (
  `id` BIGINT  NOT NULL AUTO_INCREMENT COMMENT '店铺主键ID',
  `store_code` VARCHAR(64) NOT NULL COMMENT '店铺唯一编码 (字母数字)',
  `store_name` VARCHAR(128) NOT NULL COMMENT '店铺名称',
  `logo_url` VARCHAR(255) DEFAULT NULL COMMENT '店铺Logo',
  `notice` VARCHAR(512) DEFAULT NULL COMMENT '店铺首页公告',
  `merchant_type` TINYINT NOT NULL DEFAULT 1 COMMENT '商户类型: 1-个人, 2-个体工商户, 3-企业',
  `owner_name` VARCHAR(64) NOT NULL COMMENT '经营者姓名 / 法人姓名',
  `id_card_number` VARCHAR(32) NOT NULL COMMENT '经营者身份证号码 (脱敏)',
  `contact_phone` VARCHAR(32) NOT NULL COMMENT '联系手机号',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '店铺状态: 0-待审, 1-正常营业, 2-打烊, 3-已封禁',
  `commission_rate` DECIMAL(5,2) NOT NULL DEFAULT 3.00 COMMENT '平台抽成比例(%)',
  `monthly_fee` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '月度技术服务费(元/月，前期=0)',
  `fee_free_until` DATETIME NULL COMMENT '免费期截止日期',
  `fee_status` VARCHAR(32) NOT NULL DEFAULT 'NORMAL' COMMENT '服务费状态: NORMAL(正常), TRIAL(免费期), OVERDUE(欠费)',
  `payment_channel_type` VARCHAR(16) NOT NULL DEFAULT 'C' COMMENT '支付模式: B(商户自主直收), C(Mock沙箱), A(平台电商收付通)',
  `custom_pay_qrcode` VARCHAR(512) NULL COMMENT '商户自主微信/支付宝收款码URL',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

-- 2. 微信/支付宝官方二级商户进件账户表
CREATE TABLE IF NOT EXISTS `merchant_payment_account` (
  `id` BIGINT  NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT  NOT NULL COMMENT '关联店铺ID',
  `channel` VARCHAR(32) NOT NULL COMMENT '渠道: WECHAT(电商收付通), ALIPAY(直付通)',
  `sub_mchid` VARCHAR(64) NOT NULL COMMENT '官方分配的二级商户号',
  `bank_account_name` VARCHAR(64) NOT NULL COMMENT '结算开户人姓名',
  `bank_account_number` VARCHAR(64) NOT NULL COMMENT '结算银行卡号',
  `bank_name` VARCHAR(128) NOT NULL COMMENT '开户银行支行名称',
  `apply_status` VARCHAR(32) NOT NULL DEFAULT 'PASSED' COMMENT '进件状态: AUDITING-审核中, PASSED-已开通, REJECTED-已驳回',
  `reject_reason` VARCHAR(255) DEFAULT NULL COMMENT '驳回原因',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

-- 3. 系统后台用户表 (RBAC 商家隔离)
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` BIGINT  NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT  DEFAULT NULL COMMENT '所属店铺ID (平台超管为NULL)',
  `username` VARCHAR(64) NOT NULL COMMENT '登录账号/手机号',
  `password` VARCHAR(128) NOT NULL COMMENT '密码 (BCrypt加密)',
  `real_name` VARCHAR(64) DEFAULT NULL COMMENT '真实姓名',
  `role_code` VARCHAR(32) NOT NULL COMMENT '角色: SUPERADMIN-平台超管, MERCHANT_ADMIN-店长, MERCHANT_STAFF-员工',
  `is_active` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 1-启用, 0-禁用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

-- 4. 商品分类表
CREATE TABLE IF NOT EXISTS `category` (
  `id` BIGINT  NOT NULL AUTO_INCREMENT,
  `parent_id` BIGINT  NOT NULL DEFAULT 0,
  `name` VARCHAR(64) NOT NULL,
  `icon` VARCHAR(255) DEFAULT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

-- 5. 商品主表
CREATE TABLE IF NOT EXISTS `product` (
  `id` BIGINT  NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT  NOT NULL COMMENT '所属店铺ID',
  `category_id` BIGINT  NOT NULL COMMENT '所属分类ID',
  `title` VARCHAR(255) NOT NULL COMMENT '商品标题',
  `sub_title` VARCHAR(255) DEFAULT NULL COMMENT '副标题/卖点',
  `goods_type` TINYINT NOT NULL DEFAULT 1 COMMENT '类型: 1-实物物流发货, 2-线上服务/虚拟免物流',
  `main_image` VARCHAR(255) NOT NULL COMMENT '主图封面URL',
  `detail_images` JSON DEFAULT NULL COMMENT '轮播与富文本内容',
  `service_desc` TEXT NULL COMMENT '线上服务交付说明及不可逆防退款强警示',
  `min_price` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '最低显示售价',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 0-已下架, 1-在售, 2-违规下架',
  `sort_order` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

-- 6. 商品规格 SKU 表
CREATE TABLE IF NOT EXISTS `product_sku` (
  `id` BIGINT  NOT NULL AUTO_INCREMENT,
  `product_id` BIGINT  NOT NULL COMMENT '关联商品ID',
  `merchant_id` BIGINT  NOT NULL COMMENT '所属店铺ID',
  `sku_code` VARCHAR(64) NOT NULL COMMENT 'SKU编码',
  `spec_data` JSON NOT NULL COMMENT '规格KV JSON (如: {"时长":"1个月 VIP"})',
  `price` DECIMAL(10,2) NOT NULL COMMENT '售价',
  `stock_quantity` INT NOT NULL DEFAULT 0 COMMENT '可用物理库存',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

-- 7. 虚拟卡密库存表
CREATE TABLE IF NOT EXISTS `product_virtual_card` (
  `id` BIGINT  NOT NULL AUTO_INCREMENT,
  `product_id` BIGINT  NOT NULL,
  `sku_id` BIGINT  NOT NULL,
  `merchant_id` BIGINT  NOT NULL,
  `card_content` TEXT NOT NULL COMMENT '卡密明细文本 (卡号/卡密/激活链接)',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0-未售出, 1-已售出核销, 2-作废',
  `order_sn` VARCHAR(64) DEFAULT NULL COMMENT '售出关联订单号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

-- 8. 交易订单主表 (含两段式代付字段)
CREATE TABLE IF NOT EXISTS `trade_order` (
  `id` BIGINT  NOT NULL AUTO_INCREMENT,
  `order_sn` VARCHAR(64) NOT NULL COMMENT '全局订单号',
  `merchant_id` BIGINT  NOT NULL COMMENT '所属店铺ID',
  `buyer_contact` VARCHAR(64) NOT NULL COMMENT '买家手机号或邮箱',
  `goods_type` TINYINT NOT NULL DEFAULT 1 COMMENT '商品类型: 1-实物, 2-线上服务/虚拟',
  `total_amount` DECIMAL(10,2) NOT NULL COMMENT '订单总金额',
  `pay_amount` DECIMAL(10,2) NOT NULL COMMENT '实付金额',
  `commission_rate` DECIMAL(5,2) NOT NULL DEFAULT 3.00 COMMENT '抽佣比率(%)',
  `commission_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '平台抽佣服务费',
  `merchant_settle_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '商户应收净货款',
  `order_status` VARCHAR(32) NOT NULL DEFAULT 'UNPAID' COMMENT 'UNPAID, PAID, SHIPPED, COMPLETED, REFUNDED, CLOSED',
  `sharing_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSING, FINISHED, FAILED',
  `payment_channel` VARCHAR(32) DEFAULT 'WECHAT' COMMENT 'WECHAT, ALIPAY',
  `pay_trade_no` VARCHAR(128) DEFAULT NULL COMMENT '第三方支付交易流水号',
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
  PRIMARY KEY (`id`)
);

-- 9. 订单商品明细表
CREATE TABLE IF NOT EXISTS `trade_order_item` (
  `id` BIGINT  NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT  NOT NULL,
  `order_sn` VARCHAR(64) NOT NULL,
  `product_id` BIGINT  NOT NULL,
  `sku_id` BIGINT  NOT NULL,
  `sku_code` VARCHAR(64) NOT NULL,
  `product_title` VARCHAR(255) NOT NULL,
  `spec_info` VARCHAR(255) DEFAULT NULL,
  `price` DECIMAL(10,2) NOT NULL,
  `quantity` INT NOT NULL,
  `subtotal` DECIMAL(10,2) NOT NULL,
  `card_details` TEXT DEFAULT NULL COMMENT '核销卡密文本快照',
  PRIMARY KEY (`id`)
);

-- 10. 代付成功页【我也想开店】平台运营广告配置表
CREATE TABLE IF NOT EXISTS `platform_ad_config` (
  `id` BIGINT  NOT NULL AUTO_INCREMENT PRIMARY KEY,
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
);

-- 11. 商户日/月财务对账账单表
CREATE TABLE IF NOT EXISTS `billing_statement` (
  `id` BIGINT  NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `shop_id` BIGINT  NOT NULL COMMENT '商户店铺ID',
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
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 12. 买家维权投诉与超管仲裁工单表
CREATE TABLE IF NOT EXISTS `complaint` (
  `id` BIGINT  NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `order_id` BIGINT  NOT NULL COMMENT '关联订单ID',
  `order_sn` VARCHAR(64) NOT NULL COMMENT '关联订单编号',
  `buyer_id` VARCHAR(64) NOT NULL COMMENT '买家唯一标识 (手机号/UID)',
  `merchant_id` BIGINT  NOT NULL COMMENT '被投诉商户ID',
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
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);



-- ========================================================
