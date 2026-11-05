/*
 * 文件：AdminBootstrap.java
 * 用途：生产首次部署的受控管理员初始化，避免在schema或部署脚本中写入固定超管口令。
 * 维护注意：
 *   1. 仅在显式提供BOOTSTRAP_ADMIN_USERNAME/PASSWORD且库中尚无SUPERADMIN时创建一次，不覆盖任何既有账号；
 *   2. 口令不得为空或演示默认口令admin123，校验失败直接阻断启动；
 *   3. 只在启动阶段执行，不参与登录鉴权；创建后应立即由环境负责人轮换并移除环境变量；
 *   4. 密码散列只在内存中生成，不记录明文或散列到日志。
 */
package com.shop.b2b2c.config;

import cn.dev33.satoken.secure.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.b2b2c.entity.SysUser;
import com.shop.b2b2c.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class AdminBootstrap implements ApplicationRunner {

    static final Set<String> DEMO_DEFAULT_PASSWORDS = Set.of("admin123");

    private final SysUserMapper users;
    private final String username;
    private final String password;

    public AdminBootstrap(SysUserMapper users,
                          @Value("${app.bootstrap-admin.username:}") String username,
                          @Value("${app.bootstrap-admin.password:}") String password) {
        this.users = users;
        this.username = username == null ? "" : username.strip();
        this.password = password == null ? "" : password.strip();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isEmpty() && password.isEmpty()) {
            return;
        }
        if (username.isEmpty() || password.isEmpty()) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_USERNAME与BOOTSTRAP_ADMIN_PASSWORD必须同时提供");
        }
        if (DEMO_DEFAULT_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD不得使用演示默认口令");
        }
        if (users.findByUsername(username).isPresent()) {
            return;
        }
        Long superAdminCount = users.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRoleCode, "SUPERADMIN"));
        if (superAdminCount != null && superAdminCount > 0) {
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername(username);
        admin.setPassword(BCrypt.hashpw(password));
        admin.setRealName("平台超级管理员（受控初始化）");
        admin.setRoleCode("SUPERADMIN");
        admin.setIsActive(true);
        users.save(admin);
    }
}
