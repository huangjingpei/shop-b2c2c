/*
 * 文件：StpInterfaceImpl.java
 * 用途：为Sa-Token提供当前会话角色和权限集合。
 * 维护注意：角色从会话读取，细粒度权限当前为空；新增员工权限需要服务端授权设计，不能仅改前端菜单。
 */
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
