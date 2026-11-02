-- ========================================================
-- Step 1 数据库与实体增量迁移脚本 (MySQL 8.0 标准语法)
-- 适用数据库: MySQL 8.0+
-- 目标数据库: shop_b2b2c
-- ========================================================

USE `shop_b2b2c`;

SET FOREIGN_KEY_CHECKS = 0;

-- 1. 扩展 merchant_store 表
ALTER TABLE `merchant_store`
  ADD COLUMN `monthly_fee` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '月度技术服务费(元/月)' AFTER `commission_rate`,
  ADD COLUMN `fee_free_until` DATETIME NULL COMMENT '免费期截止日期' AFTER `monthly_fee`,
  ADD COLUMN `fee_status` VARCHAR(32) NOT NULL DEFAULT 'NORMAL' COMMENT '服务费状态: NORMAL(正常), TRIAL(免费期), OVERDUE(逾期欠费)' AFTER `fee_free_until`,
  ADD COLUMN `payment_channel_type` VARCHAR(16) NOT NULL DEFAULT 'C' COMMENT '支付渠道模式: B(商户自主直收), C(Mock沙箱), A(平台收付通)' AFTER `fee_status`,
  ADD COLUMN `custom_pay_qrcode` VARCHAR(512) NULL COMMENT '商户自主微信/支付宝收款二维码图片URL' AFTER `payment_channel_type`;

-- 2. 扩展 product 表
ALTER TABLE `product`
  ADD COLUMN `service_desc` TEXT NULL COMMENT '线上服务交付说明及不可逆防退款强警示' AFTER `detail_images`;

-- 3. 扩展 trade_order 表
ALTER TABLE `trade_order`
  ADD COLUMN `peer_pay_token` VARCHAR(64) NULL COMMENT '代付防撞唯一令牌' AFTER `query_token`,
  ADD COLUMN `peer_pay_type` TINYINT NOT NULL DEFAULT 0 COMMENT '代付类型: 0-自主支付, 1-买家求助代付, 2-卖家代客推单' AFTER `peer_pay_token`,
  ADD COLUMN `peer_payer_id` VARCHAR(128) DEFAULT NULL COMMENT '实际代付付款人标识 (OpenID/UID/流水号)' AFTER `peer_pay_type`,
  ADD COLUMN `peer_status` VARCHAR(32) NOT NULL DEFAULT 'INIT' COMMENT '代付状态: INIT, RESERVED_LOCK, PAID, EXPIRED, CANCELLED' AFTER `peer_payer_id`,
  ADD COLUMN `peer_message` VARCHAR(255) DEFAULT NULL COMMENT '买家个性求助留言' AFTER `peer_status`,
  ADD COLUMN `poster_url` VARCHAR(512) DEFAULT NULL COMMENT '生成的带参代付海报图片访问路径' AFTER `peer_message`,
  ADD COLUMN `service_voucher` TEXT DEFAULT NULL COMMENT '线上服务交付凭证 (卡密/网盘/交付报告)' AFTER `poster_url`,
  ADD COLUMN `lock_expire_time` DATETIME DEFAULT NULL COMMENT '两段式库存预扣到期时间' AFTER `service_voucher`;

-- 为 peer_pay_token 增加防碰撞唯一索引
CREATE UNIQUE INDEX `uk_peer_pay_token` ON `trade_order` (`peer_pay_token`);

-- 4. 新建代付成功页【我也想开店】平台运营广告配置表
CREATE TABLE IF NOT EXISTS `platform_ad_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `ad_location` VARCHAR(64) NOT NULL DEFAULT 'PAYER_SUCCESS' COMMENT '广告位置标识',
  `ad_title` VARCHAR(128) NOT NULL COMMENT '主标题，如：我也想开店',
  `ad_sub_title` VARCHAR(255) DEFAULT NULL COMMENT '副标题，如：0元免照开店，像TA一样私域极速推单收款',
  `banner_url` VARCHAR(512) DEFAULT NULL COMMENT '宣传海报图/横幅URL',
  `target_type` VARCHAR(32) NOT NULL DEFAULT 'MERCHANT_APPLY' COMMENT '跳转类型: MERCHANT_APPLY(唤起极简开店), CUSTOM_URL(外部链接)',
  `target_url` VARCHAR(512) DEFAULT NULL COMMENT '跳转目标链接或动作参数',
  `is_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用: 1-启用, 0-禁用',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台运营引流广告配置表';

