-- ========================================================
-- B2B2C 平台全套数据库初始化脚本 (MySQL 8.0+)
-- Database: shop_b2b2c
-- ========================================================

CREATE DATABASE IF NOT EXISTS `shop_b2b2c` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `shop_b2b2c`;

SET FOREIGN_KEY_CHECKS = 0;

-- 1. 商户店铺表
DROP TABLE IF EXISTS `merchant_store`;
CREATE TABLE `merchant_store` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '店铺主键ID',
  `store_code` VARCHAR(64) NOT NULL COMMENT '店铺唯一编码 (字母数字)',
  `store_name` VARCHAR(128) NOT NULL COMMENT '店铺名称',
  `logo_url` VARCHAR(255) DEFAULT NULL COMMENT '店铺Logo',
  `notice` VARCHAR(512) DEFAULT NULL COMMENT '店铺首页公告',
  `merchant_type` TINYINT NOT NULL DEFAULT 1 COMMENT '商户类型: 1-个人自然人, 2-个体工商户, 3-企业',
  `owner_name` VARCHAR(64) NOT NULL COMMENT '经营者真实姓名 / 法人姓名',
  `id_card_number` VARCHAR(32) NOT NULL COMMENT '经营者身份证号码 (脱敏或加密)',
  `contact_phone` VARCHAR(32) NOT NULL COMMENT '联系手机号',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '店铺状态: 0-待审核, 1-正常营业, 2-打烊歇业, 3-已封禁',
  `commission_rate` DECIMAL(5,2) NOT NULL DEFAULT 3.00 COMMENT '平台抽成比例(%)，如3.00代表3%',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_store_code` (`store_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户店铺主表';

-- 2. 微信/支付宝官方二级商户进件账户表
DROP TABLE IF EXISTS `merchant_payment_account`;
CREATE TABLE `merchant_payment_account` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '关联店铺ID',
  `channel` VARCHAR(32) NOT NULL COMMENT '渠道: WECHAT(电商收付通), ALIPAY(直付通)',
  `sub_mchid` VARCHAR(64) NOT NULL COMMENT '官方分配的二级商户号',
  `bank_account_name` VARCHAR(64) NOT NULL COMMENT '结算开户人姓名',
  `bank_account_number` VARCHAR(64) NOT NULL COMMENT '结算银行卡号',
  `bank_name` VARCHAR(128) NOT NULL COMMENT '开户银行支行名称',
  `apply_status` VARCHAR(32) NOT NULL DEFAULT 'PASSED' COMMENT '进件状态: AUDITING-审核中, PASSED-已开通, REJECTED-已驳回',
  `reject_reason` VARCHAR(255) DEFAULT NULL COMMENT '驳回原因',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_channel` (`merchant_id`, `channel`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户二级收付通进件账户表';

-- 3. 系统后台用户表 (RBAC 商家隔离)
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '所属店铺ID (平台超管为NULL)',
  `username` VARCHAR(64) NOT NULL COMMENT '登录账号/手机号',
  `password` VARCHAR(128) NOT NULL COMMENT '密码 (BCrypt加密)',
  `real_name` VARCHAR(64) DEFAULT NULL COMMENT '真实姓名',
  `role_code` VARCHAR(32) NOT NULL COMMENT '角色: SUPERADMIN-平台超管, MERCHANT_ADMIN-商家店长, MERCHANT_STAFF-商家员工',
  `is_active` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 1-启用, 0-禁用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统管理用户表';

-- 4. 商品分类表
DROP TABLE IF EXISTS `category`;
CREATE TABLE `category` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `name` VARCHAR(64) NOT NULL,
  `icon` VARCHAR(255) DEFAULT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

-- 5. 商品主表
DROP TABLE IF EXISTS `product`;
CREATE TABLE `product` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID',
  `category_id` BIGINT UNSIGNED NOT NULL COMMENT '所属分类ID',
  `title` VARCHAR(255) NOT NULL COMMENT '商品标题',
  `sub_title` VARCHAR(255) DEFAULT NULL COMMENT '副标题/卖点',
  `goods_type` TINYINT NOT NULL DEFAULT 1 COMMENT '类型: 1-实物商品, 2-虚拟卡密(自动发货)',
  `main_image` VARCHAR(255) NOT NULL COMMENT '主图封面URL',
  `detail_images` JSON DEFAULT NULL COMMENT '商品轮播与富文本内容',
  `min_price` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '最低显示售价',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 0-已下架, 1-在售, 2-违规下架',
  `sort_order` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_merchant_product` (`merchant_id`, `status`),
  KEY `idx_category` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品主表';

