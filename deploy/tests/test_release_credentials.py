# 文件：test_release_credentials.py
# 用途：生产发布脚本凭据契约用例，断言两云 .env 模板包含必填键与前台地址、部署脚本缺失即终止、
#       财务双人复核开关缺失/非法值被拒绝（G36）、仓库脚本不再回退真实Redis/数据库/管理员默认口令，
#       且结构基线不含演示账号。
# 维护注意：只读取仓库文本，不执行部署、不连接MySQL/Nginx；真实发布与管理员首登仍由环境负责人在Linux验证。
import unittest
from pathlib import Path

DEPLOY_DIR = Path(__file__).resolve().parents[1]
BACKEND_RESOURCES = Path(__file__).resolve().parents[2] / "shop-b2b2c-backend" / "src" / "main" / "resources"

CLOUDS = {
    "aliyun": {
        "server_dir": DEPLOY_DIR / "aliyun" / "server",
        "server_name_key": "GMALL_SERVER_NAME",
        "root_var": "GMALL_ROOT",
    },
    "tencent": {
        "server_dir": DEPLOY_DIR / "tencent" / "server",
        "server_name_key": "SHOP_SERVER_NAME",
        "root_var": "SHOP_ROOT",
    },
}

# 这些值曾作为脚本内真实/占位默认口令存在，必须彻底消失（不能作为回退分支留在仓库里）。
FORBIDDEN_DEFAULT_SECRETS = ["3wTYvNMHNn89pZ3XmTZcQluG", "YourStrongRedisPassword"]


class CloudEnvTemplateContractTest(unittest.TestCase):
    def test_env_templates_require_storefront_url_and_unicode(self):
        for cloud, cfg in CLOUDS.items():
            with self.subTest(cloud=cloud):
                script = (cfg["server_dir"] / "02-init-infra.sh").read_text(encoding="utf-8")
                self.assertIn("APP_FRONTEND_STOREFRONT_URL=https://${%s}/m/" % cfg["server_name_key"], script)
                self.assertIn("useUnicode=true", script)
                self.assertNotIn("useUNICODE", script)

    def test_env_templates_require_bootstrap_admin_keys(self):
        for cloud, cfg in CLOUDS.items():
            with self.subTest(cloud=cloud):
                script = (cfg["server_dir"] / "02-init-infra.sh").read_text(encoding="utf-8")
                self.assertIn("BOOTSTRAP_ADMIN_USERNAME=${BOOTSTRAP_ADMIN_USERNAME}", script)
                self.assertIn("BOOTSTRAP_ADMIN_PASSWORD=${BOOTSTRAP_ADMIN_PASSWORD}", script)
                self.assertIn('"${BOOTSTRAP_ADMIN_PASSWORD}" != "admin123"', script)

    def test_env_templates_abort_when_required_values_missing(self):
        for cloud, cfg in CLOUDS.items():
            with self.subTest(cloud=cloud):
                script = (cfg["server_dir"] / "02-init-infra.sh").read_text(encoding="utf-8")
                self.assertIn("for key in", script)
                self.assertIn("REDIS_PASS", script)
                self.assertIn("die", script)
                # 不得用 ${REDIS_PASS:-...} 之类的默认值
                self.assertNotIn("REDIS_PASS:-", script)

    def test_env_templates_have_no_default_secret_values(self):
        for cloud, cfg in CLOUDS.items():
            with self.subTest(cloud=cloud):
                script = (cfg["server_dir"] / "02-init-infra.sh").read_text(encoding="utf-8")
                for secret in FORBIDDEN_DEFAULT_SECRETS:
                    self.assertNotIn(secret, script)

    def test_spring_data_redis_uses_same_db_number(self):
        for cloud, cfg in CLOUDS.items():
            with self.subTest(cloud=cloud):
                script = (cfg["server_dir"] / "02-init-infra.sh").read_text(encoding="utf-8")
                self.assertIn("SPRING_DATA_REDIS_DATABASE=${REDIS_DB_VALUE}", script)
                self.assertIn("REDIS_DB=${REDIS_DB_VALUE}", script)

    def test_env_examples_are_placeholders_not_real_secrets(self):
        for cloud in CLOUDS:
            with self.subTest(cloud=cloud):
                example = (DEPLOY_DIR / cloud / "env.example.sh").read_text(encoding="utf-8")
                for secret in FORBIDDEN_DEFAULT_SECRETS:
                    self.assertNotIn(secret, example)
                self.assertIn('BOOTSTRAP_ADMIN_PASSWORD=""', example)
                self.assertIn('REDIS_PASS=""', example)


