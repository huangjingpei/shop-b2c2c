/*
 * 文件：AuthSessionService.java
 * 用途：对受保护请求核验服务端会话与当前账号状态、角色和店铺绑定的一致性。
 * 维护注意：每次请求读取数据库，不信任浏览器角色缓存；账号删除/禁用或授权变化须撤销旧会话，要求重新登录。数据库故障不得降级放行；对象级租户归属仍由业务接口校验。Q13-A 最小权限通过 requireManagementRole 在接口层拒绝员工，前端隐藏按钮不能替代该检查。
 */
package com.shop.b2b2c.service;
import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.entity.SysUser;
import com.shop.b2b2c.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.Set;
@Service
@RequiredArgsConstructor
public class AuthSessionService {
    private final SysUserMapper users;
    public static final Set<String> ROLES = Set.of("SUPERADMIN", "MERCHANT_ADMIN", "MERCHANT_STAFF");
    /** Q13-A：店长/平台管理员角色集合；员工不得推单、发货、调用资金接口或查看卡密明文。 */
    public static final Set<String> MANAGEMENT_ROLES = Set.of("SUPERADMIN", "MERCHANT_ADMIN");

    /** 接口层最小权限裁剪：角色快照已由每次请求的会话核对写入，这里不再重复读库。 */
    public static void requireManagementRole() {
        if (!isManagementRole()) {
            throw new BusinessException(403, "仅店长或平台管理员可执行该操作");
        }
    }

    public static boolean isManagementRole() {
        // 会话角色缺失（未登录、令牌升级前的旧会话等）按无权限处理，不能把 Set.contains(null) 抛成 500。
        String role = SecurityUtils.getCurrentRoleCode();
        return role != null && MANAGEMENT_ROLES.contains(role);
    }

    public SysUser requireCurrentUser() {
        StpUtil.checkLogin();
        SysUser user = users.findById(StpUtil.getLoginIdAsLong()).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getIsActive()) || user.getRoleCode() == null
                || !ROLES.contains(user.getRoleCode()) || !Objects.equals(user.getRoleCode(), SecurityUtils.getCurrentRoleCode())
                || !Objects.equals(user.getMerchantId(), SecurityUtils.getCurrentMerchantId())) {
            StpUtil.logout();
            throw new BusinessException(401, "登录信息已失效，请重新登录");
        }
        return user;
    }
}
