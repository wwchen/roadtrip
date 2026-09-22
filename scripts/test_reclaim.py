#!/usr/bin/env python3
"""Behavioural coverage for scripts/reclaim.sh.

reclaim.sh is the single implementation of disk reclaim for prod deploys, the
sandbox sweep, and local development. The risk it carries is blast radius: it
runs on a host shared with unrelated Docker stacks, so every destructive call
has to stay inside the roadtrip label. These tests drive the real script
against a fake `docker` on PATH and assert on the exact argv it produces.
"""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RECLAIM = ROOT / "scripts" / "reclaim.sh"

FAKE_DOCKER = """#!/bin/sh
printf '%s\\n' "$*" >> "$DOCKER_LOG"
case "$1" in
  image)
    case "$2" in
      ls)
        case "$*" in
          *dangling=true*) cut -d' ' -f1 "$FAKE_DANGLING" 2>/dev/null; true ;;
          *)
            case "$3" in
              roadtrip/backend) cat "$FAKE_IMAGE_LS" 2>/dev/null ;;
              ghcr.io/wwchen/roadtrip/recgov-companion) cat "$FAKE_COMPANION_LS" 2>/dev/null ;;
            esac
            true ;;
        esac
        ;;
      inspect)
        case "$*" in
          *Created*) grep "^$5 " "$FAKE_DANGLING" 2>/dev/null | cut -d' ' -f2; true ;;
          *) echo "sha256:deadbeef" ;;
        esac
        ;;
      rm) echo "Deleted: $3" ;;
    esac
    ;;
esac
exit 0
"""


class ReclaimTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.log = self.tmp / "docker.log"
        self.image_ls = self.tmp / "images.txt"
        self.image_ls.write_text("")
        self.dangling = self.tmp / "dangling.txt"
        self.dangling.write_text("")
        self.companion_ls = self.tmp / "companion.txt"
        self.companion_ls.write_text("")
        fake = self.bin / "docker"
        fake.write_text(FAKE_DOCKER)
        fake.chmod(0o755)
        self.addCleanup(self._tmp.cleanup)

    def run_reclaim(self, *args: str, **env: str) -> subprocess.CompletedProcess:
        environ = dict(os.environ)
        environ["PATH"] = f"{self.bin}{os.pathsep}{environ['PATH']}"
        environ["DOCKER_LOG"] = str(self.log)
        environ["FAKE_IMAGE_LS"] = str(self.image_ls)
        environ["FAKE_DANGLING"] = str(self.dangling)
        environ["FAKE_COMPANION_LS"] = str(self.companion_ls)
        environ.update(env)
        return subprocess.run(
            ["bash", str(RECLAIM), *args],
            capture_output=True, text=True, check=False, env=environ,
        )

    def docker_calls(self) -> list:
        if not self.log.exists():
            return []
        return [line for line in self.log.read_text().splitlines() if line]


class CheckDiskTest(ReclaimTestCase):
    def test_passes_when_free_space_meets_the_floor(self) -> None:
        done = self.run_reclaim(
            "check-disk", "--label", "unit test", "--min-gb", "0",
            "--path", str(self.tmp),
        )
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIn("disk check", done.stdout)

    def test_fails_when_free_space_is_under_the_floor(self) -> None:
        done = self.run_reclaim(
            "check-disk", "--label", "unit test", "--min-gb", "999999999",
            "--path", str(self.tmp),
        )
        self.assertEqual(done.returncode, 1)
        self.assertIn("unit test", done.stderr)
        self.assertIn("deadlocks the Docker daemon", done.stderr)

    def test_unknown_command_exits_two(self) -> None:
        done = self.run_reclaim("nonsense")
        self.assertEqual(done.returncode, 2)

    def test_unreadable_path_warns_and_succeeds(self) -> None:
        done = self.run_reclaim(
            "check-disk", "--label", "unit test", "--min-gb", "1",
            "--path", "/nonexistent/path/xyz",
        )
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIn("could not read free space", done.stderr)

    def test_option_without_a_value_is_a_usage_error(self) -> None:
        done = self.run_reclaim("check-disk", "--min-gb")
        self.assertEqual(done.returncode, 2)
        self.assertIn("requires a value", done.stderr)

    def test_option_value_that_looks_like_a_flag_is_a_usage_error(self) -> None:
        # A missing value followed by another flag (a typo dropping the
        # actual value) must not be swallowed as that flag's value -- it
        # used to blow up later with "path: unbound variable" and exit 1,
        # which deploy.sh's `|| exit 1` misreads as "disk under floor".
        done = self.run_reclaim("check-disk", "--min-gb", "--path", str(self.tmp))
        self.assertEqual(done.returncode, 2)
        self.assertIn("requires a value", done.stderr)


