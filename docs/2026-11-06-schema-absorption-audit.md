# V1–V6 语句级吸收清单（静态比对，2026-11-06）

<!--
文件：2026-11-06-schema-absorption-audit.md
用途：逐条列出 V1–V6 中每一个实际 DDL 语句的出处、原始 SQL、意图、在 schema.sql 的落点与最终形态；
      用于在删除迁移脚本前证明“V 系列意图未落空”。本文件只做静态文本比对，未连接 MySQL、未编译、未运行测试。
维护注意：
 1. 状态最高为“已实现·待人工验证”；真实 MySQL 建表/外键/触发器行为须由用户演练后回填。
 2. 若后续修改 schema.sql，必须同步更新本清单；新增迁移脚本不得绕过本清单直接删除。
 3. V 系列中的 SET/PREPARE/EXECUTE/DEALLOCATE 是 information_schema 容错脚手架，不是结构语义，不写入 schema.sql。
-->

## 1. 范围与方法

- 对象：`db/migrations/V1–V6` 共 6 个脚本。
- 方法：抽取脚本中每一个实际执行的 DDL 字符串（`ALTER`/`CREATE`/`DROP`/`RENAME`），再到 `db/schema.sql` 按表名、列名、索引名、外键名查找等价定义；比较类型、长度、精度、`NOT NULL`、`DEFAULT`、`AUTO_INCREMENT`、`COMMENT`、索引列序、外键子/父列。
- 迁移脚本的 `SET @...`、`PREPARE/EXECUTE/DEALLOCATE`、`information_schema` 判断与 `AFTER` 位置仅用于改存量库，属于执行技巧，不写入 `schema.sql`；其表达的约束/语义已还原为建表定义。
- 结论先行：**86 条 DDL 意图全部在 `schema.sql` 有等价定义；缺失 0 条、定义不同 0 条。** 其中 2 组存量库重建技巧（V1 索引先建后删再改名、V4 先建新唯一键再删旧键，共涉及 3 条不直接落地的语句），其最终状态已体现在 `schema.sql`。
- V1–V6 中**没有**触发器、存储过程、函数、事件、CHECK 约束或生成列（已用关键字全量检索确认）；V3 是唯一在迁移脚本内建表的语句，含主键与 `AUTO_INCREMENT`，已在 `schema.sql` 等价存在。`schema.sql` 现有的 `merchant_account_ledger` append-only 触发器不来自 V1–V6。

- 容错脚手架：6 个脚本中 `SET @...` / `PREPARE` / `EXECUTE` / `DEALLOCATE PREPARE` 共 **428 条**。它们只用于“结构已存在则跳过”，不是结构语义；下表每条 DDL 意图均已还原为建表定义，脚手架不写入 `schema.sql`。

## 2. 语句级清单（共 86 条）


### V1__s1_order_lifecycle.sql

| # | 出处 | 原始 SQL（执行形态） | 这条语句想干什么 | schema.sql 落点 | 最终形态 |
| --- | --- | --- | --- | --- | --- |
| 1 | L13 #1 | <code>ALTER TABLE trade_order ADD INDEX idx_order_created (created_at, id)</code> | 订单分页按 created_at + id 稳定排序 | schema.sql 已有等价 | <code>L228 KEY `idx_order_created` (`created_at`, `id`),</code> |
| 2 | L23 #2 | <code>ALTER TABLE trade_order ADD INDEX idx_merchant_created (merchant_id, created_at, id)</code> | 商户维度订单分页按 created_at + id 稳定排序 | schema.sql 已有等价 | <code>L229 KEY `idx_merchant_created` (`merchant_id`, `created_at`, `id`),</code> |
| 3 | L33 #3 | <code>ALTER TABLE trade_order ADD INDEX idx_order_expiry (order_status, lock_expire_time, id)</code> | 超时未支付订单扫描：order_status + lock_expire_time + id | schema.sql 已有等价 | <code>L230 KEY `idx_order_expiry` (`order_status`, `lock_expire_time`, `id`),</code> |
| 4 | L43 #4 | <code>ALTER TABLE inventory_reservation ADD COLUMN attempt_key VARCHAR(64) NULL AFTER owner_token</code> | inventory_reservation.attempt_key：预占所有权与幂等尝试键分离，避免重发覆盖所有权（T10） | schema.sql 已有等价 | <code>L362 attempt_key VARCHAR(64) NULL,</code> |
| 5 | L52 #5 | <code>ALTER TABLE inventory_reservation ADD COLUMN reissue_count INT NOT NULL DEFAULT 0 AFTER attempt_key</code> | inventory_reservation.reissue_count：记录预占重发次数，支持重发上限与审计（T10） | schema.sql 已有等价 | <code>L363 reissue_count INT NOT NULL DEFAULT 0,</code> |
| 6 | L68 #6 | <code>ALTER TABLE inventory_reservation ADD INDEX idx_reservation_expiry_v2 (status, expires_at, order_id)</code> | 先建同列临时索引，避免重建窗口丢索引（存量库技巧） | 本阶段不需要（存量重建技巧；最终由 idx_reservation_expiry 落地） | <code>L366 KEY idx_reservation_expiry (status, expires_at, order_id),（临时名不保留）</code> |
| 7 | L76 #7 | <code>ALTER TABLE inventory_reservation DROP INDEX idx_reservation_expiry</code> | 删除旧列序的预占到期索引，为重建让位（存量库技巧） | 本阶段不需要（旧定义被重建覆盖） | <code>L366 同名索引为重建后的最终定义（旧定义已被替换）</code> |
| 8 | L88 #8 | <code>ALTER TABLE inventory_reservation RENAME INDEX idx_reservation_expiry_v2 TO idx_reservation_expiry</code> | 把重建后的临时索引改回正式名，最终结构为 idx_reservation_expiry (status, expires_at, order_id) | schema.sql 已有等价 | <code>L366 KEY idx_reservation_expiry (status, expires_at, order_id),</code> |

### V2__s2_card_fingerprint_state.sql

