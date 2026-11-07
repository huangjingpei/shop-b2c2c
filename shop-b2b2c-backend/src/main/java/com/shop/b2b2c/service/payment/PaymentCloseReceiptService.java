/*
 * 文件：PaymentCloseReceiptService.java
 * 用途：F08 渠道关单回执的统一写入与重试候选查询入口。
 * 维护注意：
 *  1. 只负责留痕与查询，不发起渠道调用、不改订单状态；关单动作仍在 PaymentGatewayService。
 *  2. 写入失败不应阻断本地关单：调用方必须捕获异常并记 WARN，不得把回执故障当作关单失败返回前端或渠道。
 *  3. reason 只写内部摘要，单行化并截断 200 字符；不得写渠道凭据、明文报文或买家身份。
 *  4. 重试候选只含 FAILED；SKIPPED 表示渠道/账户/适配器尚不可用，不应自动重试制造噪声。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.mapper.PaymentCloseReceiptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentCloseReceiptService {

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILED = "FAILED";
    public static final String RESULT_SKIPPED = "SKIPPED";

    private static final int MAX_REASON = 200;

    private final PaymentCloseReceiptMapper receiptMapper;

    /**
     * 记录一次关单尝试；尝试序号由 Mapper 在插入时递增计算。
     */
    public void record(TradeOrder order, String channel, String result, String reason) {
        if (order == null || order.getOrderSn() == null || order.getOrderSn().isBlank()) {
            return;
        }
        receiptMapper.insertAttempt(order.getOrderSn(), order.getMerchantId(), channel,
                result == null ? RESULT_FAILED : result, singleLine(reason, MAX_REASON), LocalDateTime.now());
    }

    /**
     * 查询可重试关单的订单号：最新回执为 FAILED、尝试次数未达上限、已过退避间隔且在窗口内。
     */
    public List<String> findRetryableOrderSn(LocalDateTime since, LocalDateTime until,
                                             int maxAttempts, int limit) {
        if (since == null || until == null || maxAttempts <= 0 || limit <= 0) {
            return List.of();
        }
        return receiptMapper.findRetryableOrderSn(since, until, maxAttempts, limit);
    }

    private static String singleLine(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
