-- 文件：V2__s2_card_fingerprint_backfill.sql
-- 用途：T14 存量卡密指纹补算：为可直接读取明文的历史卡密回填 SHA-256 指纹，并标记无法补算的加密/空正文行。
-- 维护注意：
--  1. 本脚本只用于保留数据的存量库；新库结构仍以 db/schema.sql 为唯一入口。
--  2. 幂等：加列与回填均可重复执行；禁止 DROP TABLE、TRUNCATE、DELETE 或清空卡密/批次数据。
--  3. 加密行（ENC:v1:/ENC:v2:）与空正文在 SQL 中拿不到明文，统一标记 fingerprint_unavailable=1；
--     应用在下一次卡密导入时会逐行尝试解密并补算，解密失败只跳过、计数和告警，不中断导入。
--  4. (merchant_id, card_fingerprint) 存在唯一键：同店铺重复明文只回填最早一行，其余保持 NULL、标记 0；
--     其去重代表性由已回填的同一指纹行承担，运行时会再核对已存在指纹，避免 UPDATE 触发唯一冲突。
--  5. 需要 MySQL 8.0（ROW_NUMBER 窗口函数）；生产执行前须按 T21 要求备份。

-- 1. 幂等新增指纹补算状态列
SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'product_virtual_card' AND column_name = 'fingerprint_unavailable');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE product_virtual_card ADD COLUMN fingerprint_unavailable TINYINT NOT NULL DEFAULT 0 AFTER card_fingerprint',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 明文历史行回填指纹；同店铺同指纹只取最早一行，且避开已存在指纹，保证唯一键安全
UPDATE product_virtual_card vc
JOIN (
    SELECT id, merchant_id, fp
    FROM (
        SELECT id, merchant_id,
               SHA2(TRIM(card_content), 256) AS fp,
               ROW_NUMBER() OVER (PARTITION BY merchant_id, SHA2(TRIM(card_content), 256) ORDER BY id) AS rn
        FROM product_virtual_card
        WHERE card_fingerprint IS NULL
          AND card_content IS NOT NULL
          AND TRIM(card_content) <> ''
          AND card_content NOT LIKE 'ENC:v1:%'
          AND card_content NOT LIKE 'ENC:v2:%'
    ) ranked
    WHERE rn = 1
) candidate ON candidate.id = vc.id
LEFT JOIN (
    SELECT merchant_id, card_fingerprint
    FROM product_virtual_card
    WHERE card_fingerprint IS NOT NULL
    GROUP BY merchant_id, card_fingerprint
) existing ON existing.merchant_id = candidate.merchant_id AND existing.card_fingerprint = candidate.fp
SET vc.card_fingerprint = candidate.fp, vc.fingerprint_unavailable = 0
WHERE existing.card_fingerprint IS NULL;

-- 3. 仍未回填的行：加密/空正文标记无法补算；可读明文的重复行保持 0，避免把“可计算”误标为“不可计算”
UPDATE product_virtual_card
SET fingerprint_unavailable = CASE
    WHEN card_content IS NULL OR TRIM(card_content) = '' THEN 1
    WHEN card_content LIKE 'ENC:v1:%' OR card_content LIKE 'ENC:v2:%' THEN 1
    ELSE 0
END
WHERE card_fingerprint IS NULL;

-- 4. 已回填指纹的行状态归零，保证重复执行结果稳定
UPDATE product_virtual_card
SET fingerprint_unavailable = 0
WHERE card_fingerprint IS NOT NULL AND fingerprint_unavailable <> 0;
