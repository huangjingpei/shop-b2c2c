<!-- 文件说明：记录F12卡密存量一致性核对的范围、接口语义与人工验证步骤。
维护注意：核对为只读，不修正任何漂移或异常指纹；本文件不代表F12整体已验收，补发授权仍未实现。验证命令不得带-DforkCount=0（工作目录不在模块目录时既有静态扫描测试的相对路径会解析失败）。 -->
# Sprint 24：卡密存量一致性核对

日期：2026-11-07。状态：代码与用例已编写，代理已执行后端测试、规则测试与前端构建，待用户人工验证。

## 范围与问题状态

F12 继续为"进行中"：原清单"分配记录与库存一致性核对"中的存量一致性核对本轮落地（只读核对+异常处置指引）；"补发授权"仍未实现，列入后续候选。Sprint 11/12 的去重、作废、批次能力保持不变，仍需人工回归。本轮无数据库结构变化，不新增表或列。

## 行为与接口

| 接口 | 权限 | 响应 |
| --- | --- | --- |
| GET /api/v1/merchant/cards/consistency | 本店后台账号（含员工，只读） | items：按SKU的账面库存、在库未售/已售/已作废卡数、HELD预占数、异常指纹计数与consistent标记；汇总abnormalCount与fingerprintUnavailableTotal |

一致性判定：SKU账面库存等于在库未售卡数（status=0）即为consistent。预占不扣账面、不改卡状态，heldQuantity仅作展示参考，不参与判定。fingerprintUnavailable>0表示该SKU存在缺少明文或解密失败、无法参与导入去重的异常卡（T14标记），即便账实一致也计入abnormalCount，需人工按SKU筛选卡密列表后走既有作废流程处置。

核对为单条聚合SQL只读查询，不开启事务，不修正任何数据；账实漂移不能用导入或作废强行平账（作废接口本身也会拒绝漂移SKU）。响应不含卡密正文/指纹，Cache-Control: no-store；打烊/冻结店铺也可核对（不接营业状态闸门）。匿名拒绝401；跨店只返回本店卡密商品（goods_type=2）的SKU。全部Long字段（含数量）按全局Jackson配置序列化为JSON字符串，前端按字符串处理。

管理端卡密页新增"库存一致性核对"卡片：手动触发，异常行高亮（异常指纹优先于漂移展示），异常时给出作废/联系平台指引；核对请求失败保留上次结果，不把失败当成一致。

## 已编写用例

| 场景 | 覆盖 |
| --- | --- |
| 服务口径 | 漂移行与异常指纹计数、账实一致不报警、异常指纹在账实一致时也报警、空店空结果、未绑定店铺403、只读不触碰任何写入口 |
| 集成 | fixtures一致行、无卡SKU天然漂移、跨店隔离互不可见、漂移+异常指纹如实报告且不修正数据、作废后账实归零仍一致且保留已作废计数 |
| HTTP | 匿名401、本店员工只读通过、no-store响应头、ID字符串序列化与consistent布尔 |
| 前端规则 | consistencyIssue：异常指纹优先、严格一致才算ok、字符串数字兼容、未知/非法输入 |

## 请手动验证

Windows PowerShell工作目录`E:\shop-b2b2c`，同一工作区Maven命令串行执行。

```powershell
Set-Location E:\shop-b2b2c
$env:MAVEN_OPTS='-Xms32m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2'
mvn -f shop-b2b2c-backend/pom.xml test
node --test shared/cardAssets.test.mjs
$env:NODE_OPTIONS='--max-old-space-size=512'
npm --prefix shop-admin-vue run build
```

预期：后端BUILD SUCCESS且Failures/Errors为0（默认仅跳过需显式开启的SchemaMySqlTest）；Node fail为0；Vite生产构建成功。请勿加`-DforkCount=0`：该参数让测试进程继承命令启动目录，既有静态扫描用例的相对路径（src/main/resources/...）会解析失败，与本轮改动无关。

浏览器场景（隔离开发环境）：
1. 卡密页点击"开始核对"：正常店铺显示全部SKU账实一致。
2. 在数据库把某SKU账面库存改为与在库卡数不同后重新核对：对应行标"账实漂移"，汇总提示正确，且列表数据未被修改。
3. 把某张卡标记fingerprint_unavailable=1后核对：该行标"异常指纹待处置"；按SKU筛选上方列表作废该卡后再次核对恢复一致。
4. 员工账号可查看核对结果；未登录访问接口返回401。

反馈失败用例名、文件/行号和必要堆栈，完整日志留本地，不粘贴真实卡密、令牌或完整请求体。人工反馈通过前，不把本轮标为已验证或已修复。
