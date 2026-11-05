# 文件：test_nginx_gateway.py
# 用途：用真实Nginx、临时静态产物及本地假后端验证D35网关和D37探针透传，并锁定部署脚本探针契约。
# T36/R06：另验证模板覆盖而非追加 X-Forwarded-For，以及两云登录限流 location 使用真实路径 /api/v1/auth/login。
# 维护注意：Linux/WSL运行，随机回环端口且不改/etc或系统服务；缺Nginx跳过不算通过。
# 假页面与后端不能证明真实Vue、Spring Boot、数据库/Redis或TLS已验收；只终止本用例创建的进程。
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest


NGINX = shutil.which("nginx")
TEMPLATE = Path(__file__).resolve().parents[1] / "nginx/nginx.prod.conf"


class BackendFixture(BaseHTTPRequestHandler):
    def do_GET(self):
        status = 401 if self.path.startswith("/api/v1/protected") else 200
        if self.path == "/api/v1/auth/ready" and not getattr(self.server, "ready", True):
            status = 503
        body = json.dumps({"code": status, "data": {"status": "UP" if status == 200 else "DOWN", "uri": self.path,
                                                   "forwardedProto": self.headers.get("X-Forwarded-Proto"),
                                                   "forwardedFor": self.headers.get("X-Forwarded-For")}}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        if self.path == "/api/v1/auth/ready":
            self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


@unittest.skipUnless(os.name == "posix" and NGINX, "需要Linux/WSL及真实Nginx；跳过不是通过")
class NginxGatewayTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="shop gateway test ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name, body in {
            "www/storefront/index.html": "STORE-FIXTURE",
            "www/storefront/assets/main.js": "STORE-JS",
            "www/admin/index.html": "ADMIN-FIXTURE",
            "www/admin/assets/main.js": "ADMIN-JS",
            "uploads/example.txt": "UPLOAD-FIXTURE",
        }.items():
            file = self.root / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(body, encoding="utf-8")
        (self.root / "logs").mkdir()
        self.backend = ThreadingHTTPServer(("127.0.0.1", 0), BackendFixture)
        self.thread = threading.Thread(target=self.backend.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_backend)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            self.port = listener.getsockname()[1]
        gateway = TEMPLATE.read_text(encoding="utf-8").replace("/opt/shop-b2b2c", str(self.root))
        gateway = gateway.replace("listen 80;", f"listen 127.0.0.1:{self.port};")
        gateway = gateway.replace("127.0.0.1:8080", f"127.0.0.1:{self.backend.server_port}")
        (self.root / "gateway.conf").write_text(gateway, encoding="utf-8")
        config = self.root / "nginx.conf"
        config.write_text(f'''
# 隔离测试主配置：所有临时路径与pid均属于当前用例，无系统服务操作。
master_process off;
pid "{self.root}/nginx.pid";
error_log "{self.root}/error.log" info;
events {{ worker_connections 64; }}
http {{
    access_log off;
    types {{ text/html html; application/javascript js; text/plain txt; }}
    client_body_temp_path "{self.root}/client_temp";
    proxy_temp_path "{self.root}/proxy_temp";
    fastcgi_temp_path "{self.root}/fastcgi_temp";
    uwsgi_temp_path "{self.root}/uwsgi_temp";
    scgi_temp_path "{self.root}/scgi_temp";
    include "{self.root}/gateway.conf";
}}
''', encoding="utf-8")
        args = [NGINX, "-p", str(self.root) + "/", "-c", str(config)]
        syntax = subprocess.run(args + ["-t"], capture_output=True, text=True, timeout=10)
        self.assertEqual(0, syntax.returncode, syntax.stdout + syntax.stderr)
        self.process = subprocess.Popen(args + ["-g", "daemon off;"], stdout=subprocess.DEVNULL,
                                        stderr=subprocess.PIPE, text=True)
        self.addCleanup(self.stop_nginx)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                self.fail("隔离Nginx启动失败: " + self.process.communicate()[1])
            try:
                if self.request("/")[0] == 200:
                    return
            except OSError:
                pass
            time.sleep(0.05)
        self.fail("隔离Nginx启动超时")

    def stop_backend(self):
        self.backend.shutdown()
        self.backend.server_close()
        self.thread.join(timeout=3)

    def stop_nginx(self):
        if self.process.poll() is None:
            self.process.terminate()
        try:
            self.process.communicate(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.communicate(timeout=3)

    def request(self, path, user_agent="fixture-client", extra_headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        headers = {"User-Agent": user_agent, **(extra_headers or {})}
        try:
            connection.request("GET", path, headers=headers)
            response = connection.getresponse()
            return response.status, dict((key.lower(), value) for key, value in response.getheaders()), response.read().decode()
        finally:
            connection.close()

    def test_all_devices_and_storefront_history_use_storefront(self):
        for agent in ["Desktop", "iPhone", "curl/8", "python-requests/2"]:
            with self.subTest(agent=agent):
                self.assertEqual("STORE-FIXTURE", self.request("/", agent)[2])
        for path in ["/store/7", "/product/9", "/peer-pay/token", "/quick-query"]:
            self.assertEqual((200, "STORE-FIXTURE"), self.request(path)[::2])

    def test_admin_redirect_history_and_distinct_assets(self):
        status, headers, _ = self.request("/console")
        self.assertEqual(308, status)
        self.assertTrue(headers["location"].endswith("/console/"))
        for path in ["/console/", "/console/login", "/console/merchant/products"]:
            self.assertEqual((200, "ADMIN-FIXTURE"), self.request(path)[::2])
        self.assertEqual("ADMIN-JS", self.request("/console/assets/main.js")[2])
        self.assertEqual("STORE-JS", self.request("/assets/main.js")[2])

    def test_missing_assets_are_404_and_never_spa_html(self):
        for path in ["/assets/missing.js", "/console/assets/missing.js"]:
            with self.subTest(path=path):
                status, _, body = self.request(path)
                self.assertEqual(404, status)
                self.assertNotIn("STORE-FIXTURE", body)
                self.assertNotIn("ADMIN-FIXTURE", body)

    def test_api_preserves_path_query_auth_failure_and_machine_clients(self):
        status, _, body = self.request("/api/v1/public/products?merchantId=7", "curl/8")
        self.assertEqual(200, status)
        data = json.loads(body)["data"]
        self.assertEqual("/api/v1/public/products?merchantId=7", data["uri"])
        self.assertEqual("http", data["forwardedProto"])
        self.assertEqual(401, self.request("/api/v1/protected")[0])

    def test_forged_forwarded_for_is_overwritten_by_gateway(self):
        """T36：外部伪造 X-Forwarded-For 不得透传，网关必须用直连地址覆盖。"""
        status, _, body = self.request(
            "/api/v1/public/products", extra_headers={"X-Forwarded-For": "203.0.113.5"})
        self.assertEqual(200, status)
        self.assertEqual("127.0.0.1", json.loads(body)["data"]["forwardedFor"])

    def test_health_uses_application_endpoint_instead_of_static_success(self):
        status, _, body = self.request("/healthz", "curl/8")
        self.assertEqual(200, status)
        self.assertEqual("/api/v1/auth/health", json.loads(body)["data"]["uri"])
        self.stop_backend()
        self.assertEqual(502, self.request("/healthz")[0])

    def test_readiness_has_its_own_endpoint_and_preserves_http_503(self):
        status, headers, body = self.request("/readyz")
        self.assertEqual(200, status)
        self.assertEqual("/api/v1/auth/ready", json.loads(body)["data"]["uri"])
        self.assertIn("no-store", headers.get("cache-control", ""))
        self.backend.ready = False
        self.assertEqual(503, self.request("/readyz")[0])
        self.assertEqual(200, self.request("/healthz")[0])

    def test_unsupported_legacy_entries_do_not_fake_success_or_register(self):
        for path in ["/m", "/m/product/1", "/invite", "/invite/register", "/actuator/health", "/admin", "/admin/login"]:
            with self.subTest(path=path):
                self.assertEqual(404, self.request(path)[0])

    def test_upload_mapping_and_security_and_cache_headers(self):
        self.assertEqual("UPLOAD-FIXTURE", self.request("/uploads/example.txt")[2])
        self.assertEqual(404, self.request("/uploads/missing.txt")[0])
        _, headers, _ = self.request("/assets/main.js")
        self.assertIn("max-age", headers.get("cache-control", ""))
        self.assertEqual("application/javascript", headers.get("content-type"))
        self.assertEqual("nosniff", headers.get("x-content-type-options"))


class ProbeScriptContractTest(unittest.TestCase):
    def test_infrastructure_never_disables_or_rewrites_redis_write_protection(self):
        script = (TEMPLATE.parents[1] / "tencent/server/02-init-infra.sh").read_text(encoding="utf-8")
        executable = "\n".join(line for line in script.splitlines() if not line.lstrip().startswith("#"))
        self.assertIsNone(re.search(r"config\s+set\s+stop-writes-on-bgsave-error\s+no", executable, re.I))
        self.assertIsNone(re.search(r"config\s+rewrite", executable, re.I))

    def test_gateway_templates_override_forwarded_for(self):
        for rel in ["nginx/nginx.prod.conf", "aliyun/server/nginx-gmall.conf",
                    "aliyun/server/nginx-gmall-bootstrap.conf",
                    "tencent/server/nginx-shop.conf", "tencent/server/nginx-shop-bootstrap.conf"]:
            with self.subTest(rel=rel):
                conf = (TEMPLATE.parents[1] / rel).read_text(encoding="utf-8")
                self.assertNotIn("$proxy_add_x_forwarded_for", conf, "不得追加客户端可伪造的 XFF")
                # 各模板对齐列宽不同，这里只锁定“用直连地址覆盖”的语义，不锁定空格数量。
                self.assertRegex(conf, r"X-Forwarded-For\s+\$remote_addr")

    def test_cloud_login_rate_limit_uses_real_path(self):
        for rel in ["aliyun/server/nginx-gmall.conf", "aliyun/server/nginx-gmall-bootstrap.conf",
                    "tencent/server/nginx-shop.conf", "tencent/server/nginx-shop-bootstrap.conf"]:
            with self.subTest(rel=rel):
                conf = (TEMPLATE.parents[1] / rel).read_text(encoding="utf-8")
                self.assertIn("location = /api/v1/auth/login", conf)
                self.assertNotIn("/api/admin/auth/login", conf)

    def test_tencent_deploy_uses_readiness_and_checks_http_failure(self):
        script = (TEMPLATE.parents[1] / "tencent/server/04-deploy.sh").read_text(encoding="utf-8")
        probe = next(line for line in script.splitlines() if "if curl " in line)
        self.assertIn("curl -fsS --max-time 3", probe)
        self.assertIn("/api/v1/auth/ready", probe)
        self.assertNotIn("/api/v1/auth/health", probe)


if __name__ == "__main__":
    unittest.main()
