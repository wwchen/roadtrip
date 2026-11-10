#!/usr/bin/env python3

import re
import subprocess
import tempfile
import unittest
from pathlib import Path, PurePosixPath

import yaml


ROOT = Path(__file__).resolve().parents[1]

AWAIT_IMAGES_ACTION = (
    ROOT / ".github" / "actions" / "await-images" / "action.yml"
).read_text()

# The shipped shell scripts address their siblings through ${SCRIPT_DIR} and
# the release root through ${REPO_ROOT}. On the deploy host both resolve
# inside the unpacked release, so a file the manifest omits is simply absent
# there.
SHELL_PATH_REFERENCE = re.compile(r"\$\{(SCRIPT_DIR|REPO_ROOT)\}/([A-Za-z0-9._/-]+)")
SHELL_REFERENCE_ROOTS = {"SCRIPT_DIR": "scripts", "REPO_ROOT": ""}


COMPANION_KEY_SCRIPT = "scripts/companion-image-key.sh"

SCHEMA_EQUIVALENCE_TEST = "scripts/schema-equivalence.test.mjs"
SCHEMA_REFERENCE_TEST = "scripts/schema-reference.test.mjs"
BUILD_WORKSPACES = "scripts/build-workspaces.mjs"
PACKAGE_TYPECHECKS = (
    "npm run typecheck --workspace @roadtrip/domain",
    "npm run typecheck --workspace @roadtrip/db",
)
FLYWAY_MIGRATIONS = "backend/src/main/resources/db/migration"

KOTLIN_PACKAGE_ROOT = "backend/src/main/kotlin/ca/floo/roadtrip"
# The importer's entry point and the two trees it is built from. Every file
# these reach, transitively and through same-package use, is part of what that
# importer writes, so the path filter has to watch it or the gate silently does
# not run.
IMPORTER_KOTLIN_SOURCES = ("tools", "service/etl", "repo")
IMPORTER_DIFFERENTIAL = "scripts/importer-differential.mjs"
PROVENANCE_TREES_BLOCK = re.compile(r"const PROVENANCE_TREES = \[(.*?)\];", re.S)
JS_CONST_LIST = "const {} = \\[(.*?)\\];"
JS_QUOTED = re.compile(r"'([^']+)'")
JS_BARE = re.compile(r"^[A-Z][A-Z0-9_]*$", re.M)
KOTLIN_REFERENCE = re.compile(r"\bca\.floo\.roadtrip((?:\.[A-Za-z0-9_]+)+)")
KOTLIN_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
KOTLIN_PACKAGE_DECLARATION = re.compile(r"^package .*$", re.M)
KOTLIN_TOP_LEVEL_DECLARATION = re.compile(
    r"^(?:[a-z]+\s+)*(?:class|object|interface|fun|val|var|typealias)\s+"
    r"(?:<[^>]*>\s*)?(?:[A-Za-z_][\w<>?, ]*\.)?([A-Za-z_]\w*)",
    re.M,
)
KOTLIN_IDENTIFIER = re.compile(r"\b[A-Za-z_]\w*\b")


def companion_key_inputs() -> list[str]:
    declared = re.search(
        r"COMPANION_IMAGE_INPUTS=\(([^)]*)\)", (ROOT / COMPANION_KEY_SCRIPT).read_text()
    )
    assert declared, "COMPANION_IMAGE_INPUTS not found"
    return declared.group(1).split()


