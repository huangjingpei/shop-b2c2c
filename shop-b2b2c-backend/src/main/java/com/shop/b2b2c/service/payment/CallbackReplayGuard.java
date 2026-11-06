/*
 * 文件：CallbackReplayGuard.java
 * 用途：T32/财务加固E：渠道回调时间窗与防重放校验，微信与支付宝共用同一阈值口径。
 * 维护注意：
 *  1. 允许偏差固定 300 秒；超窗一律拒绝且不落账，只记录 WARN 审计日志（不记录报文正文与凭据）。
 *  2. 微信 Wechatpay-Timestamp 为秒级 epoch；支付宝 notify_time 为 GMT+8 的 yyyy-MM-dd HH:mm:ss。
 *  3. 本类只做时间窗校验，签名验签仍由各适配器完成；不得用本类替代验签。
 *  4. 持久化回调审计表尚未建立，当前以结构化日志留痕，已登记到缺口台账。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.common.BusinessException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Slf4j
final class CallbackReplayGuard {

    /** 回调时间与平台当前时间的最大允许偏差（秒），微信与支付宝一致。 */
    static final long MAX_SKEW_SECONDS = 300;

    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ALIPAY_ZONE = ZoneId.of("Asia/Shanghai");

    private CallbackReplayGuard() {
    }

    static void requireWechatTimestamp(String rawTimestamp) {
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(rawTimestamp.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(401, "微信支付回调时间戳格式错误");
        }
        requireWithinWindow(Instant.ofEpochSecond(epochSeconds), "WECHAT", null,
                "微信支付回调时间戳超出允许窗口");
    }

    static void requireAlipayNotifyTime(String notifyTime, String orderSn) {
        if (notifyTime == null || notifyTime.isBlank()) {
            throw new BusinessException(401, "支付宝异步通知缺少通知时间");
        }
        Instant callbackTime;
        try {
            callbackTime = LocalDateTime.parse(notifyTime.trim(), ALIPAY_TIME)
                    .atZone(ALIPAY_ZONE).toInstant();
        } catch (RuntimeException e) {
            throw new BusinessException(400, "支付宝异步通知时间格式错误");
        }
        requireWithinWindow(callbackTime, "ALIPAY", orderSn, "支付宝异步通知时间超出允许窗口");
    }

    private static void requireWithinWindow(Instant callbackTime, String channel, String orderSn,
                                            String message) {
        long skewSeconds = Math.abs(Duration.between(callbackTime, Instant.now()).getSeconds());
        if (skewSeconds > MAX_SKEW_SECONDS) {
            log.warn("回调审计 channel={} result=REJECT_REPLAY callbackTime={} receivedAt={} skewSeconds={} orderSn={}",
                    channel, callbackTime, Instant.now(), skewSeconds, orderSn);
            throw new BusinessException(401, message);
        }
    }
}