| # | 出处 | 原始 SQL（执行形态） | 这条语句想干什么 | schema.sql 落点 | 最终形态 |
| --- | --- | --- | --- | --- | --- |
| 9 | L12 #1 | <code>ALTER TABLE product_virtual_card ADD COLUMN fingerprint_unavailable TINYINT NOT NULL DEFAULT 0 AFTER card_fingerprint</code> | product_virtual_card.fingerprint_unavailable：标记卡密指纹不可用，避免对无指纹行回写指纹（S2） | schema.sql 已有等价 | <code>L141 `fingerprint_unavailable` TINYINT NOT NULL DEFAULT 0 COMMENT '指纹不可用标记: 1-缺少明文或解密失败，导入去重时跳过并计数',</code> |

### V3__s2_card_access_audit.sql

| # | 出处 | 原始 SQL（执行形态） | 这条语句想干什么 | schema.sql 落点 | 最终形态 |
| --- | --- | --- | --- | --- | --- |
| 10 | L7 #裸1 | <code>CREATE TABLE IF NOT EXISTS `card_access_audit` (<br>  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,<br>  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属店铺ID',<br>  `actor_id` BIGINT UNSIGNED NOT NULL COMMENT '操作人用户ID（取自已校验会话）',<br>  `action` VARCHAR(32) NOT NULL COMMENT '动作: EXPORT_PLAINTEXT',<br>  `sku_id` BIGINT UNSIGNED NOT NULL COMMENT '明文涉及的SKU',<br>  `card_count` INT NOT NULL COMMENT '本次返回的明文条数',<br>  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,<br>  PRIMARY KEY (`id`),<br>  KEY `idx_audit_merchant_created` (`merchant_id`, `created_at`),<br>  KEY `idx_audit_sku_created` (`sku_id`, `created_at`)<br>) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卡密明文访问审计表';</code> | 建卡密明文访问审计表：主键 + 商户/操作人/动作/商品/SKU/数量/时间 + 2 个索引 | schema.sql 已有等价（并额外含 3 个外键） | <code>L155-L169 完整建表块：主键 id + idx_audit_merchant + idx_audit_sku + L166-L168 三个外键</code> |

### V4__s2_merchant_sku_code_unique.sql

| # | 出处 | 原始 SQL（执行形态） | 这条语句想干什么 | schema.sql 落点 | 最终形态 |
| --- | --- | --- | --- | --- | --- |
| 11 | L14 #1 | <code>ALTER TABLE product_sku ADD UNIQUE KEY uk_merchant_sku_code (merchant_id, sku_code)</code> | SKU 编码唯一域从全局收窄为店内 (merchant_id, sku_code) | schema.sql 已有等价 | <code>L126 UNIQUE KEY `uk_merchant_sku_code` (`merchant_id`, `sku_code`),</code> |
| 12 | L23 #2 | <code>ALTER TABLE product_sku DROP INDEX uk_sku_code</code> | 删除全局 sku_code 唯一键，完成唯一域收窄；最终结构不再保留 | 本阶段不需要（最终状态已无该索引） | <code>schema.sql 中不存在该索引</code> |

### V5__g34_payment_event_reconcile_retry.sql

| # | 出处 | 原始 SQL（执行形态） | 这条语句想干什么 | schema.sql 落点 | 最终形态 |
| --- | --- | --- | --- | --- | --- |
| 13 | L13 #1 | <code>ALTER TABLE payment_event ADD COLUMN reconcile_attempts INT NOT NULL DEFAULT 0 COMMENT 'G34：陈旧 PROCESSING 渠道回查已重试次数' AFTER raw_payload</code> | payment_event.reconcile_attempts：记录渠道回查补偿尝试次数，控制重试上限（G34） | schema.sql 已有等价 | <code>L488 `reconcile_attempts` INT NOT NULL DEFAULT 0 COMMENT 'G34：陈旧 PROCESSING 渠道回查已重试次数',</code> |
| 14 | L23 #2 | <code>ALTER TABLE payment_event ADD COLUMN next_reconcile_at DATETIME NULL COMMENT 'G34：下次允许回查时间，NULL 表示立即可扫' AFTER reconcile_attempts</code> | payment_event.next_reconcile_at：记录下次补偿回查时间，支持游标扫描（G34） | schema.sql 已有等价 | <code>L489 `next_reconcile_at` DATETIME NULL COMMENT 'G34：下次允许回查时间，NULL 表示立即可扫；超过上限转 MANUAL_REVIEW',</code> |
| 15 | L33 #3 | <code>ALTER TABLE payment_event ADD INDEX idx_event_reconcile (event_status, next_reconcile_at)</code> | 按 event_status + next_reconcile_at 扫描需要补偿回查的事件 | schema.sql 已有等价 | <code>L495 KEY `idx_event_reconcile` (`event_status`, `next_reconcile_at`),</code> |

### V6__s1_legacy_structure_reconcile.sql

