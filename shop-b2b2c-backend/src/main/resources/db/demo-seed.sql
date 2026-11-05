-- 文件：demo-seed.sql
-- 用途：开发/演示环境的演示账号、店铺、商品、卡密与引流广告种子，INSERT IGNORE 幂等。
-- 维护注意：
--   1. 本文件含固定演示口令(admin123)、虚构身份证/银行卡与明文演示卡密，禁止在生产库执行；
--   2. 生产通过 application-prod.yml 设置 spring.sql.init.mode=never，仅保留管理员受控初始化；
--   3. 商品分类属于运行参考数据，仍保留在 schema.sql，不在此重复；
--   4. 新增演示数据必须保持 INSERT IGNORE 与固定主键，避免重复启动产生副本。
-- ========================================================

-- 1. 初始超管账号 (演示密码: admin123, BCrypt 哈希)
INSERT IGNORE INTO `sys_user` (`id`, `merchant_id`, `username`, `password`, `real_name`, `role_code`, `is_active`)
VALUES (1, NULL, 'superadmin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '平台超级管理员', 'SUPERADMIN', 1);

-- 2. 初始演示商家 A (极速数卡官方自营店 - 虚拟服务标杆)
INSERT IGNORE INTO `merchant_store` (`id`, `store_code`, `store_name`, `logo_url`, `notice`, `merchant_type`, `owner_name`, `id_card_number`, `contact_phone`, `status`, `commission_rate`, `monthly_fee`, `fee_status`, `payment_channel_type`)
VALUES (1, 'STORE001', '极速数卡旗舰店', 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=100', '官方直营，全自动秒级极速发货！24小时在线客服售后无忧。', 1, '张三', '110101199003072391', '13800138000', 1, 3.00, 0.00, 'NORMAL', 'C');

-- 演示商家 A 管理员 (账号: seller01, 密码: admin123)
INSERT IGNORE INTO `sys_user` (`id`, `merchant_id`, `username`, `password`, `real_name`, `role_code`, `is_active`)
VALUES (2, 1, 'seller01', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '张三 (店长)', 'MERCHANT_ADMIN', 1);

-- 演示商家 A 微信支付进件账户
INSERT IGNORE INTO `merchant_payment_account` (`id`, `merchant_id`, `channel`, `sub_mchid`, `bank_account_name`, `bank_account_number`, `bank_name`, `apply_status`)
VALUES (1, 1, 'WECHAT', '1900008881', '张三', '6222021000123456789', '中国工商银行北京分行', 'PASSED');

-- 3. 初始演示商家 B (潮尚优选源头工厂店 - 实物电商标杆)
INSERT IGNORE INTO `merchant_store` (`id`, `store_code`, `store_name`, `logo_url`, `notice`, `merchant_type`, `owner_name`, `id_card_number`, `contact_phone`, `status`, `commission_rate`, `monthly_fee`, `fee_status`, `payment_channel_type`)
VALUES (2, 'STORE002', '潮尚优选源头工厂店', 'https://images.unsplash.com/photo-1441986300917-64674bd600d8?w=100', '源头好货工厂直供，专注重磅纯棉服饰与日用百货，全场现货48小时内发货！', 1, '李四', '310101199205123456', '13900139000', 1, 3.00, 0.00, 'NORMAL', 'C');

-- 演示商家 B 管理员 (账号: seller02, 密码: admin123)
INSERT IGNORE INTO `sys_user` (`id`, `merchant_id`, `username`, `password`, `real_name`, `role_code`, `is_active`)
VALUES (3, 2, 'seller02', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '李四 (店长)', 'MERCHANT_ADMIN', 1);

-- 演示商家 B 支付进件账户
INSERT IGNORE INTO `merchant_payment_account` (`id`, `merchant_id`, `channel`, `sub_mchid`, `bank_account_name`, `bank_account_number`, `bank_name`, `apply_status`)
VALUES (2, 2, 'WECHAT', '1900008882', '李四', '6217001000987654321', '中国建设银行上海分行', 'PASSED');

-- 5. 初始演示商品 1 (虚拟卡密/线上服务 - 腾讯视频VIP)
INSERT IGNORE INTO `product` (`id`, `merchant_id`, `category_id`, `title`, `sub_title`, `goods_type`, `main_image`, `service_desc`, `min_price`, `status`, `sort_order`)
VALUES (1, 1, 1, '腾讯视频 VIP 会员激活码【自动秒发卡密】', '支持手机/电脑/平板三端通用，官方正品激活码', 2, 'https://images.unsplash.com/photo-1574375927938-d5a98e8ffe85?w=500', '线上服务与卡密一经发货交付凭证，非商品质量问题不可退款。', 18.80, 1, 1);

INSERT IGNORE INTO `product_sku` (`id`, `product_id`, `merchant_id`, `sku_code`, `spec_data`, `price`, `stock_quantity`) VALUES
(1, 1, 1, 'TX-VIP-1M', '{"时长": "1个月 (月卡)", "类型": "直充激活卡密"}', 18.80, 5),
(2, 1, 1, 'TX-VIP-1Y', '{"时长": "12个月 (年卡)", "类型": "直充激活卡密"}', 178.00, 3);

-- 注入 5 张月卡卡密库存与 3 张年卡卡密库存
INSERT IGNORE INTO `product_virtual_card` (`id`, `product_id`, `sku_id`, `merchant_id`, `card_content`, `status`) VALUES
(1, 1, 1, 1, 'TXVIP-2026-A8K2-9D7F-1101', 0),
(2, 1, 1, 1, 'TXVIP-2026-B3J9-4C2E-1102', 0),
(3, 1, 1, 1, 'TXVIP-2026-K9M1-7L4X-1103', 0),
(4, 1, 1, 1, 'TXVIP-2026-W4R8-2P6Q-1104', 0),
(5, 1, 1, 1, 'TXVIP-2026-X7V5-9T3S-1105', 0),
(6, 1, 2, 1, 'TXVIP-2026-Y1-7K8M-9P2D-1201', 0),
(7, 1, 2, 1, 'TXVIP-2026-Y1-3F4G-5H6J-1202', 0),
(8, 1, 2, 1, 'TXVIP-2026-Y1-9Q8W-7E6R-1203', 0);

-- 初始演示商品 2 (实物商品 - 机械键盘定制键帽)
INSERT IGNORE INTO `product` (`id`, `merchant_id`, `category_id`, `title`, `sub_title`, `goods_type`, `main_image`, `min_price`, `status`, `sort_order`)
VALUES (2, 1, 3, '客制化 PBT 键帽 复古原厂高度 现货包邮', '加厚 PBT 材质，热升华不掉色，支持全配列', 1, 'https://images.unsplash.com/photo-1587829741301-dc798b83add3?w=500', 129.00, 1, 2);

INSERT IGNORE INTO `product_sku` (`id`, `product_id`, `merchant_id`, `sku_code`, `spec_data`, `price`, `stock_quantity`) VALUES
(3, 2, 1, 'KEYCAP-RETRO-BASE', '{"配色": "复古灰白 (基础套)", "高度": "原厂 Cherry 高度"}', 129.00, 50),
(4, 2, 1, 'KEYCAP-RETRO-FULL', '{"配色": "复古灰白 (全套增补)", "高度": "原厂 Cherry 高度"}', 189.00, 30);

-- 初始演示商品 3 (3C数码实物: 降噪蓝牙耳机)
INSERT IGNORE INTO `product` (`id`, `merchant_id`, `category_id`, `title`, `sub_title`, `goods_type`, `main_image`, `min_price`, `status`, `sort_order`)
VALUES (3, 1, 4, '【新品现货】ANC 主动降噪无线蓝牙耳机 空间音频 超长续航', '40dB 深度降噪，ENC 通话降噪，支持双设备无缝连接', 1, 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=500', 169.00, 1, 3);

INSERT IGNORE INTO `product_sku` (`id`, `product_id`, `merchant_id`, `sku_code`, `spec_data`, `price`, `stock_quantity`) VALUES
(5, 3, 1, 'HEADPHONE-BLACK', '{"颜色": "曜石黑", "版本": "旗舰降噪版"}', 169.00, 120),
(6, 3, 1, 'HEADPHONE-WHITE', '{"颜色": "云母白", "版本": "旗舰降噪版"}', 169.00, 95);

-- 初始演示商品 4 (服装实物: 重磅纯棉T恤)
INSERT IGNORE INTO `product` (`id`, `merchant_id`, `category_id`, `title`, `sub_title`, `goods_type`, `main_image`, `min_price`, `status`, `sort_order`)
VALUES (4, 2, 5, '源头厂货 260g 重磅纯棉落肩短袖T恤 纯色男女同款', '紧密赛络纺工艺，领口加厚双螺纹不松垮，多色现货批量直发', 1, 'https://images.unsplash.com/photo-1521572267360-ee0c2909d518?w=500', 39.90, 1, 4);

INSERT IGNORE INTO `product_sku` (`id`, `product_id`, `merchant_id`, `sku_code`, `spec_data`, `price`, `stock_quantity`) VALUES
(7, 4, 2, 'TSHIRT-WHITE-L', '{"颜色": "本白", "尺码": "L码 (175/92A)"}', 39.90, 200),
(8, 4, 2, 'TSHIRT-WHITE-XL', '{"颜色": "本白", "尺码": "XL码 (180/96A)"}', 39.90, 180),
(9, 4, 2, 'TSHIRT-BLACK-L', '{"颜色": "碳黑", "尺码": "L码 (175/92A)"}', 39.90, 150);

-- 初始演示商品 5 (食品生鲜: 坚果礼盒)
INSERT IGNORE INTO `product` (`id`, `merchant_id`, `category_id`, `title`, `sub_title`, `goods_type`, `main_image`, `min_price`, `status`, `sort_order`)
VALUES (5, 2, 7, '每日坚果大礼包 750g 礼盒装 混合坚果干果果仁 现货批发', '原色原味低温烘焙，0香精防腐剂，内含核桃仁腰果巴旦木蔓越莓', 1, 'https://images.unsplash.com/photo-1599599810769-bcde5a160d32?w=500', 58.00, 1, 5);

INSERT IGNORE INTO `product_sku` (`id`, `product_id`, `merchant_id`, `sku_code`, `spec_data`, `price`, `stock_quantity`) VALUES
(10, 5, 2, 'NUT-BOX-750G', '{"规格": "750g 欢享礼盒 (30包/盒)", "等级": "特级混合果仁"}', 58.00, 80);

-- 初始演示商品 6 (线上定制服务: 专家一对一咨询服务与技术定制)
INSERT IGNORE INTO `product` (`id`, `merchant_id`, `category_id`, `title`, `sub_title`, `goods_type`, `main_image`, `service_desc`, `min_price`, `status`, `sort_order`)
VALUES (6, 1, 2, '【专家一对一】全域私域代付商城系统架构设计与搭建辅导', '资深架构师在线指导，包含源码部署、收付通道调通与上线验收报告', 3, 'https://images.unsplash.com/photo-1551836022-d5d88e9218df?w=500', '线上定制服务一经卖家录入交付报告与源码凭据，非平台超管仲裁不可极速退款。', 499.00, 1, 6);

INSERT IGNORE INTO `product_sku` (`id`, `product_id`, `merchant_id`, `sku_code`, `spec_data`, `price`, `stock_quantity`) VALUES
(11, 6, 1, 'CONSULT-BASIC', '{"规格": "基础咨询版 (远程部署+操作手册)", "交付": "技术文档+远程调试"}', 499.00, 50),
(12, 6, 1, 'CONSULT-PRO', '{"规格": "企业尊享版 (定制开发+1年运维保障)", "交付": "专属工程师+全天候支持"}', 1999.00, 20);

-- 6. 代付成功页【我也想开店】引流广告种子
INSERT IGNORE INTO `platform_ad_config` (`id`, `ad_location`, `ad_title`, `ad_sub_title`, `banner_url`, `target_type`, `target_url`, `is_enabled`, `sort_order`)
VALUES (
  1,
  'PAYER_SUCCESS',
  '我也想开店 - 零门槛私域推单',
  '无需营业执照，个人身份证+卡号即可入驻，一键生成商品代付账单推给客户付款！',
  '/static/images/banner_merchant_recruit.png',
  'MERCHANT_APPLY',
  '/merchant/register',
  1,
  10
);
