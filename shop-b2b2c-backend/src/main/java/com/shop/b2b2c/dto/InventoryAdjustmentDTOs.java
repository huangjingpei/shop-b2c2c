/*
 * 文件：InventoryAdjustmentDTOs.java
 * 用途：人工调库存请求、去除幂等摘要的记录白名单及游标分页响应。
 * 维护注意：数量为可售库存增减，非覆盖总库存；本接口数字须为JSON整数，禁止Jackson把小数截断。商户和操作人只取会话。before/after是操作时快照，重放结果不代表实时库存。
 */
package com.shop.b2b2c.dto;

import com.shop.b2b2c.entity.InventoryAdjustment;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

public final class InventoryAdjustmentDTOs {
    private InventoryAdjustmentDTOs() {}
    public record Adjust(@JsonDeserialize(using = WholeLong.class) Long productId,
                         @JsonDeserialize(using = WholeLong.class) Long skuId,
                         @JsonDeserialize(using = WholeInteger.class) Integer delta,
                         @JsonDeserialize(using = WholeInteger.class) Integer expectedStock, String reason) {}
    public static class WholeInteger extends StdDeserializer<Integer> {
        public WholeInteger() { super(Integer.class); }
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) throw JsonMappingException.from(parser, "库存字段须为JSON整数");
            return parser.getIntValue();
        }
    }
    public static class WholeLong extends StdDeserializer<Long> {
        public WholeLong() { super(Long.class); }
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) throw JsonMappingException.from(parser, "库存标识须为JSON整数");
            return parser.getLongValue();
        }
    }
    public record View(Long id, Long productId, Long skuId, Long actorId, Integer delta, Integer beforeStock,
                       Integer afterStock, String reason, LocalDateTime createdAt) {
        public static View from(InventoryAdjustment row) {
            return new View(row.getId(), row.getProductId(), row.getSkuId(), row.getActorId(), row.getDelta(),
                    row.getBeforeStock(), row.getAfterStock(), row.getReason(), row.getCreatedAt());
        }
    }
    public record Page(List<View> items, Long nextBeforeId) {}
}
