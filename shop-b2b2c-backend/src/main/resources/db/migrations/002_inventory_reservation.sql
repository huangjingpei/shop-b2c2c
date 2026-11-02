-- 文件：002_inventory_reservation.sql
-- 用途：存量数据库新增库存预占表及到期查询索引的手动迁移。
-- 维护注意：先备份并在启动新版本前执行；使用当前连接库，不切库。索引创建按一次性迁移使用，重复执行前检查索引；禁止新旧版本混跑导致库存语义不同。
CREATE TABLE IF NOT EXISTS inventory_reservation (
  order_id BIGINT NOT NULL PRIMARY KEY,
  sku_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  owner_token VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  expires_at DATETIME NOT NULL
);
CREATE INDEX idx_reservation_expiry ON inventory_reservation(status, expires_at);
