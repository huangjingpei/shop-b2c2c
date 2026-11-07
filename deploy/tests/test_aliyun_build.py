# 文件：test_aliyun_build.py
# 用途：用隔离临时源码及伪Maven/npm验证阿里云构建入口的发布边界。
# 维护注意：仅在Linux/WSL下运行，不编译业务工程、不联网、不访问真实发布目录或服务。
# 假工具只生成最小占位产物；通过不代表真实JAR、Vite、Nginx或支付链路已验收。
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "aliyun/server/03-build.sh"
PACKER = Path(__file__).resolve().parents[1] / "aliyun/package-source.sh"


@unittest.skipUnless(os.name == "posix" and shutil.which("bash"), "需要Linux/WSL与Bash")
class AliyunBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="shop build test ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "src"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ["shop-b2b2c-backend/pom.xml", "shop-b2b2c-backend/src/main/resources/db/schema.sql",
                     "shop-admin-vue/package.json", "shop-admin-vue/package-lock.json",
                     "shop-storefront-vue/package.json", "shop-storefront-vue/package-lock.json"]:
            file = self.source / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text("fixture\n", encoding="utf-8")
        self.old = self.root / "releases/old"
        self.old.mkdir(parents=True)
        (self.old / "app.jar").write_text("old", encoding="utf-8")
        self.latest = self.root / "releases/latest"
        self.latest.symlink_to(self.old, target_is_directory=True)
        self.env = os.environ.copy()
        self.env.update(GMALL_ROOT=str(self.root), PATH=f"{self.bin}:{self.env['PATH']}",
                        BUILD_TEST_CASE="ok", BUILD_TEST_LOG=str(self.root / "calls"))
        for key in ["BASH_ENV", "ENV", "ADMIN_PORTAL_PATH"]:
            self.env.pop(key, None)
        self.tool("node", "exit 0\n")
        self.tool("mvn", r'''
# 伪后端工具：不执行Maven，只按测试场景生成占位文件。
printf 'mvn %s\n' "$*" >> "$BUILD_TEST_LOG"
[[ "$BUILD_TEST_CASE" != maven-fail ]] || exit 7
mkdir -p target
[[ "$BUILD_TEST_CASE" != no-jar ]] || exit 0
printf 'jar' > target/shop-b2b2c-backend-1.0.jar
printf 'source' > target/shop-b2b2c-backend-1.0-sources.jar
printf 'docs' > target/shop-b2b2c-backend-1.0-javadoc.jar
[[ "$BUILD_TEST_CASE" != empty-jar ]] || : > target/shop-b2b2c-backend-1.0.jar
if [[ "$BUILD_TEST_CASE" == multi-jar ]]; then printf 'other' > target/shop-b2b2c-backend-2.0.jar; fi
''')
        self.tool("npm", r'''
# 伪前端工具：记录锁文件安装与独立base，不下载依赖。
printf 'npm %s base=%s project=%s\n' "$*" "${VITE_APP_BASE-unset}" "${PWD##*/}" >> "$BUILD_TEST_LOG"
if [[ "$1" == ci ]]; then
  [[ "$BUILD_TEST_CASE" != ci-fail ]] || exit 8
  exit 0
fi
[[ "$1 $2" == 'run build' ]] || exit 9
[[ "$BUILD_TEST_CASE" != frontend-fail ]] || exit 10
if [[ "$BUILD_TEST_CASE" == storefront-fail && "${PWD##*/}" == shop-storefront-vue ]]; then exit 11; fi
output=''
while (( $# )); do
  if [[ "$1" == --outDir ]]; then output=$2; shift; fi
  shift
done
[[ -n "$output" ]] || exit 12
mkdir -p "$output/assets"
[[ "$BUILD_TEST_CASE" != no-index ]] || exit 0
printf '<html>fixture</html>' > "$output/index.html"
[[ "$BUILD_TEST_CASE" != empty-index ]] || : > "$output/index.html"
[[ "$BUILD_TEST_CASE" != no-assets ]] || exit 0
printf 'fixture' > "$output/assets/main.js"
[[ "$BUILD_TEST_CASE" != empty-assets ]] || : > "$output/assets/main.js"
''')

    def tool(self, name, content):
        file = self.bin / name
        file.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + content, encoding="utf-8")
        file.chmod(0o755)

    def run_build(self, case="ok"):
        self.env["BUILD_TEST_CASE"] = case
        return subprocess.run(["bash", str(SCRIPT)], cwd=self.root, env=self.env,
                              capture_output=True, text=True, timeout=30)

    def assert_old_release(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(self.old, self.latest.resolve())
        self.assertEqual("old", (self.old / "app.jar").read_text(encoding="utf-8"))

    def test_complete_release_and_both_bases(self):
        result = self.run_build()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        release = self.latest.resolve()
        self.assertNotEqual(self.old, release)
        for name in ["app.jar", "schema.sql", "BUILD_ID", "www-admin/index.html",
                     "www-admin/assets/main.js", "www-storefront/index.html", "www-storefront/assets/main.js"]:
            self.assertGreater((release / name).stat().st_size, 0)
        calls = (self.root / "calls").read_text(encoding="utf-8")
        self.assertEqual(2, calls.count("npm ci --no-audit --no-fund"))
        self.assertIn("base=/console/ project=shop-admin-vue", calls)
        self.assertIn("base=/ project=shop-storefront-vue", calls)
        self.assertNotIn("build:h5", calls)
        self.assertFalse((release / "BUILD_FAILED").exists())

    def test_failure_paths_never_replace_previous_release(self):
        for case in ["maven-fail", "no-jar", "empty-jar", "multi-jar", "ci-fail",
                     "frontend-fail", "storefront-fail", "no-index", "empty-index", "no-assets", "empty-assets"]:
            with self.subTest(case=case):
                # 各场景隔离target，避免前一个伪工具的占位产物影响缺失断言。
                target = self.source / "shop-b2b2c-backend/target"
                if target.exists():
                    shutil.rmtree(target)
                self.assert_old_release(self.run_build(case))
        self.assertEqual(11, len(list((self.root / "releases").glob("*/BUILD_FAILED"))))

    def test_first_release_can_publish_without_previous_latest(self):
        self.latest.unlink()
        result = self.run_build()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(self.latest.is_symlink())
        self.assertTrue((self.latest / "BUILD_ID").is_file())

    def test_missing_required_source_or_lock_fails_before_any_tool(self):
        for name in ["shop-admin-vue/package.json", "shop-storefront-vue/package-lock.json",
                     "shop-b2b2c-backend/src/main/resources/db/schema.sql"]:
            file = self.source / name
            original = file.read_bytes()
            file.unlink()
            with self.subTest(name=name):
                self.assert_old_release(self.run_build())
                self.assertFalse((self.root / "calls").exists())
            file.write_bytes(original)

    def test_custom_base_is_normalized(self):
        self.env["ADMIN_PORTAL_PATH"] = "/ops/console/"
        result = self.run_build()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("base=/ops/console/ project=shop-admin-vue", (self.root / "calls").read_text(encoding="utf-8"))

    def test_conflicting_or_invalid_base_is_rejected_before_build(self):
        for base in ["/", "relative", "/m", "/m/admin", "/api", "/api/admin", "/bad path", "/../console"]:
            with self.subTest(base=base):
                self.env["ADMIN_PORTAL_PATH"] = base
                self.assert_old_release(self.run_build())
                self.assertFalse((self.root / "calls").exists())

    def test_regular_latest_directory_is_never_overwritten(self):
        self.latest.unlink()
        self.latest.mkdir()
        marker = self.latest / "keep"
        marker.write_text("keep", encoding="utf-8")
        result = self.run_build()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("keep", marker.read_text(encoding="utf-8"))
        self.assertFalse((self.root / "calls").exists())

    def test_relative_root_and_repeated_builds_get_unique_complete_releases(self):
        self.env["GMALL_ROOT"] = "."
        first = self.run_build()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        previous = self.latest.resolve()
        second = self.run_build()
        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        self.assertNotEqual(previous, self.latest.resolve())
        self.assertTrue((previous / "BUILD_ID").exists())

    def test_source_archive_contains_current_projects_and_locks_without_generated_files(self):
        for name in ["shop-admin-vue/node_modules/dependency.js", "shop-storefront-vue/dist/index.html",
                     "shop-b2b2c-backend/target/old.jar", "shop-b2b2c-backend/debug.log"]:
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("excluded", encoding="utf-8")
        archive = self.root / "source archive.tar.gz"
        result = subprocess.run(["bash", str(PACKER), str(self.source), str(archive)],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        with tarfile.open(archive) as package:
            names = package.getnames()
        for name in ["shop-b2b2c-backend/pom.xml", "shop-admin-vue/package-lock.json", "shop-storefront-vue/package-lock.json"]:
            self.assertIn(name, names)
        self.assertFalse(any("node_modules" in name or "/dist" in name or "/target" in name or name.endswith(".log") for name in names))

    def test_incomplete_source_cannot_be_packaged(self):
        (self.source / "shop-storefront-vue/package-lock.json").unlink()
        archive = self.root / "incomplete.tar.gz"
        result = subprocess.run(["bash", str(PACKER), str(self.source), str(archive)],
                                capture_output=True, text=True, timeout=30)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(archive.exists())


if __name__ == "__main__":
    unittest.main()
