# 文件：test_deploy_build_contract.py
# 用途：不依赖 Linux/Bash 的静态契约用例，锁定两云构建脚本仍指向当前三个工程、当前npm脚本，且产物目录与部署预检一致。
# 维护注意：只读取脚本文本，不执行构建、不连接服务器；与 test_aliyun_build.py/test_tencent_build.py 的假工具演练互补。
# 跨平台运行（Windows 也可），用于防止 D34 的 apps/admin-vue3、build:h5 等旧路径回归，以及 D35 的 /m、/invite、Actuator 旧网关入口回归。
# 通过不代表真实构建、Nginx 校验或部署已验收。
import unittest
from pathlib import Path

DEPLOY = Path(__file__).resolve().parents[1]
PROJECTS = ("shop-b2b2c-backend", "shop-admin-vue", "shop-storefront-vue")


def read(path):
    return (DEPLOY / path).read_text(encoding="utf-8")


class BuildScriptContractTest(unittest.TestCase):
    def test_build_scripts_target_current_projects_and_scripts(self):
        for cloud in ("aliyun", "tencent"):
            script = read(f"{cloud}/server/03-build.sh")
            with self.subTest(cloud=cloud):
                for project in PROJECTS:
                    self.assertIn(project, script, f"{cloud} 构建脚本缺少当前工程 {project}")
                for legacy in ("apps/admin-vue3", "apps/mobile-uni", "build:h5", "backend-springboot"):
                    self.assertNotIn(legacy, script, f"{cloud} 构建脚本仍引用旧入口 {legacy}")
                self.assertIn("npm ci", script)
                self.assertIn("npm run build", script)
                self.assertIn("VITE_APP_BASE", script)

    def test_build_requires_locks_unique_jar_and_frontend_assets(self):
        for cloud in ("aliyun", "tencent"):
            script = read(f"{cloud}/server/03-build.sh")
            with self.subTest(cloud=cloud):
                self.assertIn("package-lock.json", script)
                self.assertIn("-eq 1", script, "必须要求唯一后端jar")
                self.assertIn("index.html", script)
                self.assertIn("assets", script)

    def test_package_source_contains_three_projects_and_keeps_locks(self):
        for cloud in ("aliyun", "tencent"):
            with self.subTest(cloud=cloud):
                script = read(f"{cloud}/package-source.sh")
                for project in PROJECTS:
                    self.assertIn(project, script)
                self.assertIn("package-lock.json", script)
                self.assertIn("node_modules", script)
                self.assertIn("target", script)

    def test_deploy_prechecks_match_build_output_paths(self):
        cases = {
            "aliyun": ("www-admin", "www-storefront"),
            "tencent": ("www-admin", "www-storefront"),
        }
        for cloud, outputs in cases.items():
            build = read(f"{cloud}/server/03-build.sh")
            deploy = read(f"{cloud}/server/04-deploy.sh")
            with self.subTest(cloud=cloud):
                for output in outputs:
                    self.assertIn(f'${{RELEASE_DIR}}/{output}', build,
                                  f"{cloud} 构建脚本未产出 {output}")
                    self.assertIn(f'${{RELEASE_DIR}}/{output}/index.html', deploy,
                                  f"{cloud} 部署预检未校验 {output}")

    def test_cloud_gateway_templates_follow_unified_paths(self):
        for cloud in ("aliyun", "tencent"):
            for conf in ("server/nginx-gmall.conf", "server/nginx-gmall-bootstrap.conf",
                         "server/nginx-shop.conf", "server/nginx-shop-bootstrap.conf"):
                path = DEPLOY / cloud / conf
                if not path.exists():
                    continue
                text = read(f"{cloud}/{conf}")
                with self.subTest(conf=f"{cloud}/{conf}"):
                    self.assertIn("www/storefront", text)
                    self.assertIn("/api/v1/auth/health", text)
                    self.assertIn("/api/v1/auth/ready", text)
                    self.assertNotIn("return 302 /m/", text)
                    self.assertNotIn("www/mobile", text)
                    self.assertNotIn("www-mobile", text)
                    self.assertIn("location = /m { return 404; }", text)

    def test_status_scripts_probe_redis_write_protection_read_only(self):
        for cloud in ("aliyun", "tencent"):
            script = read(f"{cloud}/server/06-status.sh")
            with self.subTest(cloud=cloud):
                self.assertIn("config get stop-writes-on-bgsave-error", script)
                self.assertNotIn("config set stop-writes-on-bgsave-error", script)
                self.assertIn("REDISCLI_AUTH", script)

    def test_aliyun_entry_uses_source_packer_and_current_build(self):
        script = read("aliyun/deploy.sh")
        self.assertIn("package-source.sh", script)
        self.assertIn("03-build.sh", script)
        self.assertIn("04-deploy.sh", script)


if __name__ == "__main__":
    unittest.main()
