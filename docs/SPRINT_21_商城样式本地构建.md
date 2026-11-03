# Sprint 21：商城样式本地构建

本文记录主台账D38中商城运行时Tailwind CDN依赖的修复、边界与人工验证命令。状态：**实现和测试用例已编写，待人工验证**。海报事务内生成、Base64存储及订单列表大字段仍待处理，D38保持进行中。

## 根因与实现范围

index.html原在浏览器加载cdn.tailwindcss.com并配置主题，导致页面样式依赖第三方脚本即时执行。现改为main.js导入本地CSS，由Vite/PostCSS/Tailwind生成站内静态样式，删除CDN及内联运行时配置，保留原系统字体和品牌配色。

新增tailwind.config.cjs、postcss.config.cjs及src/styles/tailwind.css。CJS避免type=module兼容歧义；内容扫描基于配置文件绝对路径并规范化Windows分隔符，覆盖HTML及src下Vue/JS/MJS，避免从仓库根运行时漏扫。动态class仍需完整字面量，不能依赖运行时拼接产生未构建的类；详见[Tailwind 3内容扫描规则](https://v3.tailwindcss.com/docs/content-configuration)。

沿用五个既有品牌色，另补Store.vue已经引用的brand-300橙色边框色阶；旧CDN配置未定义它，悬停边框原本无对应规则。这是同一配置缺陷的局部修复，不引入页面重设计或新的主题体系。

构建接入参考[Tailwind 3官方Vite指南](https://v3.tailwindcss.com/docs/guides/vite)。明确使用Tailwind 3.4.17兼容当前模板，而非同时迁移至另一主版本。新增PostCSS和Autoprefixer开发依赖，通过npm管理器更新依赖锁并固定版本；本轮仅执行package-lock-only、ignore-scripts及关闭审计/资金提示的元数据更新，没有执行安装生命周期、编译或测试。静态比对既有依赖版本未变，新增74条传递依赖记录；未进行依赖漏洞审计。

package.json用于依赖和命令声明，package-lock.json用于可重复安装及完整性校验；两者为严格JSON，不添加注释或说明字段，用本文记录用途与跳过原因，锁文件不能手工拼接。浏览器无需加载这些开发依赖，构建/安装阶段仍需要依赖源可访问，不代表可以断网安装。

## 验证用例与范围

shared/storefrontStyles.test.mjs使用项目自己的PostCSS配置、真实Tailwind转换及实际源码，在内存生成一次CSS供6个用例复用：

- HTML不再加载外部样式运行时，main.js导入本地CSS。
- 品牌底色/文字/hover及遗漏的边框色阶实际生成且颜色正确。
- index任意背景色与卡片文本截断保留。
- sm/lg网格断点及列数正确。
- Vue动态class两个分支、disabled状态均保留。
- Tailwind指令被处理，系统字体存在且不引入外部CSS import。

测试需要先按锁文件安装商城依赖，不应在模块缺失时跳过。未执行这些测试或Vite生产构建，PostCSS单测不证明Vite产物、浏览器视觉或所有部署base已经通过。后端无行为和SQL变化，本轮不新增Maven测试要求。

## 用户手动执行

工作目录：`E:\shop-b2b2c`，PowerShell：

```powershell
npm --prefix shop-storefront-vue ci
node --test shared/storefrontStyles.test.mjs
$env:NODE_OPTIONS='--max-old-space-size=512'
$env:VITE_APP_BASE='/'
npm --prefix shop-storefront-vue run build
```

预期样式测试6项无失败/无跳过，生产构建成功且dist含本地CSS。需要完整前端规则回归时：

```powershell
node --test shared/goodsType.test.mjs shared/idempotency.test.mjs shared/buyerReceipt.test.mjs shared/authSession.test.mjs shared/productEditing.test.mjs shared/inventoryAdjustment.test.mjs shared/cardAssets.test.mjs shared/orderStatistics.test.mjs shared/storePage.test.mjs shared/serviceFee.test.mjs shared/storefrontStyles.test.mjs
```

浏览器打开新产物并禁用缓存，检查首页、空店/店铺、商品、查单及代付页的移动/桌面布局、hover、禁用按钮和切换控件；网络面板应只请求本站构建CSS，不再请求Tailwind CDN。阻断该CDN再刷新，样式应正常。图片、业务API及其他远程资源仍可能依赖网络，本轮不承诺整个商城离线可用。

若验证Sprint 18保留的/m布局，须使用/m/构建base并与对应网关/产物目录配套，不能把根商城产物混用；路径约定仍按D35文档处理。代理本轮仅静态审阅、依赖锁元数据更新、git diff格式和文档链接检查，不使用历史结果代替本轮人工验证。
