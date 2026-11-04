# 文件：test_backup.py
# 用途：验证 MySQL 备份脚本（backup_mysql.sh）的安全边界、密码注入约束与历史归档轮转。
# 维护注意：仅在 Linux/WSL 下运行，通过 PATH 注入隔离的 fake mysqldump 工具，不触碰生产库与真实数据库。
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/backup_mysql.sh"


@unittest.skipUnless(os.name == "posix" and shutil.which("bash"), "需要Linux/WSL与Bash")
class BackupMysqlTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="shop_backup_test_")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.backup_dir = self.root / "backups"
        self.backup_dir.mkdir(parents=True)
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir(parents=True)

        self.env = os.environ.copy()
        self.env.update(
            PATH=f"{self.bin_dir}:{self.env['PATH']}",
            BACKUP_DIR=str(self.backup_dir),
            DB_NAME="test_db",
            DB_USER="test_user",
            DB_PASS="TestPass123!",
            RETENTION_DAYS="7",
        )

    def make_tool(self, name: str, script_body: str):
        tool_path = self.bin_dir / name
        tool_path.write_text(f"#!/usr/bin/env bash\n{script_body}\n", encoding="utf-8")
        tool_path.chmod(0o755)
        return tool_path

    def run_script(self, env_override=None):
        env = self.env.copy()
        if env_override:
            for k, v in env_override.items():
                if v is None:
                    env.pop(k, None)
                else:
                    env[k] = v
        return subprocess.run(
            ["bash", str(SCRIPT)],
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=30,
        )

    def test_missing_db_pass_aborts(self):
        """TC-R32-01: 未注入 DB_PASS 时必须立即报错中止，拒绝使用默认密码"""
        proc = self.run_script({"DB_PASS": None})
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("DB_PASS 未设置", proc.stderr)
        # 验证未生成任何备份文件
        gz_files = list(self.backup_dir.glob("*.sql.gz"))
        self.assertEqual(len(gz_files), 0)

    def test_empty_db_pass_aborts(self):
        """TC-R32-02: DB_PASS 为空字符串时同样必须拒绝"""
        proc = self.run_script({"DB_PASS": ""})
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("DB_PASS 未设置", proc.stderr)

    def test_mysqldump_failure_aborts_and_cleans_up(self):
        """TC-R32-03: mysqldump 执行失败（如连接超时或权限不足）时必须退出且清理临时归档文件"""
        self.make_tool("mysqldump", "echo 'Error: Access denied' >&2; exit 2")
        proc = self.run_script()
        self.assertNotEqual(proc.returncode, 0)
        # 验证错误被记录
        log_file = self.backup_dir / "backup.log"
        self.assertTrue(log_file.exists())
        self.assertIn("mysqldump 执行失败", log_file.read_text(encoding="utf-8"))
        # 确保无残留的损坏 .sql.gz 文件
        gz_files = list(self.backup_dir.glob("*.sql.gz"))
        self.assertEqual(len(gz_files), 0)

    def test_empty_dump_aborts_and_cleans_up(self):
        """TC-R32-04: mysqldump 输出 0 字节时必须识别为空文件并报错清理"""
        self.make_tool("mysqldump", "exit 0")  # 输出空内容但返回 0
        proc = self.run_script()
        self.assertNotEqual(proc.returncode, 0)
        log_file = self.backup_dir / "backup.log"
        self.assertTrue(log_file.exists())
        self.assertIn("备份文件为空", log_file.read_text(encoding="utf-8"))
        # 确保无残留文件
        gz_files = list(self.backup_dir.glob("*.sql.gz"))
        self.assertEqual(len(gz_files), 0)

    def test_successful_backup_creates_valid_gz(self):
        """TC-R32-05: 正常场景下生成非空 .sql.gz 归档，文件名符合规范"""
        self.make_tool("mysqldump", "echo '-- MySQL Dump Fixture'; echo 'CREATE TABLE t1(id int);'")
        proc = self.run_script()
        self.assertEqual(proc.returncode, 0, f"Script failed: {proc.stderr}")
        log_file = self.backup_dir / "backup.log"
        self.assertTrue(log_file.exists())
        self.assertIn("备份成功", log_file.read_text(encoding="utf-8"))

        gz_files = list(self.backup_dir.glob("shop_test_db_*.sql.gz"))
        self.assertEqual(len(gz_files), 1)
        self.assertGreater(gz_files[0].stat().st_size, 0)

    def test_retention_rotation_cleans_only_old_files(self):
        """TC-R32-06: 备份成功后清理超过 retention 天数的旧备份，保留近期备份"""
        # 创建一个 10 天前的旧备份文件（RETENTION_DAYS=7）
        old_file = self.backup_dir / "shop_test_db_20260101_000000.sql.gz"
        old_file.write_bytes(b"fake old gz content")
        # 修改 mtime 到 10 天前
        past_time = time.time() - (10 * 86400)
        os.utime(str(old_file), (past_time, past_time))

        # 创建一个 2 天前的较新备份文件
        recent_file = self.backup_dir / "shop_test_db_20260109_000000.sql.gz"
        recent_file.write_bytes(b"fake recent gz content")
        recent_time = time.time() - (2 * 86400)
        os.utime(str(recent_file), (recent_time, recent_time))

        self.make_tool("mysqldump", "echo 'CREATE TABLE t2(id int);'")
        proc = self.run_script()
        self.assertEqual(proc.returncode, 0)

        # 验证旧文件已被清理，新文件与本次备份均保留
        self.assertFalse(old_file.exists(), "超过保留期的旧备份应被删除")
        self.assertTrue(recent_file.exists(), "未过期的旧备份应保留")
        gz_files = list(self.backup_dir.glob("shop_test_db_*.sql.gz"))
        self.assertEqual(len(gz_files), 2)  # recent_file + 新备份


if __name__ == "__main__":
    unittest.main()
