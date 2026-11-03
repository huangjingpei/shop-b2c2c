/*
 * 文件：IdempotencyRecord.java
 * 用途：建单请求幂等摘要及响应快照模型。
 * 维护注意：响应含查单/分享凭证，仅供内部重放；禁止公开查询或记录日志。记录和订单必须同事务提交，不能单独删除后复用旧键。
 */
package com.shop.b2b2c.entity;
import lombok.Data;
@Data
public class IdempotencyRecord {
    private String requestHash;
    private String responseJson;
}