# The deploy host's shape: a daemon that accepts the connection and never
# answers, which is what a full disk does to Docker. `sleep` is exec'd so the
# watchdog's TERM lands on the process that is actually blocking.
HUNG_DOCKER = """#!/bin/sh
printf '%s\\n' "$*" >> "$DOCKER_LOG"
exec sleep 60
"""


class DiskCheckDiagnosisTest(ReclaimTestCase):
    """A failed disk check says where the space is, not just how little is left."""

    def check_under_the_floor(self, **env: str) -> subprocess.CompletedProcess:
        return self.run_reclaim(
            "check-disk", "--label", "prod deploy", "--scope", "host",
            "--min-gb", "999999999", "--path", str(self.tmp), **env,
        )

    def test_names_the_roadtrip_images_and_docker_as_a_whole(self) -> None:
        self.image_ls.write_text("roadtrip/backend:v1  1.2GB  2 days ago\n")
        done = self.check_under_the_floor()
        self.assertEqual(done.returncode, 1)
        self.assertIn("where the space is", done.stderr)
        self.assertIn("roadtrip/backend:v1", done.stderr)
        self.assertIn("system df", self.docker_calls())

    def test_marks_the_rollback_copies_prune_keeps_on_purpose(self) -> None:
        # The five newest per repository are exactly what prune will never
        # take, so they are the likeliest place a 0B prune leaves the space.
        self.image_ls.write_text(FOUR_TAGS)
        done = self.check_under_the_floor()
        self.assertEqual(done.stderr.count("kept for rollback: newest 5"), 4)

    def test_without_a_scope_it_does_not_guess_the_keep_depth(self) -> None:
        self.image_ls.write_text(FOUR_TAGS)
        done = self.run_reclaim(
            "check-disk", "--label", "unit test", "--min-gb", "999999999",
            "--path", str(self.tmp),
        )
        self.assertEqual(done.returncode, 1)
        self.assertIn("roadtrip/backend:latest", done.stderr)
        self.assertNotIn("kept for rollback", done.stderr)

    def test_advice_is_scoped_and_never_an_unscoped_docker_prune(self) -> None:
        # The old hint recommended `docker image prune -f` -- the one command
        # that reaches past the label on a host shared with other stacks --
        # and `reclaim.sh prune` with no --scope, which is a usage error.
        done = self.check_under_the_floor()
        self.assertIn("scripts/reclaim.sh report --scope host", done.stderr)
        self.assertNotIn("docker image prune", done.stderr)
        self.assertNotIn("docker volume prune", done.stderr)

    def test_diagnosis_never_prunes(self) -> None:
        self.image_ls.write_text(FOUR_TAGS)
        self.check_under_the_floor()
        destructive = [c for c in self.docker_calls()
                       if " prune" in c or c.startswith(("image rm", "volume rm"))]
        self.assertEqual(destructive, [])

    def test_a_passing_check_asks_docker_nothing(self) -> None:
        done = self.run_reclaim(
            "check-disk", "--label", "unit test", "--min-gb", "0",
            "--path", str(self.tmp),
        )
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(self.docker_calls(), [])

    def test_a_wedged_daemon_bounds_the_diagnosis_instead_of_hanging_the_deploy(self) -> None:
        (self.bin / "docker").write_text(HUNG_DOCKER)
        # Every call hangs: one per repository plus `system df`, each given up
        # on after a second. Well inside the budget, and nowhere near 60s each.
        calls = len(RECLAIM.read_text().split("ROADTRIP_REPOSITORIES=\"")[1]
                    .split("\"")[0].split()) + 1
        done = subprocess.run(
            ["bash", str(RECLAIM), "check-disk", "--label", "prod deploy",
             "--scope", "host", "--min-gb", "999999999", "--path", str(self.tmp)],
            capture_output=True, text=True, check=False, timeout=calls * 1 + 20,
            env={**os.environ,
                 "PATH": f"{self.bin}{os.pathsep}{os.environ['PATH']}",
                 "DOCKER_LOG": str(self.log),
                 "RECLAIM_DIAGNOSIS_TIMEOUT_S": "1"},
        )
        self.assertEqual(done.returncode, 1, "a hung read must not turn the failure into anything else")
        self.assertIn("gave up after 1s", done.stderr)
        self.assertIn("may already be wedged", done.stderr)


