/*
 * 文件：SysUser.java
 * 用途：平台/商户后台账号、角色及店铺绑定模型。
 * 维护注意：password保存BCrypt散列，实体不可直接公开；角色与merchantId变更需同步会话撤销，修改isActive不自动保证旧会话立刻失效。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId; // 平台超管为 NULL，商家员工绑定店铺ID
    private String username;
    private String password;
    private String realName;
    private String roleCode; // SUPERADMIN, MERCHANT_ADMIN, MERCHANT_STAFF
    private Boolean isActive = true;
    /** D11 首次登录强制改密标记：1-须先改密才能访问业务工作台；审核开店生成的店长账号落 1，改密成功清 0。 */
    private Boolean mustChangePassword = false;
    private LocalDateTime createdAt;
}
