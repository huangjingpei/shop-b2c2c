-- 文件：003_order_idempotency.sql
-- 用途：为建单幂等提供唯一摘要占位及响应快照表。
-- 维护注意：在目标连接库执行；响应含交易凭证，限制数据库访问。不要单独清理记录后重用旧键；不创建/切换数据库。
CREATE TABLE IF NOT EXISTS order_idempotency (
  request_key VARCHAR(64) NOT NULL PRIMARY KEY,
  request_hash VARCHAR(64) NOT NULL,
  response_json LONGTEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