FOUR_TAGS = "\n".join([
    "roadtrip/backend:tilt-aaaa",
    "roadtrip/backend:tilt-bbbb",
    "roadtrip/backend:latest",
    "roadtrip/backend:tilt-cccc",
]) + "\n"


COMPANION = "ghcr.io/wwchen/roadtrip/recgov-companion"

FOUR_COMPANION_TAGS = "\n".join([
    f"{COMPANION}:newest",
    f"{COMPANION}:previous",
    f"{COMPANION}:older",
    f"{COMPANION}:oldest",
]) + "\n"


class CompanionKeepDepthTest(ReclaimTestCase):
    """The companion carries its own rollback depth: a copy is ~3.5GB against a
    backend's ~0.7GB, and a rollback reaches one release back."""

    def companion_removals(self) -> list:
        return [c for c in self.docker_calls() if c.startswith(f"image rm {COMPANION}")]

    def test_host_scope_keeps_two_companions_while_backend_keeps_five(self) -> None:
        self.companion_ls.write_text(FOUR_COMPANION_TAGS)
        self.image_ls.write_text(FOUR_TAGS)
        done = self.run_reclaim("prune", "--scope", "host")
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(
            self.companion_removals(),
            [f"image rm {COMPANION}:older", f"image rm {COMPANION}:oldest"],
        )
        # Same run, same depth as before for the small image.
        self.assertEqual(
            [c for c in self.docker_calls() if c.startswith("image rm roadtrip/backend")], [],
        )

    def test_local_scope_is_already_two_and_is_not_deepened(self) -> None:
        self.companion_ls.write_text(FOUR_COMPANION_TAGS)
        done = self.run_reclaim("prune", "--scope", "local")
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(
            self.companion_removals(),
            [f"image rm {COMPANION}:older", f"image rm {COMPANION}:oldest"],
        )

    def test_a_shallower_scope_is_never_deepened_for_the_companion(self) -> None:
        # ROADTRIP_IMAGE_KEEP=1 asks for one copy of everything; the companion
        # default must not quietly hold a second.
        self.companion_ls.write_text(FOUR_COMPANION_TAGS)
        self.run_reclaim("prune", "--scope", "host", ROADTRIP_IMAGE_KEEP="1")
        self.assertEqual(
            self.companion_removals(),
            [f"image rm {COMPANION}:previous",
             f"image rm {COMPANION}:older",
             f"image rm {COMPANION}:oldest"],
        )

    def test_a_deeper_scope_does_not_deepen_the_companion(self) -> None:
        self.companion_ls.write_text(FOUR_COMPANION_TAGS)
        self.run_reclaim("prune", "--scope", "host", ROADTRIP_IMAGE_KEEP="10")
        self.assertEqual(
            self.companion_removals(),
            [f"image rm {COMPANION}:older", f"image rm {COMPANION}:oldest"],
        )

    def test_an_explicit_override_is_taken_as_given(self) -> None:
        self.companion_ls.write_text(FOUR_COMPANION_TAGS)
        self.run_reclaim("prune", "--scope", "host", ROADTRIP_COMPANION_IMAGE_KEEP="3")
        self.assertEqual(self.companion_removals(), [f"image rm {COMPANION}:oldest"])

    def test_the_diagnosis_marks_the_depth_it_actually_keeps(self) -> None:
        # The failed-check diagnosis and prune read the same function, so the
        # log cannot claim to keep five of an image prune keeps two of.
        self.companion_ls.write_text(FOUR_COMPANION_TAGS)
        done = self.run_reclaim(
            "check-disk", "--label", "prod deploy", "--scope", "host",
            "--min-gb", "999999999", "--path", str(self.tmp),
        )
        self.assertEqual(done.returncode, 1)
        self.assertIn(f"{COMPANION}:newest  (kept for rollback: newest 2)", done.stderr)
        self.assertIn(f"{COMPANION}:older", done.stderr)
        self.assertNotIn(f"{COMPANION}:older  (kept", done.stderr)


