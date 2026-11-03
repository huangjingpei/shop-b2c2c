# Sprint 19：通用网关路径与存活入口

本文记录主台账D35中通用Nginx网关和部署路径文档的修复范围，提供用户手动执行的隔离验证方式。状态：**实现和测试用例已编写，待人工验证**。D35云部署脚本、TLS及部署演练仍未完成；D37依赖就绪探针单独保留。

## 根因及变化

旧deploy/nginx/nginx.prod.conf面向不同工程：商城/m、管理端/admin、Uni-app注册页、backend容器主机名及Actuator，与当前Vue路由、单机布局及后端存活接口不匹配；根路径还按设备跳到不同应用。UA黑名单阻止curl/requests等合法自动化客户端，干扰探针及API验证。

本轮通用网关改用商城/、管理端/console/、回环后端8080，商品/店铺/代付和后台History路由分别回退到各自HTML；前端JS资源路径不存在返回404。API保留完整URI、查询参数和后端错误状态，保持IP限流与安全响应头，不再按UA拒绝合法工具；权限及登录限制仍由后端执行。

`/healthz`从静态healthy改为代理真实`/api/v1/auth/health`，后端不可达返回网关错误。它只表明应用存活，不能证明数据库、Redis或交易可用。未启用的Actuator、旧移动商城、旧管理路径和邀请注册入口明确404，避免回退HTML伪装成功。

新增[部署入口与路径约定](部署入口与路径约定.md)作为统一维护入口，解释产物/base/API/分享URL关系，并更新项目及云文档导航。选择商城根路径、/console及现有单机磁盘布局属于代理基于当前Vue与腾讯云布局的技术细化；没有新增买家注册、改变业务权限或宣称云脚本已统一。未修改数据库结构及初始化入口。

## 测试用例及限制

deploy/tests/test_nginx_gateway.py使用真实Nginx进程、临时静态文件、本地假HTTP后端及随机回环端口，不读写/etc、不连接外部网络，不修改系统服务。用例会独立执行nginx -t并启动测试Nginx，仅终止自身创建的进程。

7个测试覆盖：不同设备/自动化UA的商城与History路由；管理入口重定向、后台刷新与两端独立资源；缺失JS返回404；API完整路径/参数和401透传；真实存活路径及后端停止后502；无效旧入口拒绝；公开附件、缓存、MIME和安全头。临时路径包含空格，核对引号兼容。

未执行任何测试或Nginx命令。真实Vue产物、Spring Boot接口、TLS、数据库/Redis及线上部署不在假后端用例证明范围内；Linux/WSL缺少Nginx会跳过，跳过不算验收通过。

## 用户手动执行

在已安装Nginx及Python 3的Linux或WSL Ubuntu中，从仓库根运行；Windows环境示例：

```bash
cd /mnt/e/shop-b2b2c
PATH="/usr/sbin:$PATH" python3 -m unittest discover -s deploy/tests -p 'test_nginx_gateway.py' -v
```

预期7个测试无失败、无错误、无跳过。Nginx语法验证已包含在用例中，不要为验证本轮配置运行生产部署脚本，也不要将该片段直接作为完整nginx.conf执行。代理只完成静态审阅、git diff格式及文档链接检查。

如需真实前端产物，在隔离开发环境、依赖已按锁文件安装的前提下手动构建：

```bash
VITE_APP_BASE=/console/ npm --prefix shop-admin-vue run build
VITE_APP_BASE=/ npm --prefix shop-storefront-vue run build
```

预期两端构建成功；再按统一路径部署到开发环境核对页面与分享链接。以上命令未由代理执行；未更改Vue或后端业务代码，本轮不新增Maven回归要求。

## 保留范围

D35仍需将云端04-deploy.sh、systemd、证书/域名、Nginx模板选择及回滚与此路径约定同步，处理实际演练发现的问题。阿里云Sprint 18原/m候选包不直接适配当前根商城；腾讯云历史“开箱即用”“零停机”等表述不能视为当前验证结果。D34构建完整不代表D35运行部署已完成。
