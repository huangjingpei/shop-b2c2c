/*
 * 文件：IdempotencyRecord.java
 * 用途：建单与人工库存调整的请求幂等摘要及响应快照模型。
 * 维护注意：响应可能含交易凭证或内部调整记录，仅供内部重放，禁止公开查询或记录日志。占位、业务写入和结果必须同事务提交，不能单独删除后复用旧键；业务/身份作用域由调用方隔离。
 */
package com.shop.b2b2c.entity;
import lombok.Data;
@Data
public class IdempotencyRecord {
    private String requestHash;
    private String responseJson;
}