| # | 出处 | 原始 SQL（执行形态） | 这条语句想干什么 | schema.sql 落点 | 最终形态 |
| --- | --- | --- | --- | --- | --- |
| 16 | L22 #1 | <code>ALTER TABLE merchant_store ADD COLUMN pay_channel VARCHAR(32) NOT NULL DEFAULT 'WECHAT' COMMENT 'T32 新增：真实支付渠道 WECHAT/ALIPAY，与 A/B/C 收款模式解耦' AFTER payment_channel_type</code> | merchant_store.pay_channel：T32 新增：真实支付渠道 WECHAT/ALIPAY，与 A/B/C 收款模式解耦 | schema.sql 已有等价 | <code>L34 `pay_channel` VARCHAR(32) NOT NULL DEFAULT 'WECHAT' COMMENT 'T32 新增：真实支付渠道 WECHAT/ALIPAY，与 A/B/C 收款模式解耦',</code> |
| 17 | L33 #2 | <code>ALTER TABLE merchant_payment_account ADD COLUMN settlement_account_id BIGINT UNSIGNED NULL COMMENT 'T39 阶段二预留：关联 merchant_settlement_account，阶段一先不写值' AFTER reject_reason</code> | merchant_payment_account.settlement_account_id：T39 阶段二预留：关联 merchant_settlement_account，阶段一先不写值 | schema.sql 已有等价 | <code>L53 `settlement_account_id` BIGINT UNSIGNED NULL COMMENT 'T39 阶段二预留：关联 merchant_settlement_account，阶段一先不写值',</code> |
| 18 | L42 #3 | <code>ALTER TABLE merchant_payment_account ADD COLUMN binding_status VARCHAR(24) NOT NULL DEFAULT 'UNBOUND' COMMENT 'T39 阶段二预留：UNBOUND/BINDING/BOUND/REJECTED/DISABLED，只允许渠道真实结果推进' AFTER settlement_account_id</code> | merchant_payment_account.binding_status：T39 阶段二预留：UNBOUND/BINDING/BOUND/REJECTED/DISABLED，只允许渠道真实结果推进 | schema.sql 已有等价 | <code>L54 `binding_status` VARCHAR(24) NOT NULL DEFAULT 'UNBOUND' COMMENT 'T39 阶段二预留：UNBOUND/BINDING/BOUND/REJECTED/DISABLED，只允许渠道真实结果推进',</code> |
| 19 | L51 #4 | <code>ALTER TABLE merchant_payment_account ADD COLUMN channel_merchant_id VARCHAR(64) NULL COMMENT 'T39 阶段二预留：渠道真实商户号返回值' AFTER binding_status</code> | merchant_payment_account.channel_merchant_id：T39 阶段二预留：渠道真实商户号返回值 | schema.sql 已有等价 | <code>L55 `channel_merchant_id` VARCHAR(64) NULL COMMENT 'T39 阶段二预留：渠道真实商户号返回值',</code> |
| 20 | L60 #5 | <code>ALTER TABLE merchant_payment_account ADD COLUMN bound_at DATETIME NULL COMMENT 'T39 阶段二预留：绑定完成时间' AFTER channel_merchant_id</code> | merchant_payment_account.bound_at：T39 阶段二预留：绑定完成时间 | schema.sql 已有等价 | <code>L56 `bound_at` DATETIME NULL COMMENT 'T39 阶段二预留：绑定完成时间',</code> |
| 21 | L69 #6 | <code>ALTER TABLE merchant_payment_account ADD COLUMN effective_from DATETIME NULL COMMENT 'T39 阶段二预留：绑定生效时间' AFTER bound_at</code> | merchant_payment_account.effective_from：T39 阶段二预留：绑定生效时间 | schema.sql 已有等价 | <code>L57 `effective_from` DATETIME NULL COMMENT 'T39 阶段二预留：绑定生效时间',</code> |
| 22 | L78 #7 | <code>ALTER TABLE merchant_payment_account ADD COLUMN effective_to DATETIME NULL COMMENT 'T39 阶段二预留：绑定失效时间' AFTER effective_from</code> | merchant_payment_account.effective_to：T39 阶段二预留：绑定失效时间 | schema.sql 已有等价 | <code>L58 `effective_to` DATETIME NULL COMMENT 'T39 阶段二预留：绑定失效时间',</code> |
| 23 | L87 #8 | <code>ALTER TABLE merchant_payment_account ADD COLUMN raw_result TEXT NULL COMMENT 'T39 阶段二预留：渠道原始返回，供人工核对' AFTER effective_to</code> | merchant_payment_account.raw_result：T39 阶段二预留：渠道原始返回，供人工核对 | schema.sql 已有等价 | <code>L59 `raw_result` TEXT NULL COMMENT 'T39 阶段二预留：渠道原始返回，供人工核对',</code> |
| 24 | L98 #9 | <code>ALTER TABLE trade_order ADD COLUMN refunded_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T33 已退款商品金额（含渠道处理中预占）'</code> | trade_order.refunded_amount：T33 已退款商品金额（含渠道处理中预占） | schema.sql 已有等价 | <code>L183 `refunded_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T33 已退款商品金额（含渠道处理中预占）',</code> |
| 25 | L107 #10 | <code>ALTER TABLE trade_order ADD COLUMN fee_refunded_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T33 已冲正/预占技术服务费（含处理中预占）'</code> | trade_order.fee_refunded_amount：T33 已冲正/预占技术服务费（含处理中预占） | schema.sql 已有等价 | <code>L184 `fee_refunded_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T33 已冲正/预占技术服务费（含处理中预占）',</code> |
| 26 | L116 #11 | <code>ALTER TABLE trade_order ADD COLUMN fee_status VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'T33/T34 服务费状态 PENDING-未收费, ACCRUED-已入账, ARREARS-欠费, PARTIAL_REVERSED-部分冲正, REVERSED-全额冲正'</code> | trade_order.fee_status：T33/T34 服务费状态 PENDING-未收费, ACCRUED-已入账, ARREARS-欠费, PARTIAL_REVERSED-部分冲正, REVERSED-全额冲正 | schema.sql 已有等价 | <code>L185 `fee_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'T33/T34 服务费状态 PENDING-未收费, ACCRUED-已入账, ARREARS-欠费, PARTIAL_REVERSED-部分冲正, REVERSED-全额冲正',</code> |
| 27 | L125 #12 | <code>ALTER TABLE trade_order ADD COLUMN goods_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 计费基数：商品成交额（不含运费/税费）'</code> | trade_order.goods_amount：T34 计费基数：商品成交额（不含运费/税费） | schema.sql 已有等价 | <code>L186 `goods_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 计费基数：商品成交额（不含运费/税费）',</code> |
| 28 | L134 #13 | <code>ALTER TABLE trade_order ADD COLUMN merchant_discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 商家优惠金额'</code> | trade_order.merchant_discount_amount：T34 商家优惠金额 | schema.sql 已有等价 | <code>L187 `merchant_discount_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 商家优惠金额',</code> |
| 29 | L143 #14 | <code>ALTER TABLE trade_order ADD COLUMN platform_discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 平台优惠金额'</code> | trade_order.platform_discount_amount：T34 平台优惠金额 | schema.sql 已有等价 | <code>L188 `platform_discount_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 平台优惠金额',</code> |
| 30 | L152 #15 | <code>ALTER TABLE trade_order ADD COLUMN shipping_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 运费，不计入服务费基数'</code> | trade_order.shipping_amount：T34 运费，不计入服务费基数 | schema.sql 已有等价 | <code>L189 `shipping_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 运费，不计入服务费基数',</code> |
| 31 | L161 #16 | <code>ALTER TABLE trade_order ADD COLUMN order_tax_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 订单税额（价税分离）'</code> | trade_order.order_tax_amount：T34 订单税额（价税分离） | schema.sql 已有等价 | <code>L190 `order_tax_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 订单税额（价税分离）',</code> |
| 32 | L170 #17 | <code>ALTER TABLE trade_order ADD COLUMN fee_policy_id BIGINT NULL COMMENT 'T34 订单级费率快照来源的政策ID；T39 起写 merchant_fee_policy.id，历史订单可为空'</code> | trade_order.fee_policy_id：T34 订单级费率快照来源的政策ID；T39 起写 merchant_fee_policy.id，历史订单可为空 | schema.sql 已有等价 | <code>L191 `fee_policy_id` BIGINT NULL COMMENT 'T34 订单级费率快照来源的政策ID；T39 起写 merchant_fee_policy.id，历史订单可为空',</code> |
| 33 | L179 #18 | <code>ALTER TABLE trade_order ADD COLUMN fee_rate_bps INT NOT NULL DEFAULT 0 COMMENT 'T34 订单级费率快照，万分比；300 表示 3%'</code> | trade_order.fee_rate_bps：T34 订单级费率快照，万分比；300 表示 3% | schema.sql 已有等价 | <code>L192 `fee_rate_bps` INT NOT NULL DEFAULT 0 COMMENT 'T34 订单级费率快照，万分比；300 表示 3%',</code> |
| 34 | L188 #19 | <code>ALTER TABLE trade_order ADD COLUMN fee_rate_type VARCHAR(24) NOT NULL DEFAULT 'NORMAL' COMMENT 'T34 费率类型 NORMAL/PROMOTION；PROMOTION 表示推广期 0 费率'</code> | trade_order.fee_rate_type：T34 费率类型 NORMAL/PROMOTION；PROMOTION 表示推广期 0 费率 | schema.sql 已有等价 | <code>L193 `fee_rate_type` VARCHAR(24) NOT NULL DEFAULT 'NORMAL' COMMENT 'T34 费率类型 NORMAL/PROMOTION；PROMOTION 表示推广期 0 费率',</code> |
| 35 | L197 #20 | <code>ALTER TABLE trade_order ADD COLUMN fee_base_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 服务费计费基数=商品成交额-商家优惠'</code> | trade_order.fee_base_amount：T34 服务费计费基数=商品成交额-商家优惠 | schema.sql 已有等价 | <code>L194 `fee_base_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 服务费计费基数=商品成交额-商家优惠',</code> |
| 36 | L206 #21 | <code>ALTER TABLE trade_order ADD COLUMN fee_standard_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 标准服务费=基数×费率'</code> | trade_order.fee_standard_amount：T34 标准服务费=基数×费率 | schema.sql 已有等价 | <code>L195 `fee_standard_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 标准服务费=基数×费率',</code> |
| 37 | L215 #22 | <code>ALTER TABLE trade_order ADD COLUMN fee_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 应收服务费（推广期可为0）'</code> | trade_order.fee_amount：T34 应收服务费（推广期可为0） | schema.sql 已有等价 | <code>L196 `fee_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 应收服务费（推广期可为0）',</code> |
| 38 | L224 #23 | <code>ALTER TABLE trade_order ADD COLUMN fee_waived_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 减免金额，推广期等于标准服务费'</code> | trade_order.fee_waived_amount：T34 减免金额，推广期等于标准服务费 | schema.sql 已有等价 | <code>L197 `fee_waived_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 减免金额，推广期等于标准服务费',</code> |
| 39 | L233 #24 | <code>ALTER TABLE trade_order ADD COLUMN fee_net_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 服务费不含税额'</code> | trade_order.fee_net_amount：T34 服务费不含税额 | schema.sql 已有等价 | <code>L198 `fee_net_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 服务费不含税额',</code> |
| 40 | L242 #25 | <code>ALTER TABLE trade_order ADD COLUMN fee_tax_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 服务费税额'</code> | trade_order.fee_tax_amount：T34 服务费税额 | schema.sql 已有等价 | <code>L199 `fee_tax_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'T34 服务费税额',</code> |
| 41 | L251 #26 | <code>ALTER TABLE trade_order ADD COLUMN fee_tax_rate DECIMAL(7,4) NOT NULL DEFAULT 0.0000 COMMENT 'T34 服务费税率快照，如 0.0600'</code> | trade_order.fee_tax_rate：T34 服务费税率快照，如 0.0600 | schema.sql 已有等价 | <code>L200 `fee_tax_rate` DECIMAL(7,4) NOT NULL DEFAULT 0.0000 COMMENT 'T34 服务费税率快照，如 0.0600',</code> |
| 42 | L260 #27 | <code>ALTER TABLE trade_order ADD COLUMN collection_mode VARCHAR(24) NOT NULL DEFAULT 'MERCHANT_DIRECT' COMMENT 'T32 新增：MERCHANT_DIRECT/PLATFORM_COLLECT，阶段一固定商家直收'</code> | trade_order.collection_mode：T32 新增：MERCHANT_DIRECT/PLATFORM_COLLECT，阶段一固定商家直收 | schema.sql 已有等价 | <code>L204 `collection_mode` VARCHAR(24) NOT NULL DEFAULT 'MERCHANT_DIRECT' COMMENT 'T32 新增：MERCHANT_DIRECT/PLATFORM_COLLECT，阶段一固定商家直收',</code> |
| 43 | L269 #28 | <code>ALTER TABLE trade_order ADD COLUMN pay_channel VARCHAR(32) NOT NULL DEFAULT 'WECHAT' COMMENT 'T32 新增：真实支付渠道快照 WECHAT/ALIPAY'</code> | trade_order.pay_channel：T32 新增：真实支付渠道快照 WECHAT/ALIPAY | schema.sql 已有等价 | <code>L205 `pay_channel` VARCHAR(32) NOT NULL DEFAULT 'WECHAT' COMMENT 'T32 新增：真实支付渠道快照 WECHAT/ALIPAY',</code> |
| 44 | L278 #29 | <code>ALTER TABLE trade_order ADD COLUMN platform_trade_no VARCHAR(128) NULL COMMENT 'T40 阶段二预留：平台代收渠道流水号；阶段一为空，不得用商家直收流水回填'</code> | trade_order.platform_trade_no：T40 阶段二预留：平台代收渠道流水号；阶段一为空，不得用商家直收流水回填 | schema.sql 已有等价 | <code>L207 `platform_trade_no` VARCHAR(128) NULL COMMENT 'T40 阶段二预留：平台代收渠道流水号；阶段一为空，不得用商家直收流水回填',</code> |
| 45 | L289 #30 | <code>ALTER TABLE payment_event ADD COLUMN collection_mode VARCHAR(24) NOT NULL DEFAULT 'MERCHANT_DIRECT' COMMENT 'T32 新增：MERCHANT_DIRECT/PLATFORM_COLLECT' AFTER merchant_id</code> | payment_event.collection_mode：T32 新增：MERCHANT_DIRECT/PLATFORM_COLLECT | schema.sql 已有等价 | <code>L479 `collection_mode` VARCHAR(24) NOT NULL DEFAULT 'MERCHANT_DIRECT' COMMENT 'T32 新增：MERCHANT_DIRECT/PLATFORM_COLLECT',</code> |
| 46 | L298 #31 | <code>ALTER TABLE payment_event ADD COLUMN channel_merchant_id VARCHAR(64) NULL COMMENT 'T32 新增：渠道侧商户号（微信mchid/支付宝seller_id），与内部商户ID分离' AFTER collection_mode</code> | payment_event.channel_merchant_id：T32 新增：渠道侧商户号（微信mchid/支付宝seller_id），与内部商户ID分离 | schema.sql 已有等价 | <code>L480 `channel_merchant_id` VARCHAR(64) NULL COMMENT 'T32 新增：渠道侧商户号（微信mchid/支付宝seller_id），与内部商户ID分离',</code> |
| 47 | L307 #32 | <code>ALTER TABLE payment_event ADD COLUMN platform_trade_no VARCHAR(128) NULL COMMENT 'T32 新增：阶段二平台流水；阶段一为空' AFTER channel_merchant_id</code> | payment_event.platform_trade_no：T32 新增：阶段二平台流水；阶段一为空 | schema.sql 已有等价 | <code>L481 `platform_trade_no` VARCHAR(128) NULL COMMENT 'T32 新增：阶段二平台流水；阶段一为空',</code> |
| 48 | L322 #33 | <code>ALTER TABLE payment_event MODIFY COLUMN merchant_id BIGINT UNSIGNED NOT NULL COMMENT '商户ID'</code> | payment_event.merchant_id：把 payment_event.merchant_id 统一为 BIGINT UNSIGNED，使外键两端类型/符号一致 | schema.sql 已有等价 | <code>L478 `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '商户ID',</code> |
| 49 | L333 #34 | <code>ALTER TABLE trade_order MODIFY COLUMN payment_channel VARCHAR(32) NOT NULL DEFAULT 'WECHAT' COMMENT 'WECHAT, ALIPAY; T32 起只写真实渠道，不再写 A/B/C'</code> | trade_order.payment_channel：支付渠道改为 NOT NULL DEFAULT 'WECHAT'，防止空渠道进入订单/退款/结算链路 | schema.sql 已有等价 | <code>L203 `payment_channel` VARCHAR(32) NOT NULL DEFAULT 'WECHAT' COMMENT 'WECHAT, ALIPAY; T32 起只写真实渠道，不再写 A/B/C',</code> |
| 50 | L346 #35 | <code>ALTER TABLE merchant_payment_account ADD CONSTRAINT fk_payment_account_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)</code> | merchant_payment_account 到 merchant_store：补齐外键 fk_payment_account_merchant，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L63 CONSTRAINT fk_payment_account_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)</code> |
| 51 | L356 #36 | <code>ALTER TABLE sys_user ADD CONSTRAINT fk_user_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)</code> | sys_user 到 merchant_store：补齐外键 fk_user_merchant，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L78 CONSTRAINT fk_user_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)</code> |
| 52 | L366 #37 | <code>ALTER TABLE product ADD CONSTRAINT fk_product_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)</code> | product 到 merchant_store：补齐外键 fk_product_merchant，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L111 CONSTRAINT fk_product_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`),</code> |
| 53 | L376 #38 | <code>ALTER TABLE product ADD CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id)</code> | product 到 category：补齐外键 fk_product_category，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L112 CONSTRAINT fk_product_category FOREIGN KEY (`category_id`) REFERENCES `category` (`id`)</code> |
| 54 | L386 #39 | <code>ALTER TABLE product_sku ADD CONSTRAINT fk_sku_product FOREIGN KEY (product_id) REFERENCES product (id)</code> | product_sku 到 product：补齐外键 fk_sku_product，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L130 CONSTRAINT fk_sku_product FOREIGN KEY (`product_id`) REFERENCES `product` (`id`),</code> |
| 55 | L396 #40 | <code>ALTER TABLE product_sku ADD CONSTRAINT fk_sku_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)</code> | product_sku 到 merchant_store：补齐外键 fk_sku_merchant，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L131 CONSTRAINT fk_sku_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)</code> |
| 56 | L406 #41 | <code>ALTER TABLE product_virtual_card ADD CONSTRAINT fk_card_product FOREIGN KEY (product_id) REFERENCES product (id)</code> | product_virtual_card 到 product：补齐外键 fk_card_product，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L149 CONSTRAINT fk_card_product FOREIGN KEY (`product_id`) REFERENCES `product` (`id`),</code> |
| 57 | L416 #42 | <code>ALTER TABLE product_virtual_card ADD CONSTRAINT fk_card_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)</code> | product_virtual_card 到 product_sku：补齐外键 fk_card_sku，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L150 CONSTRAINT fk_card_sku FOREIGN KEY (`sku_id`) REFERENCES `product_sku` (`id`),</code> |
| 58 | L426 #43 | <code>ALTER TABLE product_virtual_card ADD CONSTRAINT fk_card_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)</code> | product_virtual_card 到 merchant_store：补齐外键 fk_card_merchant，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L151 CONSTRAINT fk_card_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)</code> |
| 59 | L436 #44 | <code>ALTER TABLE card_access_audit ADD CONSTRAINT fk_audit_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)</code> | card_access_audit 到 merchant_store：补齐外键 fk_audit_merchant，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L166 CONSTRAINT fk_audit_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`),</code> |
| 60 | L446 #45 | <code>ALTER TABLE card_access_audit ADD CONSTRAINT fk_audit_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)</code> | card_access_audit 到 product_sku：补齐外键 fk_audit_sku，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L167 CONSTRAINT fk_audit_sku FOREIGN KEY (`sku_id`) REFERENCES `product_sku` (`id`),</code> |
| 61 | L456 #46 | <code>ALTER TABLE card_access_audit ADD CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)</code> | card_access_audit 到 sys_user：补齐外键 fk_audit_actor，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L168 CONSTRAINT fk_audit_actor FOREIGN KEY (`actor_id`) REFERENCES `sys_user` (`id`)</code> |
| 62 | L466 #47 | <code>ALTER TABLE trade_order ADD CONSTRAINT fk_order_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)</code> | trade_order 到 merchant_store：补齐外键 fk_order_merchant，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L232 CONSTRAINT fk_order_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)</code> |
| 63 | L476 #48 | <code>ALTER TABLE trade_order_item ADD CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES trade_order (id)</code> | trade_order_item 到 trade_order：补齐外键 fk_item_order，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L253 CONSTRAINT fk_item_order FOREIGN KEY (`order_id`) REFERENCES `trade_order` (`id`),</code> |
| 64 | L486 #49 | <code>ALTER TABLE trade_order_item ADD CONSTRAINT fk_item_product FOREIGN KEY (product_id) REFERENCES product (id)</code> | trade_order_item 到 product：补齐外键 fk_item_product，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L254 CONSTRAINT fk_item_product FOREIGN KEY (`product_id`) REFERENCES `product` (`id`),</code> |
| 65 | L496 #50 | <code>ALTER TABLE trade_order_item ADD CONSTRAINT fk_item_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)</code> | trade_order_item 到 product_sku：补齐外键 fk_item_sku，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L255 CONSTRAINT fk_item_sku FOREIGN KEY (`sku_id`) REFERENCES `product_sku` (`id`)</code> |
| 66 | L506 #51 | <code>ALTER TABLE billing_statement ADD CONSTRAINT fk_statement_merchant FOREIGN KEY (shop_id) REFERENCES merchant_store (id)</code> | billing_statement 到 merchant_store：补齐外键 fk_statement_merchant，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L324 CONSTRAINT fk_statement_merchant FOREIGN KEY (`shop_id`) REFERENCES `merchant_store` (`id`)</code> |
| 67 | L516 #52 | <code>ALTER TABLE complaint ADD CONSTRAINT fk_complaint_order FOREIGN KEY (order_id) REFERENCES trade_order (id)</code> | complaint 到 trade_order：补齐外键 fk_complaint_order，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L349 CONSTRAINT fk_complaint_order FOREIGN KEY (`order_id`) REFERENCES `trade_order` (`id`),</code> |
| 68 | L526 #53 | <code>ALTER TABLE complaint ADD CONSTRAINT fk_complaint_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)</code> | complaint 到 merchant_store：补齐外键 fk_complaint_merchant，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L350 CONSTRAINT fk_complaint_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)</code> |
| 69 | L536 #54 | <code>ALTER TABLE inventory_reservation ADD CONSTRAINT fk_reservation_order FOREIGN KEY (order_id) REFERENCES trade_order (id)</code> | inventory_reservation 到 trade_order：补齐外键 fk_reservation_order，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L368 CONSTRAINT fk_reservation_order FOREIGN KEY (order_id) REFERENCES trade_order (id),</code> |
| 70 | L546 #55 | <code>ALTER TABLE inventory_reservation ADD CONSTRAINT fk_reservation_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)</code> | inventory_reservation 到 product_sku：补齐外键 fk_reservation_sku，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L369 CONSTRAINT fk_reservation_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),</code> |
| 71 | L556 #56 | <code>ALTER TABLE inventory_reservation ADD CONSTRAINT fk_reservation_product FOREIGN KEY (product_id) REFERENCES product (id)</code> | inventory_reservation 到 product：补齐外键 fk_reservation_product，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L370 CONSTRAINT fk_reservation_product FOREIGN KEY (product_id) REFERENCES product (id),</code> |
| 72 | L566 #57 | <code>ALTER TABLE inventory_reservation ADD CONSTRAINT fk_reservation_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)</code> | inventory_reservation 到 merchant_store：补齐外键 fk_reservation_merchant，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L371 CONSTRAINT fk_reservation_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)</code> |
| 73 | L576 #58 | <code>ALTER TABLE inventory_adjustment ADD CONSTRAINT fk_adjustment_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)</code> | inventory_adjustment 到 merchant_store：补齐外键 fk_adjustment_merchant，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L419 CONSTRAINT fk_adjustment_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id),</code> |
| 74 | L586 #59 | <code>ALTER TABLE inventory_adjustment ADD CONSTRAINT fk_adjustment_product FOREIGN KEY (product_id) REFERENCES product (id)</code> | inventory_adjustment 到 product：补齐外键 fk_adjustment_product，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L420 CONSTRAINT fk_adjustment_product FOREIGN KEY (product_id) REFERENCES product (id),</code> |
| 75 | L596 #60 | <code>ALTER TABLE inventory_adjustment ADD CONSTRAINT fk_adjustment_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)</code> | inventory_adjustment 到 product_sku：补齐外键 fk_adjustment_sku，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L421 CONSTRAINT fk_adjustment_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),</code> |
| 76 | L606 #61 | <code>ALTER TABLE inventory_adjustment ADD CONSTRAINT fk_adjustment_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)</code> | inventory_adjustment 到 sys_user：补齐外键 fk_adjustment_actor，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L422 CONSTRAINT fk_adjustment_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)</code> |
| 77 | L616 #62 | <code>ALTER TABLE virtual_card_void ADD CONSTRAINT fk_card_void_card FOREIGN KEY (card_id) REFERENCES product_virtual_card (id)</code> | virtual_card_void 到 product_virtual_card：补齐外键 fk_card_void_card，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L438 CONSTRAINT fk_card_void_card FOREIGN KEY (card_id) REFERENCES product_virtual_card (id),</code> |
| 78 | L626 #63 | <code>ALTER TABLE virtual_card_void ADD CONSTRAINT fk_card_void_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)</code> | virtual_card_void 到 merchant_store：补齐外键 fk_card_void_merchant，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L439 CONSTRAINT fk_card_void_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id),</code> |
| 79 | L636 #64 | <code>ALTER TABLE virtual_card_void ADD CONSTRAINT fk_card_void_product FOREIGN KEY (product_id) REFERENCES product (id)</code> | virtual_card_void 到 product：补齐外键 fk_card_void_product，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L440 CONSTRAINT fk_card_void_product FOREIGN KEY (product_id) REFERENCES product (id),</code> |
| 80 | L646 #65 | <code>ALTER TABLE virtual_card_void ADD CONSTRAINT fk_card_void_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)</code> | virtual_card_void 到 product_sku：补齐外键 fk_card_void_sku，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L441 CONSTRAINT fk_card_void_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),</code> |
| 81 | L656 #66 | <code>ALTER TABLE virtual_card_void ADD CONSTRAINT fk_card_void_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)</code> | virtual_card_void 到 sys_user：补齐外键 fk_card_void_actor，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L442 CONSTRAINT fk_card_void_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)</code> |
| 82 | L666 #67 | <code>ALTER TABLE card_import_batch ADD CONSTRAINT fk_card_batch_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)</code> | card_import_batch 到 merchant_store：补齐外键 fk_card_batch_merchant，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L459 CONSTRAINT fk_card_batch_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id),</code> |
| 83 | L676 #68 | <code>ALTER TABLE card_import_batch ADD CONSTRAINT fk_card_batch_product FOREIGN KEY (product_id) REFERENCES product (id)</code> | card_import_batch 到 product：补齐外键 fk_card_batch_product，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L460 CONSTRAINT fk_card_batch_product FOREIGN KEY (product_id) REFERENCES product (id),</code> |
| 84 | L686 #69 | <code>ALTER TABLE card_import_batch ADD CONSTRAINT fk_card_batch_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)</code> | card_import_batch 到 product_sku：补齐外键 fk_card_batch_sku，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L461 CONSTRAINT fk_card_batch_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),</code> |
| 85 | L696 #70 | <code>ALTER TABLE card_import_batch ADD CONSTRAINT fk_card_batch_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)</code> | card_import_batch 到 sys_user：补齐外键 fk_card_batch_actor，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L462 CONSTRAINT fk_card_batch_actor FOREIGN KEY (actor_id) REFERENCES sys_user (id)</code> |
| 86 | L706 #71 | <code>ALTER TABLE payment_event ADD CONSTRAINT fk_payment_event_merchant FOREIGN KEY (merchant_id) REFERENCES merchant_store (id)</code> | payment_event 到 merchant_store：补齐外键 fk_payment_event_merchant，保证引用完整性、防止孤儿数据 | schema.sql 已有等价 | <code>L496 CONSTRAINT fk_payment_event_merchant FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)</code> |

