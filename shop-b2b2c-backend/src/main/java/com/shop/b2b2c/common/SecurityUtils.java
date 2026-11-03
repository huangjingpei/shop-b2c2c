/*
 * 文件：SecurityUtils.java
 * 用途：从Sa-Token当前令牌会话读取登录时的身份、角色和店铺上下文。
 * 维护注意：商家资产操作须用requireCurrentMerchantId并验证对象归属；未绑定店铺应拒绝，禁止回退店铺1或全平台。会话角色不能替代对象级租户校验。
 */
package com.shop.b2b2c.common;

import cn.dev33.satoken.stp.StpUtil;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一会话与用户上下文提取工具 (基于 Sa-Token)
 */
public class SecurityUtils {

    public static Map<String, Object> getCurrentDetails() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        Map<String, Object> map = new HashMap<>();
        map.put("userId", StpUtil.getLoginIdAsLong());
        map.put("username", StpUtil.getTokenSession().get("username"));
        map.put("roleCode", StpUtil.getTokenSession().get("role"));
        map.put("merchantId", StpUtil.getTokenSession().get("merchantId"));
        return map;
    }

    public static Long getCurrentUserId() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        return StpUtil.getLoginIdAsLong();
    }

    public static Long getCurrentMerchantId() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        Object merchantId = StpUtil.getTokenSession().get("merchantId");
        if (merchantId != null) {
            return Long.valueOf(merchantId.toString());
        }
        return null;
    }

    public static Long requireCurrentMerchantId() {
        Long merchantId = getCurrentMerchantId();
        if (merchantId == null) {
            throw new BusinessException(403, "当前账号未绑定店铺，禁止访问商家资产");
        }
        return merchantId;
    }

    public static String getCurrentRoleCode() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        Object role = StpUtil.getTokenSession().get("role");
        return role != null ? role.toString() : null;
    }

    public static boolean isSuperAdmin() {
        return "SUPERADMIN".equals(getCurrentRoleCode());
    }
}
