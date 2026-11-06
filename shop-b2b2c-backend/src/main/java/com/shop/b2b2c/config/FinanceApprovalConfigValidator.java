/*
 * 文件：FinanceApprovalConfigValidator.java
 * 用途：生产profile（prod）启动前置校验：财务双人复核开关必须为 true，否则构造期阻断启动，避免职责分离控制被静默关闭。
 * 维护注意：
 *  1. 只对 prod profile 生效；dev/test 仍允许 false，但 FinanceApprovalGuard 会对每次资金操作写 WARN 并在管理端提示单管理员模式。
 *  2. 复用唯一配置键 shop.finance.dual-approval-required / FINANCE_DUAL_APPROVAL_REQUIRED，不新建第二套开关。
 *  3. 校验在 bean 构造期完成：配置缺失、类型非法或 prod 下为 false 都会让 Spring 启动失败；异常信息不得包含凭据。
 *  4. 部署侧两云 04-deploy.sh 已把该键列为必填并限定 true/false；本类负责运行期兜底，两者不得互相替代。
 */
package com.shop.b2b2c.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class FinanceApprovalConfigValidator {

    /** 生产 profile 名称，与 application-prod.yml / 部署脚本 --spring.profiles.active=prod 保持一致。 */
    static final String PROD_PROFILE = "prod";

    public FinanceApprovalConfigValidator(
            Environment environment,
            @Value("${shop.finance.dual-approval-required:false}") boolean dualApprovalRequired) {
        if (environment.acceptsProfiles(Profiles.of(PROD_PROFILE)) && !dualApprovalRequired) {
            throw new IllegalStateException("生产环境必须开启双人复核："
                    + "shop.finance.dual-approval-required / FINANCE_DUAL_APPROVAL_REQUIRED 必须为 true，"
                    + "当前为 false，已阻断启动；开发/测试环境可显式设为 false");
        }
    }
}
