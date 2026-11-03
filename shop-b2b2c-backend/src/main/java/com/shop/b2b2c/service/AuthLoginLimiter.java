/*
 * 文件：AuthLoginLimiter.java
 * 用途：密码校验前以Redis原子计数限制连接IP和账号的登录尝试，服务故障时拒绝认证。
 * 维护注意：成功和失败都计数，固定窗口首次命中开始计时，不按成功重置；仅信任连接地址，代理部署会共享IP桶。账号大小写/常见重音变体保守归一，键仅含摘要，不存密码或账号原文；此组件不替代员工权限与可信代理治理。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class AuthLoginLimiter {
    private final StringRedisTemplate redis;
    private final int accountLimit, ipLimit, windowSeconds;
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 or redis.call('TTL',KEYS[1])<0 then "
            + "redis.call('EXPIRE',KEYS[1],ARGV[2]); end; if n>tonumber(ARGV[1]) then return 0 else return 1 end", Long.class);

    public AuthLoginLimiter(StringRedisTemplate redis,
            @Value("${app.security.login-account-limit:20}") int accountLimit,
            @Value("${app.security.login-ip-limit:100}") int ipLimit,
            @Value("${app.security.login-window-seconds:300}") int windowSeconds) {
        if (accountLimit < 1 || accountLimit > 10000 || ipLimit < 1 || ipLimit > 10000
                || windowSeconds < 1 || windowSeconds > 3600) {
            throw new IllegalArgumentException("登录限流阈值须为1至10000，窗口须为1至3600秒");
        }
        this.redis = redis; this.accountLimit = accountLimit; this.ipLimit = ipLimit; this.windowSeconds = windowSeconds;
    }

    public void checkAddress(String address) { checkBucket("ip:" + address, ipLimit); }

    public void checkAccount(String username) {
        String normalized = Normalizer.normalize(username.strip(), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        checkBucket("account:" + normalized, accountLimit);
    }

    private void checkBucket(String identifier, int limit) {
        Long allowed;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identifier.getBytes(StandardCharsets.UTF_8)));
            allowed = redis.execute(SCRIPT, List.of("rate:auth:login:" + hash), String.valueOf(limit), String.valueOf(windowSeconds));
        } catch (Exception e) {
            throw new BusinessException(503, "登录服务暂不可用，请稍后重试");
        }
        if (allowed == null) throw new BusinessException(503, "登录服务暂不可用，请稍后重试");
        if (allowed != 1L) throw new BusinessException(429, "登录尝试过于频繁，请稍后重试");
    }
}
