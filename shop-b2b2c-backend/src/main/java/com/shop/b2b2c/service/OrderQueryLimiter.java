/*
 * 文件：OrderQueryLimiter.java
 * 用途：POST买家查单的订单维度及连接IP维度限流，Redis异常时关闭查询而不是放行。
 * 维护注意：只使用连接端IP，不信任任意X-Forwarded-For；代理场景可能共享IP桶，需按实际拓扑配置可信代理。单键Lua将计数和TTL原子执行，键仅存摘要，不包含联系方式或领取凭证。
 */
package com.shop.b2b2c.service;
import com.shop.b2b2c.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
@Service
@RequiredArgsConstructor
public class OrderQueryLimiter {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 or redis.call('TTL',KEYS[1])<0 then "
            + "redis.call('EXPIRE',KEYS[1],ARGV[2]); end; if n>tonumber(ARGV[1]) then return 0 else return 1 end", Long.class);
    public void check(String orderSn, String address) {
        checkBucket("ip:" + address, 300);
        // MySQL commonly compares order numbers case-insensitively; variants must share the same bucket.
        checkBucket("order:" + orderSn.toUpperCase(java.util.Locale.ROOT), 60);
    }
    private void checkBucket(String identifier, int limit) {
        Long allowed;
        try { allowed = redis.execute(SCRIPT, List.of(key(identifier)), String.valueOf(limit), "300"); }
        catch (Exception e) { throw new BusinessException(503, "查单服务暂不可用，请稍后重试"); }
        if (allowed == null) throw new BusinessException(503, "查单服务暂不可用，请稍后重试");
        if (allowed != 1L) throw new BusinessException(429, "查询过于频繁，请稍后重试");
    }
    private String key(String identifier) {
        try { return "rate:buyer:lookup:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identifier.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
