# Sprint 18：阿里云三端构建与产物边界

本文记录原台账D34的源码打包、服务器构建及失败发布边界修复，提供隔离验证命令。状态：**实现和测试用例已编写，待人工验证**。D35运行部署、Nginx、探针及部署演练尚未完成；本轮未连接远端或执行真实构建。

## 根因与实现

阿里云原打包入口只上传backend/apps，构建脚本也使用旧目录、旧jar名称及build:h5，缺少前端目录时直接跳过；因此命令成功可能没有当前商城产物。

本轮新增纯本地package-source.sh，由deploy.sh调用，校验并打包shop-b2b2c-backend、shop-admin-vue及shop-storefront-vue，保留两端锁文件，排除node_modules、target、dist、日志等既有产物。没有运行deploy.sh，其远程上传及部署行为不属于测试入口。

03-build.sh校验三个工程、Schema和锁文件，再使用Maven及npm ci构建；两前端均运行现有build脚本，明确传入独立输出目录与base。后端要求唯一且非空的当前工程jar，排除sources/javadoc；每前端要求非空index.html及JavaScript资源。缺端、依赖安装失败、构建失败、缺失/空产物均失败退出，不能作为完整发布。

产物写入唯一的新版本目录，避免重复时间戳与旧dist被当作本轮成果。全部校验并写入BUILD_ID后，以同文件系统rename替换latest软链接；失败保留旧指针、原版本内容及排错目录中的BUILD_FAILED。latest若为普通文件/目录则拒绝覆盖。构建不需要root，但用户须拥有目标目录写权限。

保留GMALL_ROOT、app.jar、www-admin及www-mobile/m发布布局，管理端base默认/console/、商城/m/。自定义管理路径需为合法非根目录路径，不能占用/m或/api；这是匹配历史部署布局的技术兼容细节，不代表商城全部链接已完成子路径适配。D35还要统一前后端URL、代理路由及运行方式。

## 依赖和剩余范围

- 03-build.sh不启动服务、不执行数据库迁移、不加载.env、不执行测试；Maven明确跳过测试，不能据此宣称验收通过。
- 本轮不改变腾讯云构建脚本；其可选前端分支等问题仍需独立核对，不能将阿里云修复推广为所有云环境完成。
- 阿里云04-deploy.sh及后续服务/探针仍有旧工程配置，D35继续保留；不要用本轮成功结果直接运行整套历史部署流程。
- Schema只从唯一运行时入口复制为归档文件schema.sql，未改变初始化入口或数据库结构。
- 产物存在性检查不能代替JAR启动、Vite真实构建、资源加载、API路由、数据库/Redis就绪和支付验收。未执行性能或远程部署演练。

## 用户手动验证

脚本用例位于deploy/tests/test_aliyun_build.py，使用临时目录及假Maven/npm，不连接网络或真实发布目录。覆盖正常/首次发布、路径含空格、相对根目录、连续构建、缺源码/锁文件、依赖失败、缺失/空/多jar、前端失败、缺失/空HTML和资源、非法base、latest普通目录保护，以及源码包内容和排除项。假工具通过不代表实际编译成功。

在Linux或WSL Ubuntu运行；Windows原生Python会跳过，跳过不算通过。WSL工作目录示例：

```bash
cd /mnt/e/shop-b2b2c
for file in deploy/aliyun/package-source.sh deploy/aliyun/deploy.sh deploy/aliyun/server/03-build.sh; do
  bash -n "$file" || exit 1
done
python3 -m unittest discover -s deploy/tests -p 'test_aliyun_build.py' -v
```

预期语法检查退出0，10个测试无失败、无错误、无跳过。以上命令由用户执行，代理未运行。

如需真实构建，在已安装Java 17、Maven、Node及npm的隔离Linux开发环境运行以下命令。此过程下载依赖并编译，仍由用户执行；同一源码工作区Maven不得并行：

```bash
BUILD_ROOT="$(mktemp -d /tmp/shop-build-XXXXXX)"
mkdir -p "$BUILD_ROOT/src"
git archive HEAD | tar -x -C "$BUILD_ROOT/src"
GMALL_ROOT="$BUILD_ROOT" ADMIN_PORTAL_PATH=/console bash deploy/aliyun/server/03-build.sh
```

预期只在该临时目录生成完整三端版本及latest，不访问线上发布目录。核对app.jar、schema.sql、BUILD_ID、www-admin/index.html和www-mobile/m/index.html及各自assets。真实构建失败时latest不应指向半成品；保留BUILD_FAILED及工具日志排查。不要以运行deploy.sh或04-deploy.sh代替这些隔离验证命令。

D34保持进行中/待人工验证，D35保留原状态；后续以实际反馈更新验证记录。
