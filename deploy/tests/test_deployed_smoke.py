# 文件：test_deployed_smoke.py
# 用途：对已部署环境执行只读 HTTP 烟测，验证根商城、管理端、存活/就绪探针与旧入口下线的统一路径契约。
# 维护注意：
# 1. 仅在设置 SMOKE_BASE_URL（http/https 绝对地址）时执行；未设置显式 skipped，跳过不是通过，也不算部署验收。
# 2. 只发 GET，不登录、不带 Cookie/口令、不执行写请求；不关闭 TLS 证书校验，证书错误应按失败处理。
# 3. 断言 200/404/JSON 契约与未被重定向；不验证支付渠道、数据库写入、真实 Redis 故障恢复或前端生产构建。
# 4. 目标站点数据未知，因此不依赖任何业务订单/商户ID；探针失败只说明当前网络可达性下的路径异常，需结合 06-status.sh 人工定位。
import json
import os
import unittest
import urllib.error
import urllib.request
from urllib.parse import urlsplit

BASE_URL = os.environ.get("SMOKE_BASE_URL", "").strip().rstrip("/")
TIMEOUT_SECONDS = 10


def get(path):
    """返回 (状态码, 响应头, 解码后的正文, 最终URL)；HTTP 错误码不抛异常，便于断言 404/503。"""
    request = urllib.request.Request(BASE_URL + path, method="GET", headers={"User-Agent": "deploy-smoke/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return response.status, response.headers, response.read().decode("utf-8", "replace"), response.geturl()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read().decode("utf-8", "replace"), error.geturl()


@unittest.skipUnless(BASE_URL, "未设置 SMOKE_BASE_URL，跳过已部署烟测；跳过不是通过")
class DeployedSmokeTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        parts = urlsplit(BASE_URL)
        if parts.scheme not in ("http", "https") or not parts.netloc:
            raise AssertionError(f"SMOKE_BASE_URL 必须是 http/https 绝对地址，当前为: {BASE_URL}")

    def assert_page(self, path):
        status, headers, body, final_url = get(path)
        self.assertEqual(200, status, f"{path} 期望 200，实际 {status}: {body[:120]}")
        self.assertEqual(BASE_URL + path, final_url, f"{path} 不应被重定向")
        self.assertIn("text/html", headers.get("Content-Type", ""), f"{path} 不是 HTML 页面")
        return body

    def assert_json_code(self, path):
        status, headers, body, final_url = get(path)
        self.assertEqual(200, status, f"{path} 期望 200，实际 {status}: {body[:120]}")
        self.assertIn("application/json", headers.get("Content-Type", ""), f"{path} 不是 JSON")
        return json.loads(body)

    def test_storefront_root_serves_html(self):
        body = self.assert_page("/")
        self.assertTrue(body.strip(), "商城首页内容为空")

    def test_admin_portal_serves_html(self):
        body = self.assert_page("/console/")
        self.assertTrue(body.strip(), "管理端首页内容为空")

    def test_liveness_and_readiness_report_up(self):
        self.assertEqual(200, self.assert_json_code("/healthz").get("code"))
        data = self.assert_json_code("/readyz")["data"]
        self.assertEqual("UP", data.get("status"), f"就绪状态非 UP: {data}")
        self.assertEqual("UP", data.get("database"), f"数据库检查非 UP: {data}")
        self.assertEqual("UP", data.get("redis"), f"Redis 检查非 UP: {data}")

    def test_legacy_entries_are_not_reachable(self):
        for path in ("/m", "/m/product/1", "/invite", "/invite/register", "/actuator/health", "/admin", "/admin/login"):
            with self.subTest(path=path):
                status, _, _, _ = get(path)
                self.assertEqual(404, status, f"旧入口 {path} 仍可访问，返回 {status}")

    def test_missing_asset_returns_404_not_spa_html(self):
        for path in ("/assets/__smoke_missing__.js", "/console/assets/__smoke_missing__.js"):
            with self.subTest(path=path):
                status, _, body, _ = get(path)
                self.assertEqual(404, status, f"{path} 缺失资源应为 404，实际 {status}")
                # Nginx 默认 404 页本身是 HTML，不能按内容类型判断；这里只要求不回退成携带该资源的页面。
                self.assertNotIn("__smoke_missing__", body)


if __name__ == "__main__":
    unittest.main()
