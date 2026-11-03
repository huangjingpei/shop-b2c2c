/*
 * 文件：AuthController.java
 * 用途：管理员和商家登录、退出、当前身份及存活探针接口。
 * 维护注意：密码用BCrypt验证；登录先限制连接IP，再校验参数并限制账号，Redis故障拒绝登录；退出撤销服务端会话；me校验账号状态和授权变化；health仅为存活响应，不代表数据库和Redis就绪。员工细粒度权限尚待完善。
 */
package com.shop.b2b2c.controller;

import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.entity.SysUser;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.SysUserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "认证服务 (Auth - Sa-Token)")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserMapper userMapper;
    private final com.shop.b2b2c.service.AuthSessionService sessions;
    private final MerchantStoreMapper storeMapper;
    private final com.shop.b2b2c.service.AuthLoginLimiter loginLimiter;

    @Data
    public static class LoginDTO {
        private String username;
        private String password;
    }

    @Operation(summary = "应用存活探针（不检查依赖；部署就绪使用/api/v1/auth/ready）")
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> map = new HashMap<>();
        map.put("status", "UP");
        map.put("app", "shop-b2b2c-backend");
        map.put("timestamp", System.currentTimeMillis());
        return R.ok(map);
    }

    @Operation(summary = "管理员/商家后台统一登录 (基于 Sa-Token 多端会话)")
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginDTO dto, jakarta.servlet.http.HttpServletRequest request) {
        loginLimiter.checkAddress(request.getRemoteAddr());
        if (dto == null || dto.getUsername() == null || dto.getUsername().isBlank() || dto.getUsername().length() > 64
                || dto.getPassword() == null || dto.getPassword().isEmpty() || dto.getPassword().length() > 1024) {
            throw new BusinessException(400, "请填写有效的账号和密码");
        }
        String username = dto.getUsername().strip();
        loginLimiter.checkAccount(username);
        SysUser user = userMapper.findByUsername(username)
                .orElseThrow(() -> new BusinessException("用户名或密码错误"));

        if (!BCrypt.checkpw(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new BusinessException("该账户已被禁用，请联系管理员");
        }

        if (user.getRoleCode() == null || !com.shop.b2b2c.service.AuthSessionService.ROLES.contains(user.getRoleCode())) {
            throw new BusinessException(403, "账号角色不受支持");
        }
        // 每次登录独立令牌：角色和店铺快照不能被同账号后续登录覆盖。
        StpUtil.login(user.getId());
        if (user.getUsername() != null) StpUtil.getTokenSession().set("username", user.getUsername());
        if (user.getRoleCode() != null) StpUtil.getTokenSession().set("role", user.getRoleCode());
        if (user.getMerchantId() != null) StpUtil.getTokenSession().set("merchantId", user.getMerchantId());

        String token = StpUtil.getTokenValue();

        Map<String, Object> res = new HashMap<>();
        res.put("token", token);
        res.put("userId", user.getId());
        res.put("username", user.getUsername());
        res.put("realName", user.getRealName());
        res.put("roleCode", user.getRoleCode());
        res.put("merchantId", user.getMerchantId());

        if (user.getMerchantId() != null) {
            storeMapper.findById(user.getMerchantId()).ifPresent(store -> {
                res.put("storeName", store.getStoreName());
                res.put("storeCode", store.getStoreCode());
            });
        } else {
            res.put("storeName", "平台总部总店");
        }

        return R.ok("登录成功", res);
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public R<Void> logout() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
        return R.ok("退出成功", null);
    }

    @Operation(summary = "获取当前登录人信息")
    @GetMapping("/me")
    public R<Map<String, Object>> getProfile() {
        SysUser user = sessions.requireCurrentUser();

        Map<String, Object> res = new HashMap<>();
        res.put("id", user.getId());
        res.put("username", user.getUsername());
        res.put("realName", user.getRealName());
        res.put("roleCode", user.getRoleCode());
        res.put("merchantId", user.getMerchantId());

        if (user.getMerchantId() != null) {
            storeMapper.findById(user.getMerchantId()).ifPresent(store -> {
                res.put("store", store);
            });
        }
        return R.ok(res);
    }
}
