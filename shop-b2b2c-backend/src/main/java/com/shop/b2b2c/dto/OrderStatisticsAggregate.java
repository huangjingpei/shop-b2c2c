/*
 * 文件：OrderStatisticsAggregate.java
 * 用途：T25 订单统计的库内 GROUP BY 聚合行，按 order_status + peer_status 返回计数与合法金额汇总。
 * 维护注意：payAmountSum/commissionAmountSum 已在 SQL 中排除 NULL、负数和小数位超过 2 的金额；
 *           金额异常单数单独计数，不能把异常金额并入汇总。本模型不返回任何买家隐私或卡密字段。
 */
package com.shop.b2b2c.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class OrderStatisticsAggregate {
    private String orderStatus;
    private String peerStatus;
    private long orderCount;
    private BigDecimal payAmountSum;
    private BigDecimal commissionAmountSum;
    private long amountAnomalyCount;
}
