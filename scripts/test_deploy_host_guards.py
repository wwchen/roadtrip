#!/usr/bin/env python3

import os
import signal
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / "scripts" / "deploy.sh"
PROCESS_EXIT_TIMEOUT_SECONDS = 5
PROCESS_START_TIMEOUT_SECONDS = 5


def pid_is_running(pid: int) -> bool:
    result = subprocess.run(
        ["ps", "-o", "stat=", "-p", str(pid)],
        capture_output=True,
        text=True,
        check=False,
    )
    return result.returncode == 0 and not result.stdout.strip().startswith("Z")


def wait_for_path(path: Path) -> None:
    deadline = time.monotonic() + PROCESS_START_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        if path.exists():
            return
        time.sleep(0.05)
    raise AssertionError(f"timed out waiting for {path}")


def wait_for_exit(pid: int) -> None:
    deadline = time.monotonic() + PROCESS_EXIT_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        if not pid_is_running(pid):
            return
        time.sleep(0.05)
    raise AssertionError(f"PID {pid} is still running")


class DeployHostGuardTest(unittest.TestCase):
    def deploy_env(self, root: Path) -> dict[str, str]:
        return {
            **os.environ,
            "HOME": str(root / "home"),
            "SANDBOX_ENV_FILE": str(root / "missing-sandbox.env"),
            "SANDBOX_STATE_DIR": str(root / "state"),
            "SANDBOX_CADDY_DIR": str(root / "caddy"),
            "ROADTRIP_HOST_DOCKER_LOCK_WAIT_SECONDS": "2",
        }

    def test_sandbox_down_clears_the_entire_stale_process_tree(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            stale_script = root / "deploy.sh"
            stale_script.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$$" > "$PID_DIR/root.pid"
sh -c 'printf "%s\\n" "$$" > "$PID_DIR/child.pid"; sleep 300 & printf "%s\\n" "$!" > "$PID_DIR/grandchild.pid"; wait' &
wait
"""
            )
            stale_script.chmod(0o755)

            env = self.deploy_env(root)
            env["PID_DIR"] = str(root)
            env["ROADTRIP_STALE_DEPLOY_SECONDS"] = "-1"
            stale = subprocess.Popen(
                [
                    "/bin/bash",
                    "-c",
                    stale_script.read_text(),
                    "deploy.sh sandbox-down stale",
                ],
                env=env,
                start_new_session=True,
            )
            pid_paths = [root / name for name in ("root.pid", "child.pid", "grandchild.pid")]
            try:
                for path in pid_paths:
                    wait_for_path(path)
                pids = [int(path.read_text().strip()) for path in pid_paths]

                result = subprocess.run(
                    [str(DEPLOY), "sandbox-down", "missing"],
                    cwd=ROOT,
                    env=env,
                    capture_output=True,
                    text=True,
                    check=False,
                )

                self.assertEqual(1, result.returncode)
                self.assertIn("stale deploy process tree", result.stdout)
                self.assertIn(str(stale.pid), result.stdout)
                self.assertIn("sandbox marker is required for teardown", result.stderr)
                for pid in pids:
                    wait_for_exit(pid)
            finally:
                for path in reversed(pid_paths):
                    if not path.exists():
                        continue
                    try:
                        os.kill(int(path.read_text().strip()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                try:
                    stale.wait(timeout=PROCESS_EXIT_TIMEOUT_SECONDS)
                except subprocess.TimeoutExpired:
                    stale.kill()
                    stale.wait(timeout=PROCESS_EXIT_TIMEOUT_SECONDS)

    def test_sandbox_down_waits_for_the_shared_docker_lock(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            env = self.deploy_env(root)
            env["ROADTRIP_HOST_DOCKER_LOCK_WAIT_SECONDS"] = "0"
            lock = root / "home" / ".roadtrip" / "locks" / "docker-operation"
            lock.mkdir(parents=True)
            (lock / "pid").write_text(f"{os.getpid()}\n")

            result = subprocess.run(
                [str(DEPLOY), "sandbox-down", "missing"],
                cwd=ROOT,
                env=env,
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(1, result.returncode)
            self.assertIn("timed out waiting for Docker operation lock", result.stderr)
            self.assertTrue(lock.exists(), "a waiter must not remove a live owner's lock")

    def test_reclaim_uses_the_same_lock_before_pruning(self) -> None:
        workflow = yaml.safe_load(
            (ROOT / ".github" / "workflows" / "sandbox-sweep.yml").read_text()
        )
        reclaim = workflow["jobs"]["reclaim"]
        command = next(
            step["run"]
            for step in reclaim["steps"]
            if step.get("name") == "Reclaim disk on the deploy host"
        )

        self.assertIn("$HOME/.roadtrip/locks/docker-operation", command)
        self.assertLess(
            command.index('mkdir "$lock_dir"'),
            command.index("docker builder prune"),
        )
        self.assertIn("trap release_lock EXIT", command)


if __name__ == "__main__":
    unittest.main()
