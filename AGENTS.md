# 项目开发与维护约定

本文件用于向后续开发人员和代码代理说明本仓库的维护要求，适用于整个项目。它不替代业务设计、接口契约或交付验收记录。

## 文件说明

- 每次新增文件，必须在文件开头说明该文件的用途、关键约定及维护时需要注意的细节。
- 修改缺少说明的既有文件时，补齐文件说明；业务语义变化时同步更新说明，避免留下过时注释。
- 使用文件格式支持的注释：Java/JavaScript用块注释，Vue/XML/Markdown用HTML注释，SQL用`--`，YAML用`#`。XML声明必须保留在首行。
- Markdown等文档也可用清晰的开篇文字声明用途。JSON、依赖锁文件、二进制/签名文件及可能改变解析、编译、运行行为的文件不强行加注释，也不为此新增业务字段；应在相邻维护文档说明用途与跳过原因。
- 说明必须针对该文件：按实际情况指出事务和锁、权限与敏感字段、状态与数据语义、接口兼容、迁移前提、测试环境或部署依赖。不要只写空泛模板。
- 注释不应复制密钥、卡密、用户隐私或生产凭据，也不能把尚未实现的功能描述成已完成。

## 修改与验证

- 每轮交付明确主台账D/F编号、实际子范围、代理自行细化的规则及验收状态，并更新docs/README.md导航。原清单外独立功能单列候选，不把技术实现细化冒充用户已确认的业务规则。
- 修复前核对docs/问题依赖与修复边界.md，复用既有规则与接口；避免重复建设。实际Bug仍须修复并补根因/回归用例。验收由用户自行管理，不催验收、不默认通过，不阻塞后续独立修复。
- 按2026-11-04协作约定：代码代理负责实现、测试用例、静态审阅、文件说明及交付文档；编译、测试执行和生产构建由用户手动运行。除非用户明确重新授权，代理不得主动运行这些命令。
- 每个任务交付执行目录、准确验证命令、预期结果及未验证项；用户反馈通过前保留“待验证”说明，不宣称检查通过或将待验证问题标为“已修复”。Git提交须注明执行方与验证状态。此前已执行结果只作历史记录，不覆盖后续修改。
- 每次行为修改都应补充或调整相应单元测试，必要时补数据库事务、并发和接口集成测试；修复既要覆盖拒绝非法操作，也要覆盖合法操作。
- 注释、文档变更应检查格式和语法兼容；不得借补注释夹带业务行为变化。
- 用户手动验证入口：后端`mvn -f shop-b2b2c-backend/pom.xml test`；前端规则`node --test shared/goodsType.test.mjs shared/idempotency.test.mjs shared/buyerReceipt.test.mjs shared/authSession.test.mjs shared/productEditing.test.mjs shared/inventoryAdjustment.test.mjs shared/cardAssets.test.mjs shared/orderStatistics.test.mjs shared/storePage.test.mjs shared/serviceFee.test.mjs shared/storefrontStyles.test.mjs shared/storedPoster.test.mjs shared/orderList.test.mjs shared/adminAccount.test.mjs shared/clipboard.test.mjs shared/peerPayCashier.test.mjs shared/peerPayReservation.test.mjs shared/productDetail.test.mjs shared/publicCatalog.test.mjs shared/financeReview.test.mjs shared/onboardingReview.test.mjs`。涉及Vue或前端模块时交付对应工程的生产构建命令。
- 样式规则测试storefrontStyles.test.mjs会在内存运行真实PostCSS/Tailwind；用户须先执行`npm --prefix shop-storefront-vue ci`安装锁定依赖，不得因缺依赖跳过。JSON依赖文件不插注释，维护说明见Sprint 21。
- 同一工作区的Maven clean/compile/test必须串行执行，避免共享target被其他构建覆盖；不同前端工程的独立构建可并行。
- 阿里云打包/构建隔离用例由用户在Linux/WSL运行`python3 -m unittest discover -s deploy/tests -p 'test_aliyun_build.py' -v`；假工具不编译业务，不连接远端。Windows原生跳过不代表验证通过，真实构建按对应Sprint文档另行执行。
- 通用网关隔离用例由用户在已安装Nginx的Linux/WSL运行`python3 -m unittest discover -s deploy/tests -p 'test_nginx_gateway.py' -v`；仅启动随机回环端口测试进程，不修改系统站点。缺Nginx跳过不算通过，Sprint 20后含8项Nginx回归及2项只读脚本契约，具体范围见Sprint 19/20。
- 最小只读CI见`.github/workflows/ci.yml`，本地重跑入口`bash scripts/regression.sh`；两者只执行后端H2单测、shared规则用例与deploy隔离用例，不连业务库、不构建发布产物。跳过项不算通过，CI绿不替代人工验收与真实前端生产构建。
- 测试使用隔离环境，不连接业务数据库。H2通过不能替代真实MySQL或渠道联调，报告中应明确验证范围。
- 问题台账位于`docs/B2B2C_项目缺陷与未实现功能清单_2026-11-03.md`。处理问题时同步状态；部分完成的项目保留“部分修复”，不要把整个业务域标为已完成。
- Git提交说明须清楚记录问题范围、具体行为变化、验证结果和未完成事项。不要覆盖与当前任务无关的已有修改。

## 开发期数据库基线

- 当前允许重建的开发数据库以`shop-b2b2c-backend/src/main/resources/db/schema.sql`为唯一运行时SQL入口，新表、索引和种子变化同步到该文件。不得把删库操作写入启动脚本。
- 原002/003独立迁移已合并删除，不再要求开发重建时逐一执行；schema不升级已有列，保留数据的环境另做版本升级方案。
- SQL初始化失败须阻断启动；测试不得通过忽略SQL错误证明结构正确。H2结构仅为隔离测试夹具，不能替代MySQL基线。
