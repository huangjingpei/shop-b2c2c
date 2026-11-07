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
    // F08：买家取消未支付订单或代付人撤销预占时写入；仍保持 String 存储兼容，历史未知值不得自动视为可付款。
    public static final String CANCELLED = "CANCELLED";
}