-- 6. 商品变体 SKU 表
DROP TABLE IF EXISTS `product_sku`;
CREATE TABLE `product_sku` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `product_id` BIGINT UNSIGNED NOT NULL COMMENT '关联商品ID',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID (冗余加快隔离检索)',
  `sku_code` VARCHAR(64) NOT NULL COMMENT 'SKU编码',
  `spec_data` JSON NOT NULL COMMENT '规格KV JSON (如: {"规格":"1个月 VIP", "版本":"标准版"})',
  `price` DECIMAL(10,2) NOT NULL COMMENT '售价',
  `stock_quantity` INT NOT NULL DEFAULT 0 COMMENT '可用物理库存',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_code` (`sku_code`),
  KEY `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品规格SKU表';

-- 7. 虚拟卡密库存库 (16688 自动发卡核心)
DROP TABLE IF EXISTS `product_virtual_card`;
CREATE TABLE `product_virtual_card` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `product_id` BIGINT UNSIGNED NOT NULL,
  `sku_id` BIGINT UNSIGNED NOT NULL,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `card_content` TEXT NOT NULL COMMENT '卡密明细文本 (卡号/卡密/激活链接)',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0-未售出, 1-已售出核销, 2-作废',
  `order_sn` VARCHAR(64) DEFAULT NULL COMMENT '售出关联订单号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_sku_status` (`sku_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='虚拟商品卡密库存表';

