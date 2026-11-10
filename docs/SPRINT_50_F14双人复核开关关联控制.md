<!--
文件：SPRINT_50_F14双人复核开关关联控制.md
用途：记录入驻审核双人复核开关 shop.finance.dual-approval-required 的账号数量守卫、初审前置拒绝与启动期校验。
维护注意：本文只描述代码代理已完成的实现与待执行命令，不代表真实 MySQL、生产启动或浏览器交互已验证；开关仍为单一 finance 键，未拆分入驻独立开关。
-->
# Sprint 50：F14 双人复核开关关联控制

- 日期：2026-11-08
- 主台账：F14（P2，`进行中`，入驻审核子范围）
- 执行方：代码代理（实现、用例、静态审阅）
- 验证状态：代理已跑后端全量测试（1169 项、0 失败、1 项跳过），第 3 节新增/调整用例全部通过；真实 MySQL、生产启动与首次部署流程未验证，待人工验收

## 1. 背景与缺口

入驻审核的初审/复审复用财务双人复核开关 `shop.finance.dual-approval-required`。此前存在三个关联缺口：

1. 开关开启时，第一次 APPROVE 会先把申请推进到 `REVIEW_STAGE_SECONDARY`，第二次才校验“复审人不同于初审人”。若部署中只有一名启用 `SUPERADMIN`，单据会永久卡在待复审，第二次必然 403。
2. 生产 profile 强制开关为 true，但启动期只校验开关值，不校验“启用超管数量是否足够两人”，问题要到业务动作才暴露。
3. 系统没有新增平台账号的接口，第二个超管需要运维在库中创建；缺少明确的运行期提示。

本轮只做后端关联控制，**前端不做兜底**（不新增加载候选为空时禁用按钮等逻辑），账号数量按“两名超管”要求执行。

## 2. 实现

### 2.1 服务端账号数量预检

`FinanceApprovalGuard` 新增 `requireOtherActiveSuperAdmin(action, operatorId)`：

- 开关关闭：直接返回，不查库；
- 开关开启：按 `role_code='SUPERADMIN' AND is_active=1 AND id <> operatorId` 统计，存在至少一名其他启用超管才放行；
- 数量不足返回 409，提示“没有第二位启用状态的超级管理员，操作已拒绝；请先创建第二位 SUPERADMIN 账号”；
- 操作人为空返回 403。

该方法只读 `sys_user`，不创建、不修改、不删除账号。

### 2.2 入驻初审前置拒绝

`MerchantOnboardingService.approve` 在双人复核首次通过分支内、推进状态前调用上述预检。

- 数量不足：直接 409，申请保持 `SUBMITTED`，`review_stage` 不变，不写审计、不建店、不建号、不建费率；
- 数量充足：维持原行为，推进到 `SECONDARY`，由第二位超管终审。

`SECONDARY` 阶段的同人 403 校验保持不变，作为并发/绕过兜底。

### 2.3 启动期数量守卫

新增 `DualApprovalAccountValidator`（`ApplicationRunner`）：

- 开关关闭：直接返回，不查库；
- `prod` profile 且启用超管少于 2 名：抛 `IllegalStateException` 阻断启动；
- 非 prod 且数量不足：只写 WARN，业务动作仍由 2.2 拒绝；
- `AdminBootstrap` 增加 `@Order(LOWEST_PRECEDENCE - 1)`，保证受控初始化先创建首个超管，再由守卫统计，避免新库首次启动误判为 0 名。

生产首次部署若库中只有一名超管，启动会被阻断；运维需先在库中创建第二位启用 `SUPERADMIN` 后再启动。这是“生产必须双人复核”的显式代价，本文不提供自动扩权入口。

### 2.4 开关口径

- 继续使用唯一配置键 `shop.finance.dual-approval-required` / `FINANCE_DUAL_APPROVAL_REQUIRED`，未新增入驻专用开关；
- `FinanceApprovalConfigValidator` 仍负责 prod 下开关必须为 true；
- 新增守卫只负责“开关为 true 时的账号数量”，两者职责不重叠；
- 两云 `.env` 模板与部署脚本对 true/false 的校验不变。

## 3. 用例

- `FinanceApprovalGuardTest` 新增 2 项：严格模式存在第二位超管放行、数量为 0 返回 409；宽松模式不查库。
- `MerchantOnboardingServiceTest` 调整 1 项并新增 1 项：初审推进前需存在第二位超管；数量不足时 409、状态停留在 `SUBMITTED`、不写库不建店。
- 新增 `DualApprovalAccountValidatorTest` 4 项：prod 单超管阻断、prod 双超管放行、dev 单超管只告警、开关关闭不查库。

## 4. 可重跑命令

```bash
mvn -f shop-b2b2c-backend/pom.xml test -Dtest="FinanceApprovalGuardTest,MerchantOnboardingServiceTest,DualApprovalAccountValidatorTest,FinanceApprovalConfigValidatorTest"
mvn -f shop-b2b2c-backend/pom.xml test
```

预期：新增与调整用例通过；全量测试无新增失败。前端本轮无改动，不需要重新构建。

## 5. 未验证边界

- 真实 MySQL 上 `selectCount` 的执行与索引命中未验证；
- 生产首次部署“先建第二位超管再启动”的运维流程未演练；
- 开关 true/false 切换对存量 `SUBMITTED/REVIEWING/SECONDARY` 单据的行为未做专项回归；
- 未新增平台账号管理接口，第二位超管仍由运维创建；
- 未拆分入驻独立开关，财务与入驻仍共用同一键。
