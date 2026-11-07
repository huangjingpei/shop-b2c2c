-- 文件：V16__f12_card_reissue.sql
-- 用途：F12 卡密补发授权增量结构：card_reissue 只追加记录表（源已售卡主键幂等、新卡唯一、含订单号/操作人/原因/库存快照）。
-- 维护注意：
--  1. 新库结构以 db/schema.sql 为唯一入口；本脚本只用于保留数据的开发库，两者不得互相替代。
--  2. 幂等：CREATE TABLE IF NOT EXISTS 可重复执行；不修改已有数据，不写 DROP/TRUNCATE/DELETE/删库语句。
--  3. 本表不回填历史发卡数据；源卡保持已售状态，订单全额退款时源卡与新卡按既有 voidSoldCardsByOrderSn 语义一并作废。
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
