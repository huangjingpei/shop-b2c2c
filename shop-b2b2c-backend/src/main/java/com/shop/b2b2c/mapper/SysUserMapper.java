/*
 * 文件：SysUserMapper.java
 * 用途：SysUser领域的MyBatis基础持久化接口。
 * 维护注意：账号基础查询；不得公开散列密码，账号/角色/店铺变更需要额外的授权与会话处理。 普通save不是并发控制，资金/库存更新须专用事务与条件SQL。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    default Optional<SysUser> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default SysUser save(SysUser entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default Optional<SysUser> findByUsername(String username) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)));
    }
}
