package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.config.JwtUtils;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.SysUser;
import com.shop.b2b2c.repository.MerchantStoreRepository;
import com.shop.b2b2c.repository.SysUserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "认证服务 (Auth)")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserRepository userRepository;
    private final MerchantStoreRepository storeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    @Data
    public static class LoginDTO {
        private String username;
        private String password;
    }

    @Operation(summary = "管理员/商家后台统一登录")
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginDTO dto) {
        SysUser user = userRepository.findByUsername(dto.getUsername())
                .orElseThrow(() -> new BusinessException("用户名或密码错误"));

        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new BusinessException("该账户已被禁用，请联系管理员");
        }

        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRoleCode(), user.getMerchantId());

        Map<String, Object> res = new HashMap<>();
        res.put("token", token);
        res.put("userId", user.getId());
        res.put("username", user.getUsername());
        res.put("realName", user.getRealName());
        res.put("roleCode", user.getRoleCode());
        res.put("merchantId", user.getMerchantId());

        if (user.getMerchantId() != null) {
            storeRepository.findById(user.getMerchantId()).ifPresent(store -> {
                res.put("storeName", store.getStoreName());
                res.put("storeCode", store.getStoreCode());
            });
        } else {
            res.put("storeName", "平台总部总店");
        }

        return R.ok("登录成功", res);
    }

    @Operation(summary = "获取当前登录人信息")
    @GetMapping("/me")
    public R<Map<String, Object>> getProfile() {
        Map<String, Object> details = SecurityUtils.getCurrentDetails();
        if (details == null) {
            throw new BusinessException(401, "未登录");
        }
        Long userId = Long.valueOf(details.get("userId").toString());
        SysUser user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));

        Map<String, Object> res = new HashMap<>();
        res.put("id", user.getId());
        res.put("username", user.getUsername());
        res.put("realName", user.getRealName());
        res.put("roleCode", user.getRoleCode());
        res.put("merchantId", user.getMerchantId());

        if (user.getMerchantId() != null) {
            storeRepository.findById(user.getMerchantId()).ifPresent(store -> {
                res.put("store", store);
            });
        }
        return R.ok(res);
    }
}
