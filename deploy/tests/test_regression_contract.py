# 文件：test_regression_contract.py
# 用途：静态锁定 D39 可重复回归入口：CI 与本地脚本覆盖同一三套隔离用例、前端提供 npm test 入口、已部署烟测必须显式给出目标地址。
# 维护注意：只读取仓库文本与 package.json，不执行 CI、不访问网络、不构建前端；断言通过不代表 GitHub 已运行或真实环境已验收。
# 新增/改名测试套件时须同步更新此契约，避免 CI、regression.sh 与文档三处入口漂移。
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


class RegressionEntrypointContractTest(unittest.TestCase):
    def test_ci_runs_backend_shared_and_deploy_suites(self):
        workflow = read(".github/workflows/ci.yml")
        self.assertIn("mvn -B -f shop-b2b2c-backend/pom.xml test", workflow)
        self.assertIn("npm --prefix shop-storefront-vue test", workflow)
        self.assertIn("python3 -m unittest discover -s deploy/tests", workflow)
        self.assertIn("contents: read", workflow)

    def test_ci_never_builds_deploys_or_uses_secrets(self):
        workflow = read(".github/workflows/ci.yml")
        self.assertNotIn("npm run build", workflow)
        self.assertNotIn("ssh ", workflow)
        self.assertNotIn("secrets.", workflow)

    def test_regression_script_matches_ci_entries(self):
        script = read("scripts/regression.sh")
        self.assertIn("mvn -B -f shop-b2b2c-backend/pom.xml test", script)
        self.assertIn("npm --prefix shop-storefront-vue ci", script)
        self.assertIn("node --test shared/*.test.mjs", script)
        self.assertIn("python3 -m unittest discover -s deploy/tests", script)
        # 离线入口不得顺带构建或发布。
        self.assertNotIn("npm run build", script)

    def test_frontend_packages_expose_shared_test_entry(self):
        for project in ("shop-storefront-vue", "shop-admin-vue"):
            with self.subTest(project=project):
                package = json.loads(read(f"{project}/package.json"))
                self.assertEqual("node --test ../shared/", package["scripts"].get("test"),
                                 f"{project} 缺少可重跑的前端 test 入口")
                self.assertIn("build", package["scripts"])

    def test_deployed_smoke_is_read_only_and_needs_explicit_target(self):
        smoke = read("deploy/tests/test_deployed_smoke.py")
        self.assertIn('os.environ.get("SMOKE_BASE_URL"', smoke)
        self.assertIn("skipUnless(BASE_URL", smoke)
        self.assertIn('method="GET"', smoke)
        self.assertNotIn('method="POST"', smoke)
        self.assertNotIn('"Cookie"', smoke)


if __name__ == "__main__":
    unittest.main()
