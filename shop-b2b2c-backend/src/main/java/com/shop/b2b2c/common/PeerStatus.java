/*
 * 文件：PeerStatus.java
 * 用途：代付状态的持久化及API字符串编码唯一来源。
 * 维护注意：与schema.sql和前端契约保持一致，常量可用于MyBatis注解SQL。保持String字段兼容既有响应；不将INTENT/LOCKED等历史未知值自动转成可付款状态，也不提供绕过事务/CAS的状态迁移。
 */
package com.shop.b2b2c.common;

public final class PeerStatus {
    private PeerStatus() { }

    public static final String INIT = "INIT";
    public static final String RESERVED_LOCK = "RESERVED_LOCK";
    public static final String PAID = "PAID";
    public static final String EXPIRED = "EXPIRED";
    // 保留既有存储/统计契约，不代表本轮实现取消订单业务。
    public static final String CANCELLED = "CANCELLED";
}
