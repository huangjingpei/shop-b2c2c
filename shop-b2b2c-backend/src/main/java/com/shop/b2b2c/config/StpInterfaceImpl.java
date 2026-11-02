package com.shop.b2b2c.config;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 权限与角色加载接口实现
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        if (!StpUtil.isLogin()) {
            return Collections.emptyList();
        }
        Object role = StpUtil.getSession().get("role");
        if (role != null) {
            return List.of(role.toString());
        }
        return Collections.emptyList();
    }
}