## 3. 注释与语义文本差异

### 3.0 首轮误报说明

首轮脚本用 `'...'` 直接截取 DDL 字符串，V6 的 `DEFAULT ''WECHAT''`、`COMMENT ''...''` 中的双单引号被截断，导致 30 多条列被误报为 `V=[... comment]` vs `S=[...]`。修正为“先还原 `''` 为 `'`、再按列名定位 `schema.sql` 列定义、逐字比较 `COMMENT`”后重新比对，真实差异只有下表 2 条，且均为 `schema.sql` 侧更完整。

### 3.1 列注释逐条比对（41 处带 COMMENT 的列定义，含 V6 两条 MODIFY 的列注释）

| 差异项 | V 脚本原文 | schema.sql 现状 | 语义类别 | 决定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| `product_virtual_card.fingerprint_unavailable`（V2 L12） | V 脚本**没有注释** | L141：`指纹不可用标记: 1-缺少明文或解密失败，导入去重时跳过并计数` | 状态枚举/去重口径 | **不补，保留 schema.sql 版本** | V 侧为空、S 侧更完整，不存在“V 有而 S 丢”；保留 S 的说明更利于维护 |
| `payment_event.next_reconcile_at`（V5 L23） | `G34：下次允许回查时间，NULL 表示立即可扫` | L489：`G34：下次允许回查时间，NULL 表示立即可扫；超过上限转 MANUAL_REVIEW` | 状态枚举/补偿口径 | **不补，保留 schema.sql 版本** | S 侧多出“超过上限转 MANUAL_REVIEW”的处置语义，比 V 更严格；V 的意图已被完整包含 |

