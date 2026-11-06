/*
 * 文件：ChannelHttpClient.java
 * 用途：真实支付渠道 HTTP 调用的超时与错误收敛工具 (T32)。
 * 维护注意：
 *  1. 只允许有限超时，禁止无限等待渠道响应；异常统一转为 502，不向调用方回显渠道内部报文。
 *  2. 不复用可变状态；每次调用传入完整 URL 与请求实体，避免渠道间串用凭据。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.common.BusinessException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

final class ChannelHttpClient {

    private ChannelHttpClient() {
    }

    static RestTemplate create() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        return new RestTemplate(factory);
    }

    static BusinessException failure(String channel) {
        return new BusinessException(502, channel + "渠道调用失败，请稍后重试");
    }

    static void ensureConfigured(boolean condition, String message) {
        if (!condition) {
            throw new BusinessException(503, message);
        }
    }

    static void handleRestFailure(String channel, RestClientException e) {
        throw failure(channel);
    }
}
