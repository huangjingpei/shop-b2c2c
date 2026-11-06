/*
 * 文件：OrderIdempotencyService.java
 * 用途：为三类HTTP建单提供参数冲突检测、事务内单次执行及原响应重放。
 * 维护注意：
 *  1. 幂等键是私密重试凭证，不是身份认证；商家scope必须来自会话。
 *  2. 占位、业务写入和结果快照同事务提交；禁止提前提交占位，重放也在内部事务行锁下完成。
 *  3. 调用方不得已有外层业务事务，确保重试可以独立回滚。
 *  4. T27/Q18-B：快照保留 30 天（由 IdempotencyPurgeJob 清理），落库前必须脱敏：
 *     queryToken/buyerContact 替换为 REDACTED 占位，posterBase64 直接剥离；重放时从 trade_order 回填，
 *     因此数据库只保留键→订单号及非敏感展示字段，不保存买家领取凭证与隐私。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.mapper.IdempotencyMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class OrderIdempotencyService {
    /** T27：落库时替换敏感响应值；重放时按 orderSn 从 trade_order 回填。 */
    private static final String REDACTED = "***REDACTED***";
    private static final Set<String> SENSITIVE_RESPONSE_FIELDS = Set.of("queryToken", "buyerContact");

    private final IdempotencyMapper mapper;
    private final ObjectProvider<TradeOrderMapper> orderMapperProvider;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    public OrderIdempotencyService(IdempotencyMapper mapper,
                                  ObjectProvider<TradeOrderMapper> orderMapperProvider,
                                  ObjectMapper json,
                                  PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.orderMapperProvider = orderMapperProvider;
        ObjectMapper stable = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        // Jackson 2.15 起 ObjectMapper.configure(MapperFeature...) 已弃用，改用 setConfig + with 保持原有序化行为。
        stable.setConfig(stable.getSerializationConfig().with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));
        stable.setConfig(stable.getDeserializationConfig().with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));
        this.json = stable;
        this.transaction = new TransactionTemplate(manager);
    }
    public static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new BusinessException(400, "请提供16至128位的Idempotency-Key（字母、数字、下划线或短横线）");
        }
    }
    public Map<String,Object> execute(String scope, String key, Object request, Supplier<Map<String,Object>> action) {
        requireKey(key);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("幂等入口必须在业务事务之外调用");
        }
        String storageKey = hash(scope + "\0" + key);
        String requestHash = hash(encode(request));
        // Unique order/token collision retries the whole rolled-back transaction; never retry inside a failed transaction.
        for (int attempt = 0; ; attempt++) {
            try {
                return transaction.execute(status -> {
                    mapper.claim(storageKey, requestHash);
                    var record = mapper.lock(storageKey);
                    if (!requestHash.equals(record.getRequestHash())) throw new BusinessException(409, "同一幂等键不能用于不同的建单参数");
                    String response = record.getResponseJson();
                    if (response == null) {
                        Map<String, Object> result = action.get();
                        // T27/Q18-B: 只落库轻量映射（orderSn 与非敏感展示字段），敏感值以占位符替代，海报大字段剥离。
                        response = encode(sanitize(result));
                        if (mapper.complete(storageKey, response) != 1) throw new IllegalStateException("幂等响应保存失败");
                    }
                    try {
                        Map<String, Object> replayed = json.readValue(response, new TypeReference<Map<String,Object>>() {});
                        // T27: 首次响应也走同一条回填路径，保证首次与重放响应完全一致。
                        rehydrate(replayed);
                        // R26: 统一从 trade_order 补齐海报 Base64，保持初次与重放响应契约及字段顺序完全一致
                        if (replayed.containsKey("peerPayToken") && !replayed.containsKey("posterBase64")) {
                            String token = (String) replayed.get("peerPayToken");
                            if (token != null && !token.isBlank()) {
                                TradeOrderMapper orders = orderMapperProvider.getIfAvailable();
                                if (orders != null) {
                                    String poster = orders.findStoredPosterByToken(token);
                                    if (poster != null) {
                                        replayed.put("posterBase64", poster);
                                    }
                                }
                            }
                        }
                        return replayed;
                    }
                    catch (Exception e) { throw new IllegalStateException("幂等响应读取失败", e); }
                });
            } catch (DuplicateKeyException e) {
                if (attempt >= 2) throw e;
            }
        }
    }
    /** T27：剥离海报大字段并把买家领取凭证/联系方式替换为占位符，数据库不保存敏感响应。 */
    private Map<String, Object> sanitize(Map<String, Object> result) {
        Map<String, Object> storable = new LinkedHashMap<>(result);
        storable.remove("posterBase64");
        for (String field : SENSITIVE_RESPONSE_FIELDS) {
            if (storable.containsKey(field)) storable.put(field, REDACTED);
        }
        return storable;
    }

    /** T27：用 trade_order 的原始值回填被脱敏字段；订单已不存在时置 null，绝不把占位符暴露给调用方。 */
    private void rehydrate(Map<String, Object> replayed) {
        Object orderSnValue = replayed.get("orderSn");
        if (!(orderSnValue instanceof String orderSn) || orderSn.isBlank()) return;
        TradeOrderMapper orders = orderMapperProvider.getIfAvailable();
        if (orders == null) return;
        var order = orders.findByOrderSn(orderSn).orElse(null);
        if (order == null) {
            for (String field : SENSITIVE_RESPONSE_FIELDS) {
                if (REDACTED.equals(replayed.get(field))) replayed.put(field, null);
            }
            return;
        }
        if (REDACTED.equals(replayed.get("queryToken"))) replayed.put("queryToken", order.getQueryToken());
        if (REDACTED.equals(replayed.get("buyerContact"))) replayed.put("buyerContact", order.getBuyerContact());
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("幂等数据序列化失败", e); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