-- 5. 新建商户日/月财务对账账单表
CREATE TABLE IF NOT EXISTS `billing_statement` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `shop_id` BIGINT UNSIGNED NOT NULL COMMENT '商户店铺ID',
  `store_name` VARCHAR(128) NOT NULL COMMENT '商户店铺名称',
  `period_date` DATE NOT NULL COMMENT '对账归属日期 (YYYY-MM-DD)',
  `period_type` VARCHAR(16) NOT NULL DEFAULT 'DAILY' COMMENT '周期类型: DAILY(日对账), MONTHLY(月对账)',
  `gmv` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '当日/月总交易GMV',
  `peer_pay_gmv` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '代付渠道成交额',
  `direct_pay_gmv` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '自主支付成交额',
  `orders_cnt` INT NOT NULL DEFAULT 0 COMMENT '有效订单笔数',
  `refund_amt` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '退款总金额',
  `fee_amt` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '平台软件技术服务费扣收额',
  `status` VARCHAR(32) NOT NULL DEFAULT 'SETTLED' COMMENT '账单状态: PENDING, SETTLED, DISPUTED',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_shop_period` (`shop_id`, `period_date`, `period_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户财务对账账单汇总表';

-- 6. 新建买家维权投诉与超管仲裁工单表
CREATE TABLE IF NOT EXISTS `complaint` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '关联订单ID',
  `order_sn` VARCHAR(64) NOT NULL COMMENT '关联订单编号',
  `buyer_id` VARCHAR(64) NOT NULL COMMENT '买家唯一标识 (手机号/UID)',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '被投诉商户ID',
  `complaint_type` VARCHAR(64) NOT NULL COMMENT '投诉类型: QUALITY(质量), FAKE(假冒), SERVICE_NOT_DELIVERED(未履约), OTHER(其他)',
  `content` TEXT NOT NULL COMMENT '买家投诉详细说明',
  `evidence_images` JSON DEFAULT NULL COMMENT '买家举证凭证图数组',
  `merchant_reply` TEXT DEFAULT NULL COMMENT '商家答辩说明与举证',
  `merchant_reply_time` DATETIME DEFAULT NULL COMMENT '商家答复时间',
  `admin_arbitration` TEXT DEFAULT NULL COMMENT '平台超管官方仲裁结论',
  `admin_arbitration_time` DATETIME DEFAULT NULL COMMENT '超管仲裁时间',
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '工单状态: PENDING(待处理), MERCHANT_REPLIED(商家已答复), ARBITRATING(超管仲裁中), RESOLVED(已结案), REJECTED(已驳回)',
  `refund_decision` VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT '退款判定: NONE, FULL_REFUND, PARTIAL_REFUND',
  `refund_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '判定退款金额',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_order_id` (`order_id`),
  KEY `idx_merchant_id` (`merchant_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='买家维权投诉与平台官方仲裁表';

-- 7. 插入初始化种子数据：【我也想开店】代付成功页引流广告
INSERT INTO `platform_ad_config` (`ad_location`, `ad_title`, `ad_sub_title`, `banner_url`, `target_type`, `target_url`, `is_enabled`, `sort_order`)
VALUES (
  'PAYER_SUCCESS',
  '我也想开店 —— 零门槛私域推单',
  '无需营业执照，个人身份证+卡号即可入驻，一键生成商品代付账单推给客户付款！',
  '/static/images/banner_merchant_recruit.png',
  'MERCHANT_APPLY',
  '/merchant/register',
  1,
  10
);

SET FOREIGN_KEY_CHECKS = 1;
