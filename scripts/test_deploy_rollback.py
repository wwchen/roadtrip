#!/usr/bin/env python3
"""Behavioural coverage for `deploy.sh prod` rollback and `deploy.sh record-good`.

Runs the real script against a stub `docker`, a stub secrets wrapper and a stub
reclaim, so the paths a failed production deploy takes can be exercised
without a host.
"""

import os
import shutil
import stat
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / "scripts" / "deploy.sh"

PROCESS_TIMEOUT_SECONDS = 60

NEW_SHA = "a" * 40
LAST_GOOD_SHA = "b" * 40
DATA_SHA = "d" * 40
COMPANION_SHA = "c" * 40
BRANCH = "master"

DOCKER_STUB = textwrap.dedent(
    """\
    #!/usr/bin/env bash
    printf '%s\\n' "$*" >> "$DOCKER_LOG"
    args=" $* "
    if [[ "$args" == *"cat /target/.roadtrip-data-sha"* ]]; then
        printf '%s\\n' "$ROADTRIP_DATA_SHA"
        exit 0
    fi
    if [[ "$args" == *" container inspect "* ]]; then
        exit 1
    fi
    if [[ "$args" == *" compose "* && "$args" == *" up "* && "$args" != *" backend "* ]]; then
        for sha in $UNHEALTHY_SHAS; do
            if [[ "$ROADTRIP_BACKEND_IMAGE" == *":$sha" ]]; then
                echo "dependency failed to start: container roadtrip-backend-1 is unhealthy" >&2
                exit 1
            fi
        done
    fi
    if [[ "$args" == *" compose "* && "$args" == *" logs "* ]]; then
        echo "backend-1 | the reason the backend did not start"
    fi
    if [[ "$args" == *" compose "* && "$args" == *" exec "* ]]; then
        cat > /dev/null
    fi
    exit 0
    """
)

SECRETS_STUB = textwrap.dedent(
    """\
    #!/usr/bin/env bash
    while [[ $# -gt 0 && "$1" != "--" ]]; do shift; done
    shift
    exec "$@"
    """
)

RECLAIM_STUB = "#!/usr/bin/env bash\nexit 0\n"


def _executable(path: Path, body: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body)
    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


class DeployRollbackTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)
        self.release = self.tmp / "release"
        self.home = self.tmp / "home"
        self.bin = self.tmp / "bin"
        self.docker_log = self.tmp / "docker.log"
        self.last_good = self.home / "last-good-release"

        (self.release / "scripts").mkdir(parents=True)
        shutil.copy2(DEPLOY, self.release / "scripts" / "deploy.sh")
        _executable(self.release / "scripts" / "reclaim.sh", RECLAIM_STUB)
        _executable(self.release / "secrets" / "manage.py", SECRETS_STUB)
        _executable(self.bin / "docker", DOCKER_STUB)
        self.home.mkdir()

    def _record(self, app_sha: str) -> None:
        self.last_good.write_text(
            f"APP_SHA={app_sha}\nDATA_SHA={DATA_SHA}\nCOMPANION_SHA={COMPANION_SHA}\nBRANCH={BRANCH}\n"
        )

    def _deploy(self, *args: str, unhealthy: tuple = ()) -> subprocess.CompletedProcess:
        return self._run(["bash", "scripts/deploy.sh", *args], "", unhealthy)

    def _run(self, command: list, stdin: str, unhealthy: tuple = ()) -> subprocess.CompletedProcess:
        return subprocess.run(
            command,
            cwd=self.release,
            input=stdin,
            env={
                **os.environ,
                "PATH": f"{self.bin}{os.pathsep}{os.environ['PATH']}",
                "ROADTRIP_HOME": str(self.home),
                "SANDBOX_CADDY_DIR": str(self.tmp / "caddy"),
                "DOCKER_LOG": str(self.docker_log),
                "UNHEALTHY_SHAS": " ".join(unhealthy),
                "ROADTRIP_DEPLOY_WAIT_SECONDS": "1",
            },
            capture_output=True,
            text=True,
            timeout=PROCESS_TIMEOUT_SECONDS,
            check=False,
        )

    def test_a_backend_that_never_turns_healthy_is_rolled_back(self) -> None:
        self._record(LAST_GOOD_SHA)

        done = self._deploy("prod", NEW_SHA, DATA_SHA, COMPANION_SHA, BRANCH, unhealthy=(NEW_SHA,))

        self.assertEqual(1, done.returncode, done.stderr)
        self.assertIn("the reason the backend did not start", done.stdout + done.stderr)
        self.assertIn(f"==> ROLLBACK OK: {NEW_SHA} was unhealthy; production is back on {LAST_GOOD_SHA}", done.stderr)

    def test_a_deploy_fed_its_remote_script_on_stdin_runs_the_lines_after_it(self) -> None:
        remote = f"scripts/deploy.sh prod {NEW_SHA} {DATA_SHA} {COMPANION_SHA} {BRANCH}\necho the line after the deploy\n"

        done = self._run(["bash", "-s"], remote)

        self.assertEqual(0, done.returncode, done.stderr)
        self.assertIn("the line after the deploy", done.stdout)

    def test_a_healthy_deploy_leaves_the_last_good_record_to_the_workflow(self) -> None:
        self._record(LAST_GOOD_SHA)

        done = self._deploy("prod", NEW_SHA, DATA_SHA, COMPANION_SHA, BRANCH)

        self.assertEqual(0, done.returncode, done.stderr)
        self.assertIn(f"APP_SHA={LAST_GOOD_SHA}", self.last_good.read_text())

    def test_record_good_names_the_release_to_roll_back_to(self) -> None:
        done = self._deploy("record-good", NEW_SHA, DATA_SHA, COMPANION_SHA, BRANCH)

        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual(
            f"APP_SHA={NEW_SHA}\nDATA_SHA={DATA_SHA}\nCOMPANION_SHA={COMPANION_SHA}\nBRANCH={BRANCH}\n",
            self.last_good.read_text(),
        )

    def test_record_good_refuses_anything_but_full_shas(self) -> None:
        self._record(LAST_GOOD_SHA)

        done = self._deploy("record-good", "abc123", DATA_SHA, COMPANION_SHA, BRANCH)

        self.assertEqual(2, done.returncode)
        self.assertIn(f"APP_SHA={LAST_GOOD_SHA}", self.last_good.read_text())


if __name__ == "__main__":
    unittest.main()
