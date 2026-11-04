/*
 * 文件：TradeCostLimiter.java
 * 用途：公开建单、库存预占与防封海报获取的应用层成本限流，Redis原子计数防刷与高并发保护。
 * 维护注意：限制连接端IP、买家联系方式及代付凭据维度配额；超限拒绝不写单、不扣库存、不生成海报；Redis不可用时严格阻断不放行。代理场景依赖实际连接地址，键只存SHA-256摘要，不泄露明文联系信息。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class TradeCostLimiter {

    private final StringRedisTemplate redis;
    private final int orderCreateIpLimit;
    private final int orderCreateContactLimit;
    private final int orderCreateWindowSeconds;
    private final int preLockIpLimit;
    private final int preLockTokenLimit;
    private final int preLockWindowSeconds;
    private final int posterIpLimit;
    private final int posterTokenLimit;
    private final int posterWindowSeconds;

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 or redis.call('TTL',KEYS[1])<0 then "
            + "redis.call('EXPIRE',KEYS[1],ARGV[2]); end; if n>tonumber(ARGV[1]) then return 0 else return 1 end", Long.class);

    public TradeCostLimiter(
            StringRedisTemplate redis,
            @Value("${app.security.order-create-ip-limit:60}") int orderCreateIpLimit,
            @Value("${app.security.order-create-contact-limit:30}") int orderCreateContactLimit,
            @Value("${app.security.order-create-window-seconds:300}") int orderCreateWindowSeconds,
            @Value("${app.security.prelock-ip-limit:60}") int preLockIpLimit,
            @Value("${app.security.prelock-token-limit:20}") int preLockTokenLimit,
            @Value("${app.security.prelock-window-seconds:300}") int preLockWindowSeconds,
            @Value("${app.security.poster-ip-limit:60}") int posterIpLimit,
            @Value("${app.security.poster-token-limit:20}") int posterTokenLimit,
            @Value("${app.security.poster-window-seconds:60}") int posterWindowSeconds) {
        if (orderCreateIpLimit < 1 || orderCreateIpLimit > 10000
                || orderCreateContactLimit < 1 || orderCreateContactLimit > 10000
                || orderCreateWindowSeconds < 1 || orderCreateWindowSeconds > 3600
                || preLockIpLimit < 1 || preLockIpLimit > 10000
                || preLockTokenLimit < 1 || preLockTokenLimit > 10000
                || preLockWindowSeconds < 1 || preLockWindowSeconds > 3600
                || posterIpLimit < 1 || posterIpLimit > 10000
                || posterTokenLimit < 1 || posterTokenLimit > 10000
                || posterWindowSeconds < 1 || posterWindowSeconds > 3600) {
            throw new IllegalArgumentException("限流阈值须在1至10000之间，窗口须在1至3600秒之间");
        }
        this.redis = redis;
        this.orderCreateIpLimit = orderCreateIpLimit;
        this.orderCreateContactLimit = orderCreateContactLimit;
        this.orderCreateWindowSeconds = orderCreateWindowSeconds;
        this.preLockIpLimit = preLockIpLimit;
        this.preLockTokenLimit = preLockTokenLimit;
        this.preLockWindowSeconds = preLockWindowSeconds;
        this.posterIpLimit = posterIpLimit;
        this.posterTokenLimit = posterTokenLimit;
        this.posterWindowSeconds = posterWindowSeconds;
    }

    public void checkOrderCreation(String address, String buyerContact) {
        String safeAddress = (address != null && !address.isBlank()) ? address.trim() : "127.0.0.1";
        checkBucket("order:ip:" + safeAddress, orderCreateIpLimit, orderCreateWindowSeconds,
                "建单过于频繁，请稍后重试", "交易建单服务暂不可用，请稍后重试");
        if (buyerContact != null && !buyerContact.isBlank()) {
            String normalizedContact = buyerContact.trim().toLowerCase(Locale.ROOT);
            checkBucket("order:contact:" + normalizedContact, orderCreateContactLimit, orderCreateWindowSeconds,
                    "建单过于频繁，请稍后重试", "交易建单服务暂不可用，请稍后重试");
        }
    }

    public void checkPreLock(String address, String token) {
        String safeAddress = (address != null && !address.isBlank()) ? address.trim() : "127.0.0.1";
        checkBucket("prelock:ip:" + safeAddress, preLockIpLimit, preLockWindowSeconds,
                "预占尝试过于频繁，请稍后重试", "库存预占服务暂不可用，请稍后重试");
        if (token != null && !token.isBlank()) {
            checkBucket("prelock:token:" + token.trim().toUpperCase(Locale.ROOT), preLockTokenLimit, preLockWindowSeconds,
                    "预占尝试过于频繁，请稍后重试", "库存预占服务暂不可用，请稍后重试");
        }
    }

    public void checkPosterGeneration(String address, String token) {
        String safeAddress = (address != null && !address.isBlank()) ? address.trim() : "127.0.0.1";
        checkBucket("poster:ip:" + safeAddress, posterIpLimit, posterWindowSeconds,
                "海报获取过于频繁，请稍后重试", "海报服务暂不可用，请稍后重试");
        if (token != null && !token.isBlank()) {
            checkBucket("poster:token:" + token.trim().toUpperCase(Locale.ROOT), posterTokenLimit, posterWindowSeconds,
                    "海报获取过于频繁，请稍后重试", "海报服务暂不可用，请稍后重试");
        }
    }

    private void checkBucket(String identifier, int limit, int windowSeconds, String limitMessage, String unavailableMessage) {
        Long allowed;
        try {
            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(identifier.getBytes(StandardCharsets.UTF_8)));
            allowed = redis.execute(SCRIPT, List.of("rate:cost:" + hash), String.valueOf(limit), String.valueOf(windowSeconds));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new BusinessException(503, unavailableMessage);
        }
        if (allowed == null) {
            throw new BusinessException(503, unavailableMessage);
        }
        if (allowed != 1L) {
            throw new BusinessException(429, limitMessage);
        }
    }
}