其余 **39 处列注释逐字一致**（含 V6 两条 `MODIFY COLUMN` 的列注释），包含用户点名的四类必须保留语义：

| 必保类别 | 代表项（V 与 S 原文一致） | schema.sql 行号 |
| --- | --- | --- |
| 状态枚举 | `trade_order.fee_status`：`PENDING-未收费, ACCRUED-已入账, ARREARS-欠费, PARTIAL_REVERSED-部分冲正, REVERSED-全额冲正`；`merchant_payment_account.binding_status`：`UNBOUND/BINDING/BOUND/REJECTED/DISABLED`；`trade_order.fee_rate_type`：`NORMAL/PROMOTION`；`trade_order.collection_mode`：`MERCHANT_DIRECT/PLATFORM_COLLECT` | L185、L54、L193、L204 |
| 计费口径 | `trade_order.goods_amount`：`T34 计费基数：商品成交额（不含运费/税费）`；`fee_base_amount`：`服务费计费基数=商品成交额-商家优惠`；`fee_standard_amount`：`标准服务费=基数×费率`；`fee_rate_bps`：`万分比；300 表示 3%` | L186、L194、L195、L192 |
| 阶段二预留标记 | `merchant_payment_account.settlement_account_id` 等 7 列：`T39 阶段二预留…阶段一先不写值`；`trade_order.platform_trade_no`：`T40 阶段二预留…阶段一为空，不得用商家直收流水回填` | L53-L59、L207 |
| 金额含义 | `refunded_amount`：`T33 已退款商品金额（含渠道处理中预占）`；`fee_refunded_amount`：`T33 已冲正/预占技术服务费（含处理中预占）`；`fee_waived_amount`：`T34 减免金额，推广期等于标准服务费`；`fee_net_amount`：`T34 服务费不含税额` | L183、L184、L197、L198 |

