/*
 * 文件：OrderIdempotencyService.java
 * 用途：为三类HTTP建单提供参数冲突检测、事务内单次执行及原响应重放。
 * 维护注意：幂等键是私密重试凭证，不是身份认证；商家scope必须来自会话。占位、业务写入和结果快照同事务提交；禁止提前提交占位，重放也在内部事务行锁下完成。调用方不得已有外层业务事务，确保重试可以独立回滚；记录不自动过期，清理前需另立保留策略。
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
import java.util.function.Supplier;

@Service
public class OrderIdempotencyService {
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
        this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
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
                        // R26: 剥离海报大字段 Base64 避免幂等表体积膨胀与重复存储
                        Map<String, Object> storable = new LinkedHashMap<>(result);
                        storable.remove("posterBase64");
                        response = encode(storable);
                        if (mapper.complete(storageKey, response) != 1) throw new IllegalStateException("幂等响应保存失败");
                    }
                    try {
                        Map<String, Object> replayed = json.readValue(response, new TypeReference<Map<String,Object>>() {});
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
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("幂等数据序列化失败", e); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