-- 8. 交易订单主表
DROP TABLE IF EXISTS `trade_order`;
CREATE TABLE `trade_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_sn` VARCHAR(64) NOT NULL COMMENT '全局订单号',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID (单店独立结算)',
  `buyer_contact` VARCHAR(64) NOT NULL COMMENT '买家手机号或邮箱 (免密查单关键字段)',
  `goods_type` TINYINT NOT NULL DEFAULT 1 COMMENT '商品类型: 1-实物, 2-虚拟卡密',
  `total_amount` DECIMAL(10,2) NOT NULL COMMENT '订单总金额',
  `pay_amount` DECIMAL(10,2) NOT NULL COMMENT '实付金额',
  `commission_rate` DECIMAL(5,2) NOT NULL COMMENT '抽佣比率(%)',
  `commission_amount` DECIMAL(10,2) NOT NULL COMMENT '平台抽佣技术服务费',
  `merchant_settle_amount` DECIMAL(10,2) NOT NULL COMMENT '商户应收净货款',
  `order_status` VARCHAR(32) NOT NULL DEFAULT 'UNPAID' COMMENT 'UNPAID-待支付, PAID-已付款/待发货, SHIPPED-已发货, COMPLETED-已完成, REFUNDED-已退款, CLOSED-已取消',
  `sharing_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING-待分账, PROCESSING-分账中, FINISHED-分账成功, FAILED-分账失败',
  `payment_channel` VARCHAR(32) DEFAULT NULL COMMENT '支付渠道: WECHAT, ALIPAY',
  `pay_trade_no` VARCHAR(128) DEFAULT NULL COMMENT '第三方支付交易流水号',
  `logistics_company` VARCHAR(64) DEFAULT NULL COMMENT '快递物流公司',
  `tracking_number` VARCHAR(128) DEFAULT NULL COMMENT '快递单号',
  `delivery_time` DATETIME DEFAULT NULL COMMENT '发货时间',
  `finish_time` DATETIME DEFAULT NULL COMMENT '完成时间',
  `query_token` VARCHAR(64) NOT NULL COMMENT '买家免密查单安全凭证',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_sn` (`order_sn`),
  KEY `idx_merchant_order` (`merchant_id`, `order_status`),
  KEY `idx_contact` (`buyer_contact`),
  KEY `idx_query_token` (`query_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易订单主表';

-- 9. 订单商品明细表
DROP TABLE IF EXISTS `trade_order_item`;
CREATE TABLE `trade_order_item` (
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
  `card_details` TEXT DEFAULT NULL COMMENT '虚拟商品的核销卡密文本快照',
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

SET FOREIGN_KEY_CHECKS = 1;

-- ========================================================
-- 种子初始数据
-- ========================================================

-- 初始超管账号 (密码: admin123, BCrypt 哈希: $2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2)
INSERT INTO `sys_user` (`id`, `merchant_id`, `username`, `password`, `real_name`, `role_code`, `is_active`) 
VALUES (1, NULL, 'superadmin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '平台超级管理员', 'SUPERADMIN', 1);

-- 初始入驻演示商家 A (极速数卡官方自营店)
INSERT INTO `merchant_store` (`id`, `store_code`, `store_name`, `logo_url`, `notice`, `merchant_type`, `owner_name`, `id_card_number`, `contact_phone`, `status`, `commission_rate`)
VALUES (1, 'STORE001', '极速数卡旗舰店', 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=100', '官方直营，全自动秒级极速发货！24小时在线客服售后无忧。', 1, '张三', '110101199003072391', '13800138000', 1, 3.00);

-- 演示商家 A 的管理员账号 (账号: seller01, 密码: admin123)
INSERT INTO `sys_user` (`id`, `merchant_id`, `username`, `password`, `real_name`, `role_code`, `is_active`)
VALUES (2, 1, 'seller01', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '张三 (店长)', 'MERCHANT_ADMIN', 1);

-- 演示商家 A 的微信直付通二级商户进件信息
INSERT INTO `merchant_payment_account` (`id`, `merchant_id`, `channel`, `sub_mchid`, `bank_account_name`, `bank_account_number`, `bank_name`, `apply_status`)
VALUES (1, 1, 'WECHAT', '1900008881', '张三', '6222021000123456789', '中国工商银行北京分行', 'PASSED');

-- 初始平台商品分类
INSERT INTO `category` (`id`, `parent_id`, `name`, `icon`, `sort_order`) VALUES
(1, 0, '数字权益 / 充值卡券', 'tickets', 1),
(2, 0, '游戏点卡 / CDKey', 'gamepad', 2),
(3, 0, '数码周边 / 现货硬件', 'laptop', 3);

-- 初始演示商品 1 (虚拟卡密 - 腾讯视频VIP)
INSERT INTO `product` (`id`, `merchant_id`, `category_id`, `title`, `sub_title`, `goods_type`, `main_image`, `min_price`, `status`)
VALUES (1, 1, 1, '腾讯视频 VIP 会员激活码【自动秒发卡密】', '支持手机/电脑/平板三端通用，官方正品激活码', 2, 'https://images.unsplash.com/photo-1574375927938-d5a98e8ffe85?w=500', 18.80, 1);

INSERT INTO `product_sku` (`id`, `product_id`, `merchant_id`, `sku_code`, `spec_data`, `price`, `stock_quantity`) VALUES
(1, 1, 1, 'TX-VIP-1M', '{"时长": "1个月 (月卡)", "类型": "直充激活卡密"}', 18.80, 5),
(2, 1, 1, 'TX-VIP-1Y', '{"时长": "12个月 (年卡)", "类型": "直充激活卡密"}', 178.00, 3);

-- 注入 5 张月卡卡密库存
INSERT INTO `product_virtual_card` (`product_id`, `sku_id`, `merchant_id`, `card_content`, `status`) VALUES
(1, 1, 1, 'TXVIP-2026-A8K2-9D7F-1101', 0),
(1, 1, 1, 'TXVIP-2026-B3J9-4C2E-1102', 0),
(1, 1, 1, 'TXVIP-2026-K9M1-7L4X-1103', 0),
(1, 1, 1, 'TXVIP-2026-W4R8-2P6Q-1104', 0),
(1, 1, 1, 'TXVIP-2026-X7V5-9T3S-1105', 0);

-- 注入 3 张年卡卡密库存
INSERT INTO `product_virtual_card` (`product_id`, `sku_id`, `merchant_id`, `card_content`, `status`) VALUES
(1, 2, 1, 'TXVIP-YEAR-GOLD-8899-AAAA', 0),
(1, 2, 1, 'TXVIP-YEAR-GOLD-8899-BBBB', 0),
(1, 2, 1, 'TXVIP-YEAR-GOLD-8899-CCCC', 0);

-- 初始演示商品 2 (实物商品 - 机械键盘定制键帽)
INSERT INTO `product` (`id`, `merchant_id`, `category_id`, `title`, `sub_title`, `goods_type`, `main_image`, `min_price`, `status`)
VALUES (2, 1, 3, '客制化 PBT 键帽 复古原厂高度 现货包邮', '加厚 PBT 材质，热升华不掉色，支持全配列', 1, 'https://images.unsplash.com/photo-1587829741301-dc798b83add3?w=500', 129.00, 1);

INSERT INTO `product_sku` (`id`, `product_id`, `merchant_id`, `sku_code`, `spec_data`, `price`, `stock_quantity`) VALUES
(3, 2, 1, 'KEYCAP-RETRO-BASE', '{"配色": "复古灰白 (基础套)", "高度": "原厂 Cherry 高度"}', 129.00, 50),
(4, 2, 1, 'KEYCAP-RETRO-FULL', '{"配色": "复古灰白 (全套增补)", "高度": "原厂 Cherry 高度"}', 189.00, 30);