**结论：V1–V6 的 41 处带 COMMENT 的列定义中，没有任何一条注释在 `schema.sql` 中缺失或被弱化。** 其中 39 处逐字一致、2 处为 `schema.sql` 侧更完整，决定保留 `schema.sql` 版本，不需要补写。
V1 的 `attempt_key`、`reissue_count` 在 V 与 S 中均无注释；两者是 T10 内部技术字段，不属于四类必补语义，**不凭空新增**，避免把技术实现细化冒充业务规则。V3 表注释 `卡密明文访问审计表` 在 V 与 S 中逐字一致（S L155）。V1–V6 没有索引注释语句。

### 3.2 V1 六项点名核对

| 对象 | V1 原始 SQL | schema.sql 行号与定义 | 结论 |
| --- | --- | --- | --- |
| `idx_order_created` | `ALTER TABLE trade_order ADD INDEX idx_order_created (created_at, id)` | L228：`KEY idx_order_created (created_at, id),` | 等价，列序一致 |
| `idx_merchant_created` | `ALTER TABLE trade_order ADD INDEX idx_merchant_created (merchant_id, created_at, id)` | L229：`KEY idx_merchant_created (merchant_id, created_at, id),` | 等价，列序一致 |
| `idx_order_expiry` | `ALTER TABLE trade_order ADD INDEX idx_order_expiry (order_status, lock_expire_time, id)` | L230：`KEY idx_order_expiry (order_status, lock_expire_time, id),` | 等价，列序一致 |
| `inventory_reservation.attempt_key` | `ALTER TABLE inventory_reservation ADD COLUMN attempt_key VARCHAR(64) NULL AFTER owner_token` | L362：`attempt_key VARCHAR(64) NULL,` | 等价；`AFTER` 位置 L361 `owner_token` 之后，列序一致 |
| `inventory_reservation.reissue_count` | `ALTER TABLE inventory_reservation ADD COLUMN reissue_count INT NOT NULL DEFAULT 0 AFTER attempt_key` | L363：`reissue_count INT NOT NULL DEFAULT 0,` | 等价；类型/非空/默认值一致，列序 L362 之后 |
| `idx_reservation_expiry` | `ADD INDEX idx_reservation_expiry_v2 ...` + `DROP INDEX idx_reservation_expiry` + `RENAME ...` | L366：`KEY idx_reservation_expiry (status, expires_at, order_id),` | 最终定义等价；V1 的先建后删改名是存量重建技巧，不抄入 schema.sql |