def tracked_paths() -> set[str]:
    out = subprocess.run(
        ["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True
    ).stdout
    return set(out.splitlines())


def js_path_list(name: str) -> list[str]:
    """The string entries of a `const <name> = [...]` list in the differential.

    A bare identifier entry is resolved through its own `const <IDENT> = '...'`,
    so PROVENANCE_FILES' REGISTRY_RELATIVE_PATH is checked like any other path.
    """
    source = (ROOT / IMPORTER_DIFFERENTIAL).read_text()
    block = re.search(JS_CONST_LIST.format(name), source, re.S)
    assert block is not None, f"{name} not found in {IMPORTER_DIFFERENTIAL}"
    paths = []
    for entry in block.group(1).split(","):
        entry = entry.strip()
        if not entry:
            continue
        quoted = JS_QUOTED.search(entry)
        if quoted is not None:
            paths.append(quoted.group(1))
            continue
        if JS_BARE.match(entry):
            resolved = re.search(rf"const {entry} = '([^']+)'", source)
            assert resolved is not None, f"{name} names {entry}, which resolves to no path"
            paths.append(resolved.group(1))
    return paths


def path_exists(path: str, tracked: set[str]) -> bool:
    if path in tracked:
        return True
    prefix = f"{path}/"
    return any(candidate.startswith(prefix) for candidate in tracked)


class KotlinSources:
    def __init__(self, package_root: Path) -> None:
        self.root = package_root
        self.code: dict[Path, str] = {}
        self.declared: dict[str, dict[str, set[Path]]] = {}
        for path in package_root.rglob("*.kt"):
            code = KOTLIN_PACKAGE_DECLARATION.sub("", KOTLIN_BLOCK_COMMENT.sub("", path.read_text()))
            self.code[path] = code
            names = self.declared.setdefault(self.package_of(path), {})
            for name in KOTLIN_TOP_LEVEL_DECLARATION.findall(code):
                names.setdefault(name, set()).add(path)

    def package_of(self, path: Path) -> str:
        return path.parent.relative_to(self.root).as_posix()

    def files_in(self, package: str) -> set[Path]:
        return {path for path in self.code if self.package_of(path) == package}

    def referenced_by(self, path: Path) -> set[Path]:
        code = self.code[path]
        own = self.declared.get(self.package_of(path), {})
        used = set(KOTLIN_IDENTIFIER.findall(code))
        files = {declaring for name in used & own.keys() for declaring in own[name]}
        for dotted in KOTLIN_REFERENCE.findall(code):
            segments = dotted.strip(".").split(".")
            depth = len(segments)
            while depth and not (self.root / "/".join(segments[:depth])).is_dir():
                depth -= 1
            if not depth:
                continue
            package = "/".join(segments[:depth])
            name = segments[depth] if depth < len(segments) else None
            declaring = self.declared.get(package, {}).get(name)
            files |= declaring if declaring else self.files_in(package)
        return files - {path}


def kotlin_file_closure(seeds: tuple[str, ...], sources: KotlinSources) -> set[Path]:
    reached = {path for seed in seeds for path in (sources.root / seed).rglob("*.kt")}
    frontier = list(reached)
    while frontier:
        for path in sources.referenced_by(frontier.pop()):
            if path not in reached:
                reached.add(path)
                frontier.append(path)
    return reached


def importer_kotlin_files() -> list[str]:
    sources = KotlinSources(ROOT / KOTLIN_PACKAGE_ROOT)
    return sorted(
        path.relative_to(ROOT).as_posix() for path in kotlin_file_closure(IMPORTER_KOTLIN_SOURCES, sources)
    )


def covered(path: str, entries) -> bool:
    return any(path == entry or path.startswith(f"{entry}/") for entry in entries)


def make_recipe(target: str) -> list[str]:
    lines = (ROOT / "Makefile").read_text().splitlines()
    start = next(i for i, line in enumerate(lines) if line.startswith(f"{target}:"))
    recipe = []
    for line in lines[start + 1 :]:
        if line and not line.startswith("\t"):
            break
        command = line.strip()
        if command and not command.startswith("#"):
            recipe.append(command)
    return recipe


def path_filters() -> dict:
    jobs = workflow("ci.yml")["jobs"]
    return yaml.safe_load(
        next(step for step in jobs["changes"]["steps"] if step.get("id") == "filter")[
            "with"
        ]["filters"]
    )


def workflow(name: str) -> dict:
    return yaml.safe_load((ROOT / ".github" / "workflows" / name).read_text())


def workflow_triggers(config: dict) -> dict:
    # PyYAML still treats unquoted `on` as a YAML 1.1 boolean.
    return config.get("on") or config.get(True)


def job_commands(job: dict) -> str:
    return "\n".join(str(step.get("run", "")) for step in job.get("steps", []))


def step_by_name(job: dict, name: str) -> dict:
    for step in job.get("steps", []):
        if step.get("name") == name:
            return step
    raise AssertionError(f"missing step {name!r}")


class DeploymentContractTest(unittest.TestCase):
    def test_neon_migrations_run_on_master_only(self) -> None:
        jobs = workflow("ci.yml")["jobs"]
        job = jobs["neon-migrate"]
        condition = job.get("if", "")
        self.assertIn("github.event_name == 'push'", condition)
        self.assertIn("github.ref == 'refs/heads/master'", condition)
        self.assertIn("needs.ci-passed.result == 'success'", condition)
        self.assertIn("ci-passed", job["needs"])
        migrate = step_by_name(job, "Apply Neon migrations")
        self.assertIn("npm run migrate --workspace @roadtrip/db", migrate["run"])
        self.assertIn('if [ -z "$DATABASE_URL" ]', migrate["run"])
        self.assertIn("::warning::", migrate["run"])
        self.assertEqual("${{ secrets.NEON_MIGRATE_URL }}", migrate["env"]["DATABASE_URL"])

    def test_ci_publishes_every_immutable_image(self) -> None:
        jobs = workflow("ci.yml")["jobs"]
        app = jobs["docker-build"]
        # The job may be skipped on a PR that cannot change the image (docs
        # only), but every master push must still publish backend:<sha>,
        # because deploy pulls exactly that tag.
        self.assertIn("github.event_name == 'push' ||", app.get("if", ""))
        self.assertIn('docker push "$IMAGE:${SHA}"', job_commands(app))
        self.assertIn("--target web", job_commands(app))
        self.assertIn('docker push "$WEB_IMAGE:${SHA}"', job_commands(app))
        self.assertIn("needs.changes.outputs.web == 'true'", app.get("if", ""))

        data_commands = job_commands(jobs["data-image"])
        self.assertIn("git rev-parse HEAD:data", data_commands)
        self.assertIn("docker push", data_commands)

        companion_commands = job_commands(jobs["companion-image"])
        self.assertIn(COMPANION_KEY_SCRIPT, companion_commands)
        self.assertNotIn("git rev-parse HEAD:companion", companion_commands)
        self.assertIn("docker push", companion_commands)

    def test_web_tests_smoke_the_web_image(self) -> None:
        commands = job_commands(workflow("ci.yml")["jobs"]["web-tests"])
        self.assertIn("--target web", commands)
        self.assertIn("WEB_IMAGE=roadtrip/web", commands)

    def test_the_web_filter_watches_the_image_inputs(self) -> None:
        web = path_filters()["web"]
        self.assertIn("Dockerfile", web)
        self.assertIn(".dockerignore", web)

    def test_local_sign_in_runs_through_tilt_and_never_the_production_web_service(self) -> None:
        compose = yaml.safe_load((ROOT / "docker-compose.yml").read_text())
        production_web = " ".join(compose["services"]["web"]["environment"])
        for name in ("NEON_AUTH", "APP_DATABASE", "ADMIN_EMAILS"):
            self.assertNotIn(name, production_web)
        tilt = (ROOT / "Tiltfile").read_text()
        web = tilt[tilt.index("local_resource(\n    'web',") : tilt.index("docker_compose(")]
        self.assertNotIn("NEON_AUTH_BASE_URL", tilt)
        self.assertNotIn("neonauth", tilt)
        self.assertIn("roadtrip_web_writer:", web)
        self.assertIn("resource_deps=['frontend-dist', 'postgres', 'backend']", web)
        self.assertIn("NPM_INSTALL_MARKER]", web)
        self.assertIn("kill -0 $PPID", web)
        self.assertIn("set -eu", web)
        self.assertIn("is not set; add it to the local vault", tilt)
        self.assertNotIn("npm ci --workspace", tilt)

    def test_local_neon_auth_url_is_the_only_committed_dev_setting(self) -> None:
        settings = (ROOT / "apps/web/.env.development").read_text().split()
        self.assertEqual([line.split("=", 1)[0] for line in settings], ["NEON_AUTH_BASE_URL"])
        self.assertIn(".neonauth.", settings[0])

    def test_web_runs_only_in_production(self) -> None:
        compose = yaml.safe_load((ROOT / "docker-compose.yml").read_text())
        web = compose["services"]["web"]
        self.assertEqual(["web"], web["profiles"])
        self.assertEqual("service_healthy", web["depends_on"]["backend"]["condition"])
        self.assertNotIn("ports", web)
        deploy = (ROOT / "scripts" / "deploy.sh").read_text()
        self.assertIn("compose+=(--profile web)", deploy)
        sandbox = yaml.safe_load((ROOT / "docker-compose.sandbox.yml").read_text())
        self.assertNotIn("web", sandbox.get("services", {}))

    def test_a_rollback_to_a_release_without_web_skips_it(self) -> None:
        deploy = (ROOT / "scripts" / "deploy.sh").read_text()
        self.assertIn('docker manifest inspect "${ROADTRIP_WEB_IMAGE}"', deploy)
        self.assertIn("_ROLLBACK_ACTIVE == 1", deploy[deploy.index("docker manifest inspect") - 200 : deploy.index("docker manifest inspect")])

    def test_reclaim_prunes_every_image_production_pulls(self) -> None:
        deploy = (ROOT / "scripts" / "deploy.sh").read_text()
        reclaim = (ROOT / "scripts" / "reclaim.sh").read_text()
        repositories = reclaim[reclaim.index('ROADTRIP_REPOSITORIES="') : reclaim.index('"', reclaim.index('ROADTRIP_REPOSITORIES="') + len('ROADTRIP_REPOSITORIES="'))].split()
        for image in re.findall(r'export ROADTRIP_[A-Z]+_IMAGE="(ghcr\.io/[^:"]+):', deploy):
            self.assertIn(image, repositories)
        self.assertIn("roadtrip/web", repositories)

    def test_the_web_image_is_labelled_for_reclaim(self) -> None:
        dockerfile = (ROOT / "Dockerfile").read_text()
        web_stage = dockerfile[dockerfile.index("AS web\n") :]
        self.assertIn('ca.floo.roadtrip.managed="true"', web_stage)
        self.assertIn('ca.floo.roadtrip.component="web"', web_stage)

    def test_web_health_checks_its_database_path(self) -> None:
        compose = yaml.safe_load((ROOT / "docker-compose.yml").read_text())
        self.assertIn("/api/pois/search", " ".join(compose["services"]["web"]["healthcheck"]["test"]))

    def test_the_drift_gates_run_on_the_files_they_guard(self) -> None:
        filters = path_filters()
        self.assertIn("backend/src/main/resources/db/migration/R__web_reader_grants.sql", filters["web"])
        self.assertIn("docs/web-app.md", filters["web"])
        self.assertIn("postgres-init/**", filters["scripts"])
        self.assertIn("caddy/**", filters["scripts"])
        self.assertIn("caddy/Caddyfile", filters["web"])

    def test_deploy_creates_the_web_reader_before_web_starts(self) -> None:
        deploy = (ROOT / "scripts" / "deploy.sh").read_text()
        backend_up = deploy.index("up -d --force-recreate backend")
        role_step = deploy.index("exec -T postgres bash /docker-entrypoint-initdb.d/20-web-reader.sh")
        everything_up = deploy.index('"${compose[@]}" up -d\n')
        self.assertLess(backend_up, role_step)
        self.assertLess(role_step, everything_up)

    def test_images_built_with_docker_build_publish_a_single_manifest(self) -> None:
        jobs = workflow("ci.yml")["jobs"]
        for job in ("companion-image", "data-image"):
            commands = job_commands(jobs[job])
            self.assertIn("docker build", commands, job)
            self.assertIn("--provenance=false", commands, job)
            self.assertIn("--sbom=false", commands, job)

    def test_companion_image_is_keyed_on_everything_its_build_reads(self) -> None:
        deploy_commands = job_commands(workflow("deploy.yml")["jobs"]["deploy"])
        self.assertIn(COMPANION_KEY_SCRIPT, deploy_commands)
        self.assertNotIn("git rev-parse HEAD:companion", deploy_commands)

        inputs = companion_key_inputs()
        copied = {
            PurePosixPath(source).parts[0]
            for line in (ROOT / "companion" / "Dockerfile").read_text().splitlines()
            if line.startswith("COPY ") and "--from" not in line
            for source in line.split()[1:-1]
        }
        self.assertTrue(copied)
        self.assertTrue(copied <= set(inputs), f"{copied - set(inputs)} copied but not in the key")

        steps = workflow("ci.yml")["jobs"]["changes"]["steps"]
        filters = yaml.safe_load(
            next(step for step in steps if step.get("id") == "filter")["with"]["filters"]
        )
        for path in inputs:
            watched = f"{path}/**" if (ROOT / path).is_dir() else path
            self.assertIn(watched, filters["companion_image"], f"{path} does not rebuild the image")
        self.assertIn(
            COMPANION_KEY_SCRIPT,
            filters["companion_image"],
            "editing the key script moves the tag but would build nothing",
        )

    def test_the_guard_on_the_companion_key_runs_on_every_change(self) -> None:
        jobs = workflow("ci.yml")["jobs"]
        guard = jobs["image-key-guard"]

        self.assertNotIn("if", guard)
        self.assertNotIn("needs", guard)
        self.assertIn("scripts.test_ci_image_builds", job_commands(guard))
        self.assertIn("image-key-guard", jobs["ci-passed"]["needs"])

    def test_the_schema_equivalence_gate_watches_both_schema_sources(self) -> None:
        jobs = workflow("ci.yml")["jobs"]
        gate = jobs["schema-equivalence"]

        filters = yaml.safe_load(
            next(
                step for step in jobs["changes"]["steps"] if step.get("id") == "filter"
            )["with"]["filters"]
        )
        watched = filters["schema_equivalence"]
        for path in (
            FLYWAY_MIGRATIONS,
            "packages",
            "scripts/schema-equivalence.mjs",
            SCHEMA_EQUIVALENCE_TEST,
        ):
            self.assertTrue(
                any(pattern.startswith(path) for pattern in watched),
                f"{path} does not run the schema equivalence gate",
            )

        self.assertIn("schema_equivalence", jobs["changes"]["outputs"])
        self.assertIn("schema_equivalence", gate["if"])
        self.assertIn("npm run build", job_commands(gate))
        self.assertIn(SCHEMA_EQUIVALENCE_TEST, job_commands(gate))
        self.assertIn(SCHEMA_REFERENCE_TEST, job_commands(gate))
        self.assertIn("schema-equivalence", jobs["ci-passed"]["needs"])
        self.assertTrue(
            any(pattern.startswith(SCHEMA_REFERENCE_TEST) for pattern in watched),
            f"{SCHEMA_REFERENCE_TEST} does not run the schema equivalence gate",
        )

    def test_the_importer_differential_gate_watches_every_tree_it_imports(self) -> None:
        jobs = workflow("ci.yml")["jobs"]
        gate = jobs["importer-differential"]
        watched = path_filters()["importer_differential"]
        prefixes = tuple(p.removesuffix("/**").removesuffix("/*") for p in watched)

        files = importer_kotlin_files()
        self.assertIn(f"{KOTLIN_PACKAGE_ROOT}/config/AppConfig.kt", files, "the closure lost a known two-hop file")
        for path in files:
            self.assertTrue(
                covered(path, prefixes),
                f"{path} is reached from {', '.join(IMPORTER_KOTLIN_SOURCES)} "
                "but no importer_differential filter entry covers it, so an edit "
                "there changes what the Kotlin importer writes without running the gate",
            )

        self.assertIn("importer_differential", jobs["changes"]["outputs"])
        self.assertIn("importer_differential", gate["if"])
        self.assertIn("node scripts/importer-differential.mjs", job_commands(gate))
        self.assertIn("importer-differential", jobs["ci-passed"]["needs"])

    def test_every_pinned_path_exists(self) -> None:
        tracked = tracked_paths()
        self.assertIn(IMPORTER_DIFFERENTIAL, tracked, "git ls-files returned nothing usable")

        for name in ("PROVENANCE_TREES", "PROVENANCE_FILES"):
            paths = js_path_list(name)
            self.assertGreater(len(paths), 1, f"{name} parsed to {len(paths)} path(s)")
            for path in paths:
                self.assertTrue(
                    path_exists(path, tracked),
                    f"{name} pins {path}, which is not in this branch. provenanceFor() hashes "
                    "every entry before any container starts, so one bad path fails every "
                    "differential run, the CI job and make importer-diff included",
                )

        watched = path_filters()["importer_differential"]
        for pattern in watched:
            if pattern.endswith("/**"):
                self.assertTrue(
                    path_exists(pattern[: -len("/**")], tracked),
                    f"the importer_differential filter watches {pattern}, which matches no tracked path",
                )
            elif "*" in pattern:
                self.assertTrue(
                    any(PurePosixPath(c).match(pattern) for c in tracked),
                    f"the importer_differential filter watches {pattern}, which matches no tracked file",
                )
            else:
                self.assertIn(
                    pattern,
                    tracked,
                    f"the importer_differential filter watches {pattern}, which is not a tracked file",
                )

    def test_the_cached_dump_provenance_covers_every_tree_it_imports(self) -> None:
        block = PROVENANCE_TREES_BLOCK.search((ROOT / IMPORTER_DIFFERENTIAL).read_text())
        self.assertIsNotNone(block, f"PROVENANCE_TREES not found in {IMPORTER_DIFFERENTIAL}")
        pinned = re.findall(r"'([^']+)'", block.group(1))
        self.assertIn(f"{KOTLIN_PACKAGE_ROOT}/service/etl", pinned, "the parse found no Kotlin trees")

        pinned = [*pinned, *js_path_list("PROVENANCE_FILES")]
        files = importer_kotlin_files()
        self.assertIn(f"{KOTLIN_PACKAGE_ROOT}/config/AppConfig.kt", files, "the closure lost a known two-hop file")
        for path in files:
            self.assertTrue(
                covered(path, pinned),
                f"{path} is reached from {', '.join(IMPORTER_KOTLIN_SOURCES)} but no "
                "PROVENANCE_TREES or PROVENANCE_FILES entry covers it, so --reference-dump would "
                "restore a dump taken before an edit there as if it were still valid",
            )

    def test_preview_check_reports_on_the_deployed_commit_whatever_happens(self) -> None:
        check = workflow("preview-check.yml")
        self.assertEqual(workflow_triggers(check), {"repository_dispatch": {"types": ["vercel.deployment.success"]}})
        self.assertEqual(check["permissions"].get("statuses"), "write")
        steps = check["jobs"]["preview"]["steps"]
        report = next(step for step in steps if step.get("name", "").startswith("Report"))
        self.assertTrue(report["if"].startswith("always()"), "a failed check must still mark the commit failed")
        self.assertIn("statuses/$SHA", report["run"])
        commands = job_commands(check["jobs"]["preview"])
        self.assertNotIn("npm ci", commands)
        self.assertNotRegex(commands, r"curl[^\n]*(-L\b|--location)", "a protection redirect must fail, not be followed")

    def test_make_importer_diff_caches_only_the_modes_that_can_restore_a_dump(self) -> None:
        def dry_run(*variables: str) -> list[str]:
            out = subprocess.run(
                ["make", "-n", "importer-diff", *variables], cwd=ROOT, capture_output=True, text=True, check=True
            ).stdout
            return [line for line in out.splitlines() if "importer-differential.mjs" in line]

        uncached = dry_run()
        self.assertEqual(len(uncached), 3, uncached)
        self.assertFalse(any("--reference-dump" in line for line in uncached), uncached)

        cached = dry_run("IMPORTER_DIFF_CACHE=cache")
        dumps = [re.search(r"--reference-dump (\S+)", line) for line in cached]
        self.assertIsNotNone(dumps[0], cached[0])
        self.assertIsNotNone(dumps[1], cached[1])
        self.assertNotEqual(dumps[0].group(1), dumps[1].group(1), "two pass counts cannot share one dump")
        self.assertIn("--prove-update-keys", cached[2])
        self.assertIsNone(dumps[2], "--prove-update-keys refuses --reference-dump, so the gate could never pass")

    def test_make_test_runs_the_gate_after_the_build_it_consumes(self) -> None:
        recipe = make_recipe("test")

        def position(command: str) -> int:
            self.assertIn(command, recipe)
            return recipe.index(command)

        build = position("npm run build")
        gate = position(f"node --test {SCHEMA_EQUIVALENCE_TEST}")
        self.assertLess(build, gate, "the gate imports packages/db/dist, so the build comes first")
        self.assertLess(build, position(f"node --test {SCHEMA_REFERENCE_TEST}"))
        for command in PACKAGE_TYPECHECKS:
            self.assertLess(build, position(command))

    def test_package_typechecks_run_in_ci(self) -> None:
        commands = job_commands(workflow("ci.yml")["jobs"]["package-tests"])
        for command in PACKAGE_TYPECHECKS:
            self.assertIn(command, commands)
        self.assertLess(commands.index("npm run build"), commands.index(PACKAGE_TYPECHECKS[0]))

    def test_every_job_that_builds_watches_the_build_driver(self) -> None:
        filters = path_filters()
        for name in ("smoke", "frontend", "schema_equivalence", "package_tests"):
            self.assertIn(BUILD_WORKSPACES, filters[name], f"{name} does not rerun on {BUILD_WORKSPACES}")

    def test_companion_key_moves_with_each_input_and_only_those(self) -> None:
        inputs = companion_key_inputs()

        def git(repo: Path, *args: str) -> str:
            return subprocess.run(
                ["git", *args], cwd=repo, check=True, capture_output=True, text=True
            ).stdout.strip()

        def run_key(cwd: Path) -> subprocess.CompletedProcess:
            return subprocess.run(
                ["bash", str(ROOT / COMPANION_KEY_SCRIPT)],
                cwd=cwd, capture_output=True, text=True,
            )

        def key(cwd: Path) -> str:
            result = run_key(cwd)
            self.assertEqual(0, result.returncode, result.stderr)
            return result.stdout.strip()

        def edit(repo: Path, path: str) -> None:
            target = repo / (path if "." in path else f"{path}/file.txt")
            target.parent.mkdir(exist_ok=True)
            target.write_text(target.read_text() + "x" if target.exists() else "x")
            git(repo, "add", "-A")
            git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", path)

        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            git(repo, "init", "-q")
            for path in (*inputs, "frontend"):
                edit(repo, path)
            baseline = key(repo)
            self.assertRegex(baseline, r"^[0-9a-f]{40}$")

            edit(repo, "frontend")
            self.assertEqual(baseline, key(repo))
            self.assertEqual(baseline, key(repo / "frontend"))

            seen = {baseline}
            for path in inputs:
                edit(repo, path)
                moved = key(repo)
                self.assertNotIn(moved, seen, f"editing {path} did not move the key")
                seen.add(moved)

            git(repo, "rm", "-q", "package-lock.json")
            git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "rename")
            missing = run_key(repo)
            self.assertNotEqual(0, missing.returncode)
            self.assertEqual("", missing.stdout.strip())
            self.assertIn("expected 3 tree entries", missing.stderr)

    def test_production_only_pulls_images(self) -> None:
        deploy = (ROOT / "scripts" / "deploy.sh").read_text()
        self.assertNotIn("docker build", deploy)
        self.assertNotIn("docker compose build", deploy)
        self.assertIn('pull "${pulled[@]}"', deploy)
        self.assertIn("local -a pulled=(backend recgov-companion)", deploy)
        self.assertIn("pulled+=(web)", deploy)

    def test_prod_and_sandbox_share_one_runtime_release(self) -> None:
        # Prod installs the release straight from its workflow. The sandbox path
        # does it inside the sandbox action, which has to join the tailnet first.
        for path in (
            ROOT / ".github" / "workflows" / "deploy.yml",
            ROOT / ".github" / "actions" / "sandbox" / "action.yml",
        ):
            self.assertIn(
                "uses: ./.github/actions/install-release", path.read_text(), str(path)
            )

        for retired in ("sandbox_up.sh", "sandbox_down.sh", "ensure_data_volume.sh"):
            self.assertFalse((ROOT / "scripts" / retired).exists())

        entries = (ROOT / "deploy" / "release-manifest.txt").read_text().splitlines()
        self.assertTrue(entries)
        for entry in entries:
            self.assertTrue((ROOT / entry).exists(), entry)

    def test_the_release_ships_every_file_its_scripts_reach_for(self) -> None:
        # The manifest existing in the repo is only half the contract: the
        # deploy host runs these scripts out of the unpacked archive, so a
        # sibling the manifest forgets fails there and nowhere else. That is
        # exactly how `scripts/reclaim.sh` -- added to deploy.sh's preflight
        # but never to the manifest -- turned every prod deploy red for nine
        # days while CI stayed green.
        entries = (ROOT / "deploy" / "release-manifest.txt").read_text().splitlines()
        shipped = set(entries)

        def is_shipped(path: str) -> bool:
            # An entry may be a directory (grafana, postgres-init), which
            # ships everything under it.
            parts = PurePosixPath(path).parts
            return any("/".join(parts[:depth]) in shipped for depth in range(1, len(parts) + 1))

        for entry in entries:
            source = ROOT / entry
            if source.suffix != ".sh":
                continue
            for variable, target in SHELL_PATH_REFERENCE.findall(source.read_text()):
                prefix = SHELL_REFERENCE_ROOTS[variable]
                referenced = f"{prefix}/{target}" if prefix else target
                # Directories the scripts create at runtime (the sandbox Caddy
                # snippet dir) and `..`-style path arithmetic name nothing the
                # release has to carry.
                if not (ROOT / referenced).is_file():
                    continue
                self.assertTrue(
                    is_shipped(referenced),
                    f"{entry} runs {referenced} on the deploy host, "
                    "but deploy/release-manifest.txt does not ship it",
                )

    def test_tilt_builds_the_backend_without_the_production_frontend_stage(self) -> None:
        dockerfile = (ROOT / "Dockerfile").read_text()
        tiltfile = (ROOT / "Tiltfile").read_text()

        self.assertIn("FROM backend-base AS backend-local", dockerfile)
        self.assertIn("FROM backend-base AS backend", dockerfile)
        self.assertIn("target='backend-local'", tiltfile)

    def test_sandbox_redeploys_active_pr_sandbox_on_new_commits(self) -> None:
        sandbox = workflow("sandbox.yml")
        triggers = workflow_triggers(sandbox)
        sandbox_job = sandbox["jobs"]["sandbox"]
        resolve_script = step_by_name(sandbox_job, "Resolve request")["with"]["script"]

        self.assertEqual(["closed", "synchronize"], triggers["pull_request"]["types"])
        self.assertNotIn("teardown-on-close", sandbox["jobs"])
        self.assertIn("github.event.action == 'synchronize'", sandbox_job["if"])
        self.assertIn("github.event.action == 'closed'", sandbox_job["if"])
        self.assertIn("context.eventName === 'pull_request'", resolve_script)
        self.assertIn("context.payload.action === 'closed'", resolve_script)
        self.assertIn("requireExisting: 'true'", resolve_script)
        self.assertIn("reason: 'this PR closed'", resolve_script)
        self.assertIn("sandbox-status:pr${pr.number}", resolve_script)
        self.assertIn("SANDBOX_TORN_DOWN_HEADING", resolve_script)
        self.assertIn("SANDBOX_TEARING_DOWN_HEADING", resolve_script)
        self.assertIn("SANDBOX_TEARDOWN_FAILED_HEADING", resolve_script)
        self.assertIn("setRequest({ operation: 'skip'", resolve_script)
        self.assertIn("operation: 'start'", resolve_script)

    def test_deploy_resolves_every_release_image_before_touching_the_host(self) -> None:
        # A workflow_dispatch deploy bypasses the `workflow_run` conclusion
        # guard, so nothing else establishes that CI has published the tags
        # the host is about to pull. Without this the failure lands on the
        # host as a containerd "not found", after the data image has been
        # pulled and a 30-minute volume guard parked.
        deploy = workflow("deploy.yml")
        job = deploy["jobs"]["deploy"]
        step_names = [step.get("name", "") for step in job["steps"]]
        await_step = step_by_name(job, "Await the release images")

        self.assertEqual("read", deploy["permissions"]["packages"])
        self.assertEqual("./.github/actions/await-images", await_step["uses"])
        self.assertLess(
            step_names.index("Await the release images"),
            step_names.index("Reach the deploy host"),
        )
        images = await_step["with"]["images"]
        for repository, sha in (
            ("backend", "env.DEPLOY_SHA"),
            ("recgov-companion", "env.DEPLOY_COMPANION_SHA"),
            ("data", "env.DEPLOY_DATA_SHA"),
        ):
            self.assertIn(f"/{repository}:${{{{ {sha} }}}}", images)

        # Pinning today's three would let a fourth image be added to the prod
        # stack and pulled on the host without ever being preflighted -- the
        # guard would rot open silently, into exactly the containerd "not
        # found" it exists to prevent. So derive the set deploy.sh actually
        # pulls and require the await list to cover it.
        pulled = set(
            re.findall(
                r'export ROADTRIP_[A-Z]+_IMAGE="ghcr\.io/[^/]+/[^/]+/([^:"]+):',
                (ROOT / "scripts" / "deploy.sh").read_text(),
            )
        )
        self.assertTrue(pulled, "found no prod image exports in deploy.sh")
        for repository in sorted(pulled):
            self.assertIn(
                f"/{repository}:",
                images,
                f"deploy.sh pulls {repository} on the host, but the deploy "
                "workflow does not preflight it",
            )

        # The deploy queue is serialized, so this job must not inherit the
        # sandbox's 20-minute patience.
        budget = int(await_step["with"]["max-attempts"]) * int(
            await_step["with"]["wait-seconds"]
        )
        self.assertLessEqual(budget, 300, "the image wait must stay well inside the job budget")

    def test_a_manual_deploy_must_still_establish_that_ci_passed(self) -> None:
        # The `workflow_run` trigger carries the CI-succeeded precondition;
        # workflow_dispatch bypasses it. Awaiting the images does not stand in
        # for it, because the image jobs do not depend on the tests -- so a
        # dispatch of a red commit finds every tag present.
        deploy = workflow("deploy.yml")
        job = deploy["jobs"]["deploy"]
        triggers = workflow_triggers(deploy)
        gate = step_by_name(job, "Require a green CI run for this commit")
        step_names = [step.get("name", "") for step in job["steps"]]

        self.assertIn("force", triggers["workflow_dispatch"]["inputs"])
        self.assertIn("workflow_dispatch", gate["if"])
        self.assertIn("!inputs.force", gate["if"])
        # Both halves: the run concluded success, and the ref is production's.
        self.assertIn("conclusion", gate["run"])
        self.assertIn("refs/heads/$DEPLOY_BRANCH", gate["run"])
        # Before anything reaches the host or the registry.
        for later in ("Await the release images", "Reach the deploy host"):
            self.assertLess(step_names.index(gate["name"]), step_names.index(later))

    def test_the_production_branch_is_named_once(self) -> None:
        # deploy.sh stamped a `master` literal into build-info and the health
        # gate asserted another `master` literal against it, so the check
        # compared a constant with itself and could not fail.
        deploy = workflow("deploy.yml")
        body = (ROOT / ".github" / "workflows" / "deploy.yml").read_text()

        self.assertEqual("master", deploy["env"]["DEPLOY_BRANCH"])
        # The `on:` block cannot read env, so that one literal stays.
        self.assertEqual(
            1,
            len([line for line in body.splitlines()
                 if "master" in line and "#" not in line and "branches:" not in line]),
            "the production branch should be defined once, as DEPLOY_BRANCH",
        )

    def test_smoke_does_not_serialize_behind_the_unit_tests(self) -> None:
        # Smoke rebuilds the fat jar and frontend itself, so the edge bought
        # nothing but a spared runner -- at the cost of the two jobs' durations
        # summing on every push before deploy could start.
        ci = workflow("ci.yml")
        smoke = ci["jobs"]["smoke"]
        aggregate = ci["jobs"]["ci-passed"]

        self.assertNotIn("backend-tests", smoke["needs"])
        self.assertNotIn("backend-tests", smoke["if"])
        # The safety property lives here, and still covers both.
        for job in ("smoke", "backend-tests"):
            self.assertIn(job, aggregate["needs"])

    def test_the_deploy_does_not_restart_what_it_just_recreated(self) -> None:
        # Compose resolves the observability bind mounts against the release
        # directory, which is keyed by SHA, so `up -d` has already recreated
        # them; restarting again only resets the health start periods that
        # `--wait` and the telemetry poll then wait through.
        deploy_script = (ROOT / "scripts" / "deploy.sh").read_text()

        self.assertNotIn("restart grafana", deploy_script)

    def test_the_image_wait_is_shared_rather_than_copied(self) -> None:
        # Two call sites block on GHCR tags; the poll loop lives in one place
        # so a fix to either reaches both.
        sandbox_action = (ROOT / ".github" / "actions" / "sandbox" / "action.yml").read_text()
        deploy = (ROOT / ".github" / "workflows" / "deploy.yml").read_text()

        self.assertIn("docker manifest inspect", AWAIT_IMAGES_ACTION)
        for caller in (sandbox_action, deploy):
            self.assertIn("uses: ./.github/actions/await-images", caller)
            self.assertNotIn("docker manifest inspect", caller)

    def test_sandbox_status_comment_updates_before_image_wait(self) -> None:
        sandbox = workflow("sandbox.yml")
        sweep = workflow("sandbox-sweep.yml")
        sandbox_action = (ROOT / ".github" / "actions" / "sandbox" / "action.yml").read_text()
        sandbox_job = sandbox["jobs"]["sandbox"]
        step_names = [step["name"] for step in sandbox_job["steps"]]
        status_step = step_by_name(sandbox_job, "Status workflow started")
        status_script = status_step["with"]["script"]

        self.assertEqual("write", sandbox["permissions"]["issues"])
        self.assertEqual("write", sweep["permissions"]["issues"])
        self.assertLess(
            step_names.index("Status workflow started"),
            step_names.index("Check out commit to deploy"),
        )
        self.assertLess(
            step_names.index("Status workflow started"),
            step_names.index("Sandbox"),
        )
        self.assertIn("workflow started", status_script)
        self.assertIn("SANDBOX_TRIGGER", status_step["env"])
        self.assertNotIn("SANDBOX_PR_NUMBER", status_step["env"])
        self.assertNotIn("SANDBOX_PR_NUMBER", sandbox_action)
        # The GHCR login moved into the shared await-images action; the
        # sandbox reaches it through that action rather than inlining it.
        self.assertIn("uses: ./.github/actions/await-images", sandbox_action)
        self.assertIn("Log in to GHCR", AWAIT_IMAGES_ACTION)
        self.assertIn("Wait for GHCR images", sandbox_action)
        self.assertLess(
            sandbox_action.index("- name: Wait for GHCR images"),
            sandbox_action.index("- name: Start sandbox"),
        )
        self.assertIn("not creating one", sandbox_action)


if __name__ == "__main__":
    unittest.main()
