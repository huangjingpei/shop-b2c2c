-- 文件：V18__f07_receiver_address_and_shipping.sql
-- 用途：F07 实物收货与运费增量结构：trade_order 加收件人姓名/手机号/省市区/详细地址快照四列；
--       merchant_store 加实物订单固定运费 shipping_fee。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. 逐列用 information_schema.columns 判断后动态加列，重复执行不报错；本脚本不修改/回填历史订单。
--  3. 历史实物订单地址为空表示“未采集”，不参与发货校验回填；新单由应用层强制校验，不能依赖数据库约束。
--  4. shipping_fee 默认 0（包邮）；不得给历史店铺写非零默认值，避免改变已展示价格口径。
--  5. 不写 DROP/TRUNCATE/DELETE 或删库语句。

-- 1) 订单：收件人姓名
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'receiver_name');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN receiver_name VARCHAR(64) DEFAULT NULL COMMENT ''F07 收件人姓名快照（仅实物订单，数字商品为空）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 订单：收件人手机号
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'receiver_phone');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN receiver_phone VARCHAR(32) DEFAULT NULL COMMENT ''F07 收件人手机号快照（仅实物订单，用于物流面单）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) 订单：收货省市区
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'receiver_region');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN receiver_region VARCHAR(64) DEFAULT NULL COMMENT ''F07 收货省市区快照（仅实物订单）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4) 订单：详细收货地址
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trade_order' AND column_name = 'receiver_address');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE trade_order ADD COLUMN receiver_address VARCHAR(200) DEFAULT NULL COMMENT ''F07 详细收货地址快照（仅实物订单）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5) 店铺：实物订单固定运费
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_store' AND column_name = 'shipping_fee');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE merchant_store ADD COLUMN shipping_fee DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''F07 实物订单每单固定运费(元)，0=包邮；仅实物单计入实付，不计入服务费基数''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
