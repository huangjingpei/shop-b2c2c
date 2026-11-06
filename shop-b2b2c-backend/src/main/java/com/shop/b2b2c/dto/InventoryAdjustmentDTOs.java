/*
 * 文件：InventoryAdjustmentDTOs.java
 * 用途：人工调库存请求、去除幂等摘要的记录白名单及游标分页响应。
 * 维护注意：数量为可售库存增减，非覆盖总库存；数量须为JSON整数，禁止Jackson把小数截断；商品/SKU编号按T37前端契约同时接受JSON整数与十进制字符串。
 * 商户和操作人只取会话。before/after是操作时快照，重放结果不代表实时库存。
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
    /**
     * 库存标识同时接受 JSON 整数与十进制数字字符串：T37 后管理端把 Long 编号按字符串提交，
     * 仅收整数会让人工调库存整条链路返回 400；两种形式都只做整数解析，拒绝小数、负数与前导零。
     */
    public static class WholeLong extends StdDeserializer<Long> {
        public WholeLong() { super(Long.class); }
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                return parser.getLongValue();
            }
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                String value = parser.getText().trim();
                if (value.matches("[1-9][0-9]*")) {
                    try {
                        return Long.valueOf(value);
                    } catch (NumberFormatException ignored) {
                        // 超出 Long.MAX_VALUE 时统一按格式错误拒绝，不回显原文。
                    }
                }
            }
            throw JsonMappingException.from(parser, "库存标识须为JSON整数或十进制数字字符串");
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
