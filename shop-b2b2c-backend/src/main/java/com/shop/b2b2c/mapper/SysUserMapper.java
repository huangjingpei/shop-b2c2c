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
