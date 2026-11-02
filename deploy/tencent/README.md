# Shop-B2B2C 腾讯云自动化部署标准指南 (通用方案)

本项目提供针对腾讯云（CVM / 轻量应用服务器 Lighthouse）开箱即用的自动化部署方案。支持单机环境共存、零停机时间平滑发布、4GB Swap 自动保护、SSL 证书自动申请与续期、以及多版本快速秒级回滚。

---

## 1. 架构总览与端口矩阵

| 服务组件 | 宿主机路径 / 容器 | 监听端口 | 访问入口 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| **Nginx 网关** | `/etc/nginx/sites-available/shop-b2b2c` | `80`, `443` | `https://1688.graddu.com` | 支持 HTTP->HTTPS 自动跳转，Certbot ACME 验证，静态文件托管 |
| **C 端商城与代付收银台** | `/opt/shop-b2b2c/www/storefront` | - | `https://1688.graddu.com/` | Vue3 + Tailwind 前端，包含商品详情、16688代付收银台 |
| **B 端管理后台** | `/opt/shop-b2b2c/www/admin` | - | `https://1688.graddu.com/console/` | Vue3 + Element Plus，超管治理与商户工作台 |
| **后端 API 服务** | `/opt/shop-b2b2c/app/app.jar` | `8080` | `https://1688.graddu.com/api/` | Spring Boot 3.2.5 + JDK 17 + MyBatis-Plus + Sa-Token |
| **MySQL 8.0** | 本机 MySQL 实例 | `3306` | 本地回环 `127.0.0.1:3306` | 数据库 `shop_b2b2c`，单一权威源 `schema.sql` 自动自愈 |
| **Redis 7** | 本机 Redis 实例 | `6379` | 本地回环 `127.0.0.1:6379` | Sa-Token 分布式多端 Token 鉴权，数据库分片 DB 2 |

---

## 2. 目录规范 (`/opt/shop-b2b2c`)

```text
/opt/shop-b2b2c/
├── .env                  # 生产环境变量（仅 root 可读，权限 600）
├── app/
│   └── app.jar           # 当前运行的后端 jar
├── releases/             # 历史发布版本存档
│   ├── 20261102-173000/
│   └── latest -> ...     # 指向当前版本的软链接
├── www/
│   ├── admin/            # B 端管理后台静态资源
│   └── storefront/       # C 端商城与收银台静态资源
├── uploads/              # 用户上传的图片与静态媒体文件
├── logs/
│   └── backend.log       # 后端运行日志
├── scripts/              # 运维控制脚本 (01-06)
└── backups/              # 历史 jar 产物与数据库快照
```

---

## 3. 快速部署命令 (本地执行)

在本地 Windows Git Bash / macOS / Linux 终端中运行：

```bash
# 1. 复制并调整配置文件（如已配置可跳过）
cp deploy/tencent/env.example.sh deploy/tencent/env.sh

# 2. 全量首次自动化部署 (准备骨架 -> 初始化基础设施 -> 源码打包构建 -> 部署上线)
bash deploy/tencent/deploy.sh --full

# 3. 仅重新编译构建并上线
bash deploy/tencent/deploy.sh --build
bash deploy/tencent/deploy.sh --deploy-only

# 4. 涉及数据库表结构变更时同步上线
bash deploy/tencent/deploy.sh --migrate

# 5. 查看云端服务运行状态
bash deploy/tencent/deploy.sh --status

# 6. 一键秒级版本回滚
bash deploy/tencent/deploy.sh --rollback
```

---

## 4. 默认账户凭据

- **平台超级管理员**: `superadmin` / `Admin@123456`
- **示范商家账户**: `merchant1` / `123456`
- **默认服务费率**: 3.00%（支持超管实时按商户调节与免收开关）
