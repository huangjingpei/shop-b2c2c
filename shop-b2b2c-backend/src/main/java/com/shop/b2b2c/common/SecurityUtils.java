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
        map.put("username", StpUtil.getSession().get("username"));
        map.put("roleCode", StpUtil.getSession().get("role"));
        map.put("merchantId", StpUtil.getSession().get("merchantId"));
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
        Object merchantId = StpUtil.getSession().get("merchantId");
        if (merchantId != null) {
            return Long.valueOf(merchantId.toString());
        }
        return null;
    }

    public static String getCurrentRoleCode() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        Object role = StpUtil.getSession().get("role");
        return role != null ? role.toString() : null;
    }

    public static boolean isSuperAdmin() {
        return "SUPERADMIN".equals(getCurrentRoleCode());
    }
}