### 3.3 V4 唯一键收窄核对

- `schema.sql` **只有** `UNIQUE KEY uk_merchant_sku_code (merchant_id, sku_code)`（L126），列序为 `merchant_id, sku_code`。
- 全文检索 `uk_sku_code`：**0 处残留**；不存在全局 `sku_code` 唯一键。
- V4 的执行顺序是“先建 `uk_merchant_sku_code`，再删 `uk_sku_code`”，保证切店唯一域时旧唯一性保护不出现空窗；`schema.sql` 的最终状态直接只建新键、不建旧键，等价且更强（不存在“先有旧键再删除”的中间态）。

## 4. 覆盖校验结论

| 检查项 | 结果 |
| --- | --- |
| DDL 意图总数 | 86 |
| `schema.sql 已有等价` | 83 |
| 存量技巧/最终状态已无（不需写入 schema.sql） | 3（V1 临时索引名、V1 旧索引删除、V4 旧全局唯一键删除；均已在最终结构落地） |
| `schema.sql 缺失` | 0 |
| `schema.sql 有但定义不同` | 0 |
| 触发器/存储过程/函数/事件/CHECK/生成列 | V1–V6 中均为 0 |

## 5. 本轮对 schema.sql 的改动

| 位置 | 改动 | 理由 |
| --- | --- | --- |
| L672（`payment_channel_statement`） | 外键名 `fk_statement_merchant` → `fk_payment_channel_statement_merchant` | 与外键名唯一性冲突：L324（`billing_statement.shop_id`）已使用 `fk_statement_merchant`，MySQL 8.0.16 起外键约束名要求库内唯一，第二处会在建表时报 `Duplicate foreign key constraint name` 并中断 `schema.sql`。L324 保留原名，因为它是 V6 #51 明确命名的对象，改名会让吸收对照失效 |

