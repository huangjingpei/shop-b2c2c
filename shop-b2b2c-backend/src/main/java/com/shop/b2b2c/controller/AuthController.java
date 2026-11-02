package com.shop.b2b2c.controller;

import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
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
    private final MerchantStoreMapper storeMapper;

    @Data
    public static class LoginDTO {
        private String username;
        private String password;
    }

    @Operation(summary = "管理员/商家后台统一登录 (基于 Sa-Token 多端会话)")
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginDTO dto) {
        SysUser user = userMapper.findByUsername(dto.getUsername())
                .orElseThrow(() -> new BusinessException("用户名或密码错误"));

        if (!BCrypt.checkpw(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new BusinessException("该账户已被禁用，请联系管理员");
        }

        // Sa-Token 登录注水
        StpUtil.login(user.getId());
        if (user.getUsername() != null) StpUtil.getSession().set("username", user.getUsername());
        if (user.getRoleCode() != null) StpUtil.getSession().set("role", user.getRoleCode());
        if (user.getMerchantId() != null) StpUtil.getSession().set("merchantId", user.getMerchantId());

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
        Map<String, Object> details = SecurityUtils.getCurrentDetails();
        if (details == null) {
            throw new BusinessException(401, "未登录");
        }
        Long userId = Long.valueOf(details.get("userId").toString());
        SysUser user = userMapper.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));

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