class PruneTest(ReclaimTestCase):
    def test_prune_without_scope_is_a_usage_error_and_runs_nothing(self) -> None:
        # The critical bug this guards: SCOPE used to default to "host", so a
        # bare `reclaim.sh prune` ran an unlabeled `docker volume prune
        # --force` -- destroying anonymous volumes belonging to unrelated
        # stacks on a shared machine. --scope must now be an explicit choice.
        done = self.run_reclaim("prune")
        self.assertEqual(done.returncode, 2)
        self.assertIn("--scope", done.stderr)
        self.assertIn("local", done.stderr)
        self.assertIn("host", done.stderr)
        self.assertEqual(self.docker_calls(), [])

    def test_report_without_scope_is_also_a_usage_error(self) -> None:
        done = self.run_reclaim("report")
        self.assertEqual(done.returncode, 2)
        self.assertIn("--scope", done.stderr)

    def test_no_include_anonymous_overrides_host_default(self) -> None:
        done = self.run_reclaim(
            "prune", "--scope", "host", "--no-include-anonymous",
        )
        self.assertEqual(done.returncode, 0, done.stderr)
        bare = [c for c in self.docker_calls()
                if c.startswith("volume prune") and "label=" not in c]
        self.assertEqual(bare, [], "host --no-include-anonymous must skip the bare volume prune")

    def test_container_prune_carries_an_age_floor(self) -> None:
        # A roadtrip container inherits the managed label from its image, so
        # a bare label filter can reap a container a concurrent deploy just
        # created but has not started yet (the create-to-start gap in
        # `docker compose up`). An age floor keeps that container alive.
        self.run_reclaim("prune", "--scope", "local")
        container_prunes = [c for c in self.docker_calls()
                             if c.startswith("container prune")]
        self.assertTrue(container_prunes)
        for call in container_prunes:
            self.assertIn("until=", call, call)

    def test_local_scope_keeps_two_tags_per_repository(self) -> None:
        self.image_ls.write_text(FOUR_TAGS)
        done = self.run_reclaim("prune", "--scope", "local")
        self.assertEqual(done.returncode, 0, done.stderr)
        removed = [c for c in self.docker_calls() if c.startswith("image rm ")]
        self.assertIn("image rm roadtrip/backend:latest", removed)
        self.assertIn("image rm roadtrip/backend:tilt-cccc", removed)
        self.assertNotIn("image rm roadtrip/backend:tilt-aaaa", removed)
        self.assertNotIn("image rm roadtrip/backend:tilt-bbbb", removed)

    def test_host_scope_keeps_five_so_four_tags_survive(self) -> None:
        self.image_ls.write_text(FOUR_TAGS)
        done = self.run_reclaim("prune", "--scope", "host")
        self.assertEqual(done.returncode, 0, done.stderr)
        removed = [c for c in self.docker_calls()
                   if c.startswith("image rm roadtrip/backend")]
        self.assertEqual(removed, [])

    def test_every_prune_call_is_label_scoped(self) -> None:
        self.run_reclaim("prune", "--scope", "local")
        prunes = [c for c in self.docker_calls() if " prune" in c]
        self.assertTrue(prunes)
        for call in prunes:
            self.assertIn("label=ca.floo.roadtrip.managed=true", call, call)

    def test_local_scope_never_prunes_anonymous_volumes(self) -> None:
        self.run_reclaim("prune", "--scope", "local")
        for call in self.docker_calls():
            if call.startswith("volume prune"):
                self.assertIn("label=", call, call)

    def test_host_scope_does_prune_anonymous_volumes(self) -> None:
        self.run_reclaim("prune", "--scope", "host")
        bare = [c for c in self.docker_calls()
                if c.startswith("volume prune") and "label=" not in c]
        self.assertTrue(bare, "host scope must reach anonymous volumes")

    def test_dry_run_makes_no_destructive_call(self) -> None:
        self.image_ls.write_text(FOUR_TAGS)
        self.run_reclaim("prune", "--scope", "local", "--dry-run")
        for call in self.docker_calls():
            self.assertFalse(call.startswith("image rm"), call)
            self.assertNotIn(" prune", call)

    def test_report_changes_nothing(self) -> None:
        self.image_ls.write_text(FOUR_TAGS)
        done = self.run_reclaim("report", "--scope", "local")
        self.assertEqual(done.returncode, 0, done.stderr)
        for call in self.docker_calls():
            self.assertFalse(call.startswith("image rm"), call)
            self.assertNotIn(" prune", call)

    def test_report_names_the_images_it_would_remove(self) -> None:
        self.image_ls.write_text(FOUR_TAGS)
        done = self.run_reclaim("report", "--scope", "local")
        self.assertIn("roadtrip/backend:latest", done.stdout)
        self.assertIn("roadtrip/backend:tilt-cccc", done.stdout)
        self.assertNotIn("roadtrip/backend:tilt-aaaa", done.stdout)

    def test_image_keep_env_override_wins_over_scope_default(self) -> None:
        self.image_ls.write_text(FOUR_TAGS)
        self.run_reclaim("prune", "--scope", "local", ROADTRIP_IMAGE_KEEP="4")
        removed = [c for c in self.docker_calls()
                   if c.startswith("image rm roadtrip/backend")]
        self.assertEqual(removed, [])

    def _dangling(self, *rows: str) -> None:
        self.dangling.write_text("".join(f"{r}\n" for r in rows))

    def test_untagged_images_past_retention_are_removed_by_id(self) -> None:
        # `docker image prune --filter until` left nine such images on the
        # deploy host; the explicit list-and-rm pass must take them.
        self._dangling("oldimg 2020-01-01T00:00:00.123456789Z")
        done = self.run_reclaim("prune", "--scope", "local")
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIn("image rm oldimg", self.docker_calls())
        self.assertIn("Deleted: oldimg", done.stdout)
        self.assertNotIn(" prune", "".join(c for c in self.docker_calls() if c.startswith("image")))

    def test_untagged_images_inside_retention_survive(self) -> None:
        recent = subprocess.run(
            ["date", "-u", "+%Y-%m-%dT%H:%M:%SZ"], capture_output=True, text=True, check=True,
        ).stdout.strip()
        self._dangling(f"newimg {recent}", "oldimg 2020-01-01T00:00:00Z")
        done = self.run_reclaim("prune", "--scope", "local")
        self.assertEqual(done.returncode, 0, done.stderr)
        removed = [c for c in self.docker_calls() if c.startswith("image rm ")]
        self.assertEqual(removed, ["image rm oldimg"])

    def test_untagged_image_pass_is_label_scoped(self) -> None:
        self.run_reclaim("prune", "--scope", "local")
        listing = [c for c in self.docker_calls() if c.startswith("image ls") and "dangling" in c]
        self.assertTrue(listing)
        self.assertIn("label=ca.floo.roadtrip.managed=true", listing[0])

    def test_untagged_image_dry_run_announces_and_removes_nothing(self) -> None:
        self._dangling("oldimg 2020-01-01T00:00:00Z")
        done = self.run_reclaim("prune", "--scope", "local", "--dry-run")
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIn("dry-run: docker image rm oldimg", done.stdout)
        self.assertNotIn("image rm oldimg", self.docker_calls())

    def test_default_image_retention_is_72_hours(self) -> None:
        done = self.run_reclaim("prune", "--scope", "local", "--dry-run")
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIn("older than 72h", done.stdout)

    def test_retention_must_be_whole_hours(self) -> None:
        done = self.run_reclaim("prune", "--scope", "local", ROADTRIP_IMAGE_RETENTION="2d")
        self.assertEqual(done.returncode, 2)
        self.assertIn("whole hours", done.stderr)


