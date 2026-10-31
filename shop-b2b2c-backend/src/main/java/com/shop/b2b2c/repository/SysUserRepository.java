package com.shop.b2b2c.repository;

import com.shop.b2b2c.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, Long> {
    Optional<SysUser> findByUsername(String username);
}