class CloudDeployCredentialContractTest(unittest.TestCase):
    def test_deploy_script_requires_complete_key_set(self):
        for cloud, cfg in CLOUDS.items():
            with self.subTest(cloud=cloud):
                script = (cfg["server_dir"] / "04-deploy.sh").read_text(encoding="utf-8")
                self.assertIn("REQUIRED_ENV_KEYS=(", script)
                for key in [
                    "DB_NAME", "DB_USER", "DB_PASS",
                    "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
                    "REDIS_PASS", "APP_FRONTEND_STOREFRONT_URL",
                    "BOOTSTRAP_ADMIN_USERNAME", "BOOTSTRAP_ADMIN_PASSWORD",
                    "FINANCE_DUAL_APPROVAL_REQUIRED",
                    cfg["server_name_key"],
                ]:
                    self.assertIn(key, script)
                self.assertIn(".env 缺少 ${key}", script)
                self.assertIn("for key in", script)

    def test_deploy_script_requires_finance_dual_approval_key(self):
        """缺键即中止：该键必须在必填集合内，不能靠 application.yml 的 false 默认静默放行。"""
        for cloud, cfg in CLOUDS.items():
            with self.subTest(cloud=cloud):
                script = (cfg["server_dir"] / "04-deploy.sh").read_text(encoding="utf-8")
                required_block = script.split("REQUIRED_ENV_KEYS=(", 1)[1].split(")", 1)[0]
                self.assertIn("FINANCE_DUAL_APPROVAL_REQUIRED", required_block)
                self.assertIn('[[ -n "${!key:-}" ]] || die ".env 缺少 ${key}', script)

    def test_deploy_script_rejects_non_boolean_finance_switch(self):
        """非法值拒绝：只接受 true/false，yes/1/空值等拼写不得被当作关闭或开启。"""
        for cloud, cfg in CLOUDS.items():
            with self.subTest(cloud=cloud):
                script = (cfg["server_dir"] / "04-deploy.sh").read_text(encoding="utf-8")
                self.assertIn('case "${FINANCE_DUAL_APPROVAL_REQUIRED}" in', script)
                self.assertIn("true|false)", script)
                self.assertIn("只接受 true 或 false", script)
                self.assertNotIn("FINANCE_DUAL_APPROVAL_REQUIRED:-", script)

    def test_env_examples_enable_finance_dual_approval(self):
        for cloud in CLOUDS:
            with self.subTest(cloud=cloud):
                example = (DEPLOY_DIR / cloud / "env.example.sh").read_text(encoding="utf-8")
                self.assertIn('FINANCE_DUAL_APPROVAL_REQUIRED="true"', example)

    def test_deploy_script_has_no_credential_default_fallbacks(self):
        for cloud, cfg in CLOUDS.items():
            with self.subTest(cloud=cloud):
                script = (cfg["server_dir"] / "04-deploy.sh").read_text(encoding="utf-8")
                self.assertNotIn('DB_USER="${DB_USER:-', script)
                self.assertNotIn('DB_NAME="${DB_NAME:-', script)
                self.assertNotIn('DB_PASS:-', script)
                self.assertNotIn('SERVER_NAME="${%s:-' % cfg["server_name_key"], script)

    def test_deploy_script_rejects_demo_default_admin_password(self):
        for cloud, cfg in CLOUDS.items():
            with self.subTest(cloud=cloud):
                script = (cfg["server_dir"] / "04-deploy.sh").read_text(encoding="utf-8")
                self.assertIn('"${BOOTSTRAP_ADMIN_PASSWORD}" == "admin123"', script)
                self.assertIn("die", script)

    def test_top_level_deploy_requires_secrets_and_hides_demo_accounts(self):
        for cloud in CLOUDS:
            with self.subTest(cloud=cloud):
                script = (DEPLOY_DIR / cloud / "deploy.sh").read_text(encoding="utf-8")
                self.assertIn('REDIS_PASS="${REDIS_PASS:?', script)
                for secret in FORBIDDEN_DEFAULT_SECRETS:
                    self.assertNotIn(secret, script)
                self.assertNotIn("Admin@123456", script)
                self.assertNotIn("superadmin", script)
                self.assertNotIn("merchant1 / 123456", script)


class BackendBaselineContractTest(unittest.TestCase):
    def test_backend_has_no_default_datasource_account(self):
        config = (BACKEND_RESOURCES / "application.yml").read_text(encoding="utf-8")
        self.assertIn("username: ${SPRING_DATASOURCE_USERNAME}", config)
        self.assertIn("password: ${SPRING_DATASOURCE_PASSWORD}", config)
        self.assertNotIn("${SPRING_DATASOURCE_USERNAME:root}", config)
        self.assertNotIn("89dfu", config)

    def test_schema_has_no_demo_account_or_plain_card_seed(self):
        schema = (BACKEND_RESOURCES / "db" / "schema.sql").read_text(encoding="utf-8")
        self.assertNotIn("admin123", schema)
        self.assertNotIn("superadmin", schema)
        self.assertNotIn("INSERT INTO `sys_user`", schema)
        self.assertNotIn("INSERT INTO `product_virtual_card`", schema)

    def test_production_profile_never_runs_sql_init(self):
        prod = (BACKEND_RESOURCES / "application-prod.yml").read_text(encoding="utf-8")
        self.assertIn("on-profile: prod", prod)
        self.assertIn("mode: never", prod)


if __name__ == "__main__":
    unittest.main()
