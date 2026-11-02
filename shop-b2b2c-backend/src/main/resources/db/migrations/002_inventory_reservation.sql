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
