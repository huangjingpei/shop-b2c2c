USE `shop_b2b2c`;

-- 扩充全品类统一商品分类
INSERT INTO `category` (`id`, `parent_id`, `name`, `icon`, `sort_order`) VALUES
(4, 0, '3C 数码 / 智能硬件', 'laptop', 1),
(5, 0, '服装鞋包 / 时尚潮流', 'shirt', 2),
(6, 0, '家居日用 / 百货批发', 'home', 3),
(7, 0, '食品生鲜 / 休闲零食', 'coffee', 4)
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`);

-- 扩充商户 B (潮流服饰官方自营)
INSERT INTO `merchant_store` (`id`, `store_code`, `store_name`, `logo_url`, `notice`, `merchant_type`, `owner_name`, `id_card_number`, `contact_phone`, `status`, `commission_rate`)
VALUES (2, 'STORE002', '潮尚优选源头工厂店', 'https://images.unsplash.com/photo-1441986300917-64674bd600d8?w=100', '源头好货工厂直供，专注重磅纯棉服饰与日用百货，全场现货48小时内发货！', 1, '李四', '310101199205123456', '13900139000', 1, 3.00)
ON DUPLICATE KEY UPDATE `store_name`=VALUES(`store_name`);

-- 商户 B 的二级分账账户
INSERT INTO `merchant_payment_account` (`id`, `merchant_id`, `channel`, `sub_mchid`, `bank_account_name`, `bank_account_number`, `bank_name`, `apply_status`)
VALUES (2, 2, 'WECHAT', '1900008882', '李四', '6217001000987654321', '中国建设银行上海分行', 'PASSED')
ON DUPLICATE KEY UPDATE `sub_mchid`=VALUES(`sub_mchid`);

-- 扩充商品 3 (3C数码实物: 降噪蓝牙耳机)
INSERT INTO `product` (`id`, `merchant_id`, `category_id`, `title`, `sub_title`, `goods_type`, `main_image`, `min_price`, `status`, `sort_order`)
VALUES (3, 1, 4, '【新品现货】ANC 主动降噪无线蓝牙耳机 空间音频 超长续航', '40dB 深度降噪，ENC 通话降噪，支持双设备无缝连接', 1, 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=500', 169.00, 1, 1)
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`);

INSERT INTO `product_sku` (`id`, `product_id`, `merchant_id`, `sku_code`, `spec_data`, `price`, `stock_quantity`) VALUES
(5, 3, 1, 'HEADPHONE-BLACK', '{"颜色": "曜石黑", "版本": "旗舰降噪版"}', 169.00, 120),
(6, 3, 1, 'HEADPHONE-WHITE', '{"颜色": "云母白", "版本": "旗舰降噪版"}', 169.00, 95)
ON DUPLICATE KEY UPDATE `price`=VALUES(`price`);

-- 扩充商品 4 (服装实物: 重磅纯棉T恤)
INSERT INTO `product` (`id`, `merchant_id`, `category_id`, `title`, `sub_title`, `goods_type`, `main_image`, `min_price`, `status`, `sort_order`)
VALUES (4, 2, 5, '源头厂货 260g 重磅纯棉落肩短袖T恤 纯色男女同款', '紧密赛络纺工艺，领口加厚双螺纹不松垮，多色现货批量直发', 1, 'https://images.unsplash.com/photo-1521572267360-ee0c2909d518?w=500', 39.90, 1, 2)
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`);

INSERT INTO `product_sku` (`id`, `product_id`, `merchant_id`, `sku_code`, `spec_data`, `price`, `stock_quantity`) VALUES
(7, 4, 2, 'TSHIRT-WHITE-L', '{"颜色": "本白", "尺码": "L码 (175/92A)"}', 39.90, 200),
(8, 4, 2, 'TSHIRT-WHITE-XL', '{"颜色": "本白", "尺码": "XL码 (180/96A)"}', 39.90, 180),
(9, 4, 2, 'TSHIRT-BLACK-L', '{"颜色": "碳黑", "尺码": "L码 (175/92A)"}', 39.90, 150)
ON DUPLICATE KEY UPDATE `price`=VALUES(`price`);

-- 扩充商品 5 (食品生鲜: 坚果礼盒)
INSERT INTO `product` (`id`, `merchant_id`, `category_id`, `title`, `sub_title`, `goods_type`, `main_image`, `min_price`, `status`, `sort_order`)
VALUES (5, 2, 7, '每日坚果大礼包 750g 礼盒装 混合坚果干果果仁 现货批发', '原色原味低温烘焙，0香精防腐剂，内含核桃仁腰果巴旦木蔓越莓', 1, 'https://images.unsplash.com/photo-1599599810769-bcde5a160d32?w=500', 58.00, 1, 3)
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`);

INSERT INTO `product_sku` (`id`, `product_id`, `merchant_id`, `sku_code`, `spec_data`, `price`, `stock_quantity`) VALUES
(10, 5, 2, 'NUT-BOX-750G', '{"规格": "750g 欢享礼盒 (30包/盒)", "等级": "特级混合果仁"}', 58.00, 80)
ON DUPLICATE KEY UPDATE `price`=VALUES(`price`);