class DeployIntegrationTest(unittest.TestCase):
    """deploy.sh must delegate reclaim, and must NOT lose the volume hold.

    The hold at _hold_data_volume exists because prod, sandbox, and the sweep
    sit in three different concurrency groups, so a prune can land in the
    window where a data volume exists but nothing mounts it yet. Moving the
    prune out of deploy.sh is exactly the change most likely to drop it.
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.source = (ROOT / "scripts" / "deploy.sh").read_text()

    def test_private_reclaim_helpers_are_gone(self) -> None:
        for name in ("_require_free_disk", "_prune_roadtrip_images",
                     "_prune_data_volumes"):
            self.assertNotIn(f"{name}() {{", self.source,
                             f"{name} should have moved to reclaim.sh")

    def test_deploy_delegates_to_reclaim(self) -> None:
        # Asserting on the exact call, not the bare word "prune", which still
        # appears in the volume-hold comments and would pass either way.
        self.assertIn('RECLAIM="${SCRIPT_DIR}/reclaim.sh"', self.source)
        self.assertIn('"${RECLAIM}" check-disk --label "prod deploy"', self.source)
        self.assertIn('"${RECLAIM}" check-disk --label "sandbox deploy"', self.source)
        self.assertIn("MIN_FREE_DISK_GB", self.source)
        # Five call sites, not three: prod-deploy preflight and post-deploy,
        # sandbox-preflight and teardown, and the sandbox-up success path
        # (top-level, after the health check passes). Preflight now prunes
        # before check-disk on both the prod and sandbox paths, so a host
        # that dips under the free-space floor reclaims policy-eligible
        # space immediately rather than waiting for the next scheduled sweep.
        self.assertEqual(self.source.count('"${RECLAIM}" prune --scope host'), 5)
        # Pre-branch, only the 30-minute sweep pruned anonymous volumes;
        # deploy.sh's own prune was label-scoped only. All five deploy.sh
        # call sites run far more often than the sweep (every prod deploy,
        # sandbox up, and sandbox down), so they must opt back out of
        # anonymous-volume pruning rather than inherit --scope host's default.
        self.assertEqual(
            self.source.count('"${RECLAIM}" prune --scope host --no-include-anonymous'),
            5,
        )

    def test_preflight_prunes_before_it_checks_disk(self) -> None:
        # A host under the free-space floor must reclaim policy-eligible
        # space before check-disk measures it, not after a successful
        # deploy -- otherwise nothing prunes until the next scheduled sweep.
        prune_call = '"${RECLAIM}" prune --scope host --no-include-anonymous'
        for label in ('prod deploy', 'sandbox deploy'):
            check_call = f'"${{RECLAIM}}" check-disk --label "{label}"'
            check_index = self.source.index(check_call)
            preceding = self.source[:check_index]
            self.assertIn(
                prune_call, preceding,
                f"{label} preflight must prune before check-disk",
            )
            # The prune is the line right before check-disk, so a stray
            # command cannot slip in between reclaiming and measuring.
            last_prune_index = preceding.rindex(prune_call)
            between = preceding[last_prune_index + len(prune_call):check_index]
            self.assertEqual(between.strip(), "", f"{label}: {between!r}")

    def test_volume_hold_survives(self) -> None:
        self.assertIn("_hold_data_volume() {", self.source)
        self.assertIn("_release_data_volume() {", self.source)
        self.assertIn("DATA_VOLUME_GUARD_SECONDS", self.source)

    def test_no_bare_docker_prune_left_in_deploy(self) -> None:
        for line in self.source.splitlines():
            stripped = line.strip()
            if stripped.startswith("#"):
                continue
            if "docker image prune" in stripped or "docker volume prune" in stripped:
                self.fail(f"deploy.sh still prunes directly: {stripped}")


if __name__ == "__main__":
    unittest.main()
