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
    private LocalDateTime createdAt;
}
