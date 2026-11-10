/*
 * 文件：DualApprovalAccountValidator.java
 * 用途：双人复核开关（shop.finance.dual-approval-required）开启时的账号数量守卫：确认至少有两名启用状态的 SUPERADMIN。
 * 维护注意：
 *  1. 与 FinanceApprovalConfigValidator 的分工：后者在 bean 构造期校验开关本身（prod 必须 true），
 *     本类在 AdminBootstrap 之后校验账号数量，避免新库首次启动时误判为 0 名超管。
 *  2. 生产 profile 下不足 2 名直接阻断启动；开发/测试只写 WARN，但仍由 FinanceApprovalGuard
 *     在入驻初审等中间态流程推进前做 409 前置拒绝，不会让单据卡死。
 *  3. 只统计 role_code=SUPERADMIN 且 is_active=1 的账号，与 /admin/settlement/reviewers 候选口径一致；
 *     不得把商户账号或员工账号算作复核人。
 *  4. 开关关闭时直接返回，不查库；不得在校验中创建、修改或删除任何账号。
 */
package com.shop.b2b2c.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.b2b2c.entity.SysUser;
import com.shop.b2b2c.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** 账号数量守卫必须在 AdminBootstrap 之后执行，因此显式排在最后。 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DualApprovalAccountValidator implements ApplicationRunner {

    /** 生产 profile 名称，与 application-prod.yml / 部署脚本 --spring.profiles.active=prod 保持一致。 */
    static final String PROD_PROFILE = "prod";
    /** 双人复核至少需要的启用超管数量。 */
    static final int MIN_ACTIVE_SUPERADMINS = 2;

    private final boolean dualApprovalRequired;
    private final boolean prod;
    private final SysUserMapper users;

    public DualApprovalAccountValidator(
            Environment environment,
            @Value("${shop.finance.dual-approval-required:false}") boolean dualApprovalRequired,
            SysUserMapper users) {
        this.dualApprovalRequired = dualApprovalRequired;
        this.prod = environment.acceptsProfiles(Profiles.of(PROD_PROFILE));
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!dualApprovalRequired) {
            return;
        }
        Long count = users.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRoleCode, "SUPERADMIN")
                .eq(SysUser::getIsActive, true));
        long active = count == null ? 0L : count;
        if (active >= MIN_ACTIVE_SUPERADMINS) {
            return;
        }
        String message = "已开启双人复核，但启用状态的超级管理员不足 " + MIN_ACTIVE_SUPERADMINS
                + " 名（当前 " + active + " 名）；请先创建第二位 SUPERADMIN 账号";
        if (prod) {
            throw new IllegalStateException(message + "，已阻断启动");
        }
        log.warn("{}（开发/测试环境仅告警；入驻初审等流程会在推进前拒绝并提示）", message);
    }
}