另新增静态回归用例 `SchemaConstraintNameTest`：读取 `schema.sql` 文本，断言约束名/触发器名库内唯一、单表内索引名不重复、关键表与列存在；只做文本检查，不启动 Spring、不连接数据库，真实 MySQL 建库仍须用户演练。

### 5.1 全库名称静态扫描

扫描口径：按文本解析 `CONSTRAINT ... FOREIGN KEY`、`CONSTRAINT ... CHECK`、`CREATE TRIGGER`，以及每个 `CREATE TABLE` 块内的 `KEY`/`UNIQUE KEY`/`INDEX`；先剔除 `--` 行注释。

| 类别 | 名称 / 出现位置 | 是否重复 |
| --- | --- | --- |
| 外键约束（66 个） | `fk_statement_merchant`：L324 `billing_statement.shop_id`、L672 `payment_channel_statement.merchant_id` | 扫描时为修复前状态，重复 1 组；`317cbce` 已将 L672 改名，早于本卡基线，基线时不再重复 |
| 外键约束其余 65 个 | 均只出现 1 次 | 否 |
| CHECK 约束（16 个） | 均只出现 1 次（`chk_` 前缀，与外键名无交集） | 否 |
| 触发器（2 个） | `trg_ledger_no_update` L550、`trg_ledger_no_delete` L554 | 否 |
| 索引/唯一键（68 个定义） | 按表统计表内重名 | 否（表内冲突 0） |

改后重复数为 0。以上为静态文本结论，真实 MySQL 建库仍须用户演练验证。

### 5.2 口径更正（`fk_statement_merchant`）

**重复问题在 `317cbce` 修复，早于本卡基线 `d83e8db`；本卡基线时该重名已不存在。** 前一版 §5.2 写“首轮发现属实、第二轮属时空错位”，该表述有误，现更正：§5 的扫描与改名过程保留（它记录了 `317cbce` 之前的真实状态和修复动作），但不能据此认为本卡基线仍存在重复。结论：`317cbce` 之后静态扫描重复 0；真实 MySQL 建库仍待演练。

## 6. 删除状态

V1–V6 脚本**保留未删**，等待用户复核本清单。按用户要求，删除必须单独一步、单独提交；在真实 MySQL 演练前不宣称等价已验证。

## 7. 未验证项（必须由用户执行后回填）

- 在真实 MySQL 8.0.16+ 空库执行 `schema.sql` 两次：表数 38、外键 66、无报错。
- `schema.sql` 中全部外键在真实 MySQL 上可创建（排序规则一致）。
- `schema.sql` 的两个 append-only 触发器可创建并生效。
- 删除 V1–V6 后 `SchemaMigrationRunner` 找不到脚本时的启动行为（若后续仍保留执行器）。