/*
 * 文件：OrderFeeStatus.java
 * 用途：订单级技术服务费状态（trade_order.fee_status）的持久化编码唯一来源，被支付扣费、退款冲正与契约测试共用。
 * 维护注意：
 *  1. 本集合与 ServiceFeeStatus（merchant_store.fee_status 的商户月费配置标记）语义不同，禁止互相赋值或合并；
 *     两列同名但一张是订单资金状态、一张是店铺配置标记。
 *  2. 编码保持既有 String 持久化，不因集中定义改变数据库默认值或历史数据；新增/删除编码须同步 schema.sql、
 *     H2 夹具、TradeRefundMapper 条件 SQL 与 OrderFeeStatusContractTest。
 *  3. 这里只声明状态值，不实现扣费/冲正逻辑；状态迁移仍由 MerchantAccountService 与 TradeRefundMapper 的事务条件更新完成。
 */
package com.shop.b2b2c.common;

import java.util.Set;

public final class OrderFeeStatus {
    private OrderFeeStatus() { }

    /** 未收费：下单默认值，尚未产生支付成功扣费或退款冲正。 */
    public static final String PENDING = "PENDING";
    /** 已入账：支付成功后在服务费账户完成扣费或写入 0 费率免收账本。 */
    public static final String ACCRUED = "ACCRUED";
    /** 欠费：支付成功时余额不足，整笔计入商家欠费。 */
    public static final String ARREARS = "ARREARS";
    /** 部分冲正：退款成功按快照比例回补了部分服务费。 */
    public static final String PARTIAL_REVERSED = "PARTIAL_REVERSED";
    /** 全额冲正：累计冲正额已达到订单服务费快照。 */
    public static final String REVERSED = "REVERSED";
    public static final Set<String> SUPPORTED = Set.of(PENDING, ACCRUED, ARREARS, PARTIAL_REVERSED, REVERSED);
}
