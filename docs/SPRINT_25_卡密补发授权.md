<!-- 文件说明：记录F12已售卡补发授权的范围、接口语义、数据库变化与人工验证步骤。
维护注意：补发不修改源已售卡的交付事实，新卡从同SKU未售池锁候选+CAS认领并复用原订单号；明文外带必须写REISSUE_PLAINTEXT审计。本文件不代表F12整体已验收。 -->
# Sprint 25：卡密补发授权

日期：2026-11-07。状态：代码与用例已编写，代理已执行后端测试、规则测试与前端构建，待用户人工验证。

## 范围与问题状态

F12 原清单"作废/补发授权"中的补发本轮落地；Sprint 24 已落地存量一致性核对。至此 F12 建议处理清单（校验卡密类型/重复指纹、加密存储、批次、作废/补发授权、分配记录与库存一致性核对）各项均有对应实现，F12 状态维持"进行中"，待用户对 Sprint 11/12/24/25 合并人工验证后再评估转"已修复"。

## 行为与接口

| 接口 | 权限 | 响应 |
| --- | --- | --- |
| POST /api/v1/merchant/cards/{id}/reissue | 店长/超管，营业状态闸门 | record（补发记录）+ newCard（新卡明文视图），Cache-Control: no-store |
| GET /api/v1/merchant/cards/{id}/reissue-record | 本店后台账号（含员工，只读） | 补发记录，不含卡密内容 |

补发语义：
- 仅已售出（status=1）且已绑定订单的卡可补发；每张已售卡最多补发一次（card_reissue.source_card_id 主键幂等），更换原因重试返回409。
- 同原因重放（网络失败重试）返回原记录与新卡明文，不重复扣库存/写记录；明文再次外带仍追加一条审计。
- 新卡从事务内锁定的同SKU未售池取一张（FOR UPDATE候选+CAS认领），复用原订单号；SKU可售库存扣1，前后库存写入记录。
- 安全口径与作废一致：SKU存在HELD预占拒绝；账面库存与在库未售卡数不一致拒绝（不能用补发修补漂移）；零库存/无候选卡拒绝409。
- 源卡保持已售状态，不回滚交付事实；订单全额退款时源卡与新卡按既有 voidSoldCardsByOrderSn 语义一并作废。
- 每次返回新卡明文（含重放）同事务写入 card_access_audit（action=REISSUE_PLAINTEXT，card_count=1）；审计失败整体回滚，不得返回明文。
- 越店卡返回404；匿名401；员工写403。管理端卡密列表对已售卡提供"补发"（店长/超管）与"补发记录"入口，新卡明文仅在成功弹窗展示，不写日志/本地缓存。

## 事务与数据库

独立READ_COMMITTED事务；锁顺序沿用卡密域约定（商户、商品、SKU、卡：findCard预读→lockSku→lockCard→lockAvailableCards→claimCard CAS）。新增1张表，基线47张表：

- `card_reissue`：source_card_id主键（源已售卡，自然幂等），uk_card_reissue_new（新卡唯一），含商户/商品/SKU/订单号/操作人/原因/前后库存；chk约束after=before-1；外键引用卡、商户、商品、SKU、操作人。不含卡密内容。

新库由唯一入口`db/schema.sql`建表；保留数据的开发库执行`V16__f12_card_reissue.sql`（CREATE TABLE IF NOT EXISTS幂等，不改已有列、不回填历史）。H2夹具sprint1-schema.sql已同步。不做生产数据验收承诺。

## 已编写用例

| 场景 | 覆盖 |
| --- | --- |
| 服务成功路径 | 锁候选+CAS认领绑定原订单、扣库存、记录字段、明文视图、REISSUE_PLAINTEXT审计、源卡状态不变 |
| 幂等 | 同原因重放返回同记录不再扣库存/写记录但追加审计；换原因409 |
| 不安全场景 | 未售/未绑定订单/HELD预占/零库存/账实漂移/无候选/认领竞态，均409且不写记录不扣库存 |
| 边界 | 未登录401、非法ID/原因400不进事务、越店404（事务内拒绝）、审计失败回滚 |
| 集成 | 已售卡补发端到端（新卡绑定/库存/审计/重放/换原因/记录查询与越店隔离）、HTTP匿名401/员工403/店长成功/no-store/记录不含明文/越店404 |
| 前端规则 | canReissueCard角色×状态×订单绑定矩阵、reissuePayload原因trim/必填/长度边界 |

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

预期：后端BUILD SUCCESS且Failures/Errors为0（默认仅跳过需显式开启的SchemaMySqlTest）；Node fail为0；Vite生产构建成功。保留数据的开发库另需执行`src/main/resources/db/migrations/V16__f12_card_reissue.sql`并确认可重复执行。

浏览器场景（隔离开发环境）：
1. 店长在卡密列表对已售卡点"补发"填原因：成功弹窗显示新卡明文，列表新卡转已售并绑定原订单号，SKU可售库存减1。
2. 同原因立即重试：返回同一新卡，库存不再扣减；换原因提示已补发过。
3. 对已售卡点"补发记录"：显示新卡ID/订单号/操作人/库存变化/原因/时间；未补发的卡提示无记录。
4. 员工账号看不到"补发"按钮，直接调接口返回403；未登录返回401。
5. 对账：补发后打开"库存一致性核对"，对应SKU账实一致（账面=在库未售）。

反馈失败用例名、文件/行号和必要堆栈，完整日志留本地，不粘贴真实卡密、令牌或完整请求体。人工反馈通过前，不把本轮标为已验证或已修复。
