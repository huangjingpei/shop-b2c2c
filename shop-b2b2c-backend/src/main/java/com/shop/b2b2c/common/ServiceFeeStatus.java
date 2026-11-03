/*
 * 文件：ServiceFeeStatus.java
 * 用途：集中声明商户服务费配置已支持的五个字符串编码。
 * 维护注意：TRIAL与EXEMPTED、OVERDUE与ARREARS分别保留，禁止当作别名自动转换；这些值只是配置标记，不实现扣费、到期转换或自动停服。新增编码须同步界面、Schema说明和契约测试。
 */
package com.shop.b2b2c.common;

import java.util.Set;

public final class ServiceFeeStatus {
    private ServiceFeeStatus() { }
    public static final String NORMAL = "NORMAL";
    public static final String TRIAL = "TRIAL";
    public static final String OVERDUE = "OVERDUE";
    public static final String EXEMPTED = "EXEMPTED";
    public static final String ARREARS = "ARREARS";
    public static final Set<String> SUPPORTED = Set.of(NORMAL, TRIAL, OVERDUE, EXEMPTED, ARREARS);
}
