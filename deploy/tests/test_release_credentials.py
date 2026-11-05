# 文件：test_release_credentials.py
# 用途：生产发布脚本凭据契约用例，断言.env模板包含受控管理员键、部署脚本拒绝演示默认口令、结构基线不含演示账号。
# 维护注意：只读取仓库文本，不执行部署、不连接MySQL/Nginx；真实发布与管理员首登仍由环境负责人在Linux验证。
import unittest
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1] / "aliyun" / "server"
BACKEND_RESOURCES = Path(__file__).resolve().parents[2] / "shop-b2b2c-backend" / "src" / "main" / "resources"


class AliyunBootstrapCredentialContractTest(unittest.TestCase):
    def test_env_template_has_bootstrap_admin_keys(self):
        script = (SERVER_DIR / "02-init-infra.sh").read_text(encoding="utf-8")
        self.assertIn("BOOTSTRAP_ADMIN_USERNAME=", script)
        self.assertIn("BOOTSTRAP_ADMIN_PASSWORD=", script)

    def test_deploy_script_rejects_demo_default_admin_password(self):
        script = (SERVER_DIR / "04-deploy.sh").read_text(encoding="utf-8")
        self.assertIn('"${BOOTSTRAP_ADMIN_PASSWORD}" == "admin123"', script)
        self.assertIn("die", script)

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
