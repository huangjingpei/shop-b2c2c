package com.shop.b2b2c.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

public class SecurityUtils {

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getCurrentDetails() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof Map) {
            return (Map<String, Object>) auth.getDetails();
        }
        return null;
    }

    public static Long getCurrentMerchantId() {
        Map<String, Object> details = getCurrentDetails();
        if (details != null && details.get("merchantId") != null) {
            return Long.valueOf(details.get("merchantId").toString());
        }
        return null;
    }

    public static String getCurrentRoleCode() {
        Map<String, Object> details = getCurrentDetails();
        if (details != null && details.get("roleCode") != null) {
            return details.get("roleCode").toString();
        }
        return null;
    }

    public static boolean isSuperAdmin() {
        return "SUPERADMIN".equals(getCurrentRoleCode());
    }
}
