#!/usr/bin/env python3
"""SOPS-backed secrets for the roadtrip stack.

The single source of truth is ``secrets/secrets.enc.env`` — a dotenv file whose
values are age-encrypted and which is committed to the repo. Every consumer
(Docker Compose ``env_file``, the Tiltfile, host-side fetchers) still reads a
plaintext ``.env``, so this script's job is to *materialize* that file from the
encrypted one. ``make run`` and ``tilt up`` do it automatically; nobody has to
remember a sync step, and the host ``.env`` can no longer silently drift from
what's committed.

Layout::

    secrets/secrets.enc.env   committed, encrypted, the source of truth
    .env                      generated, gitignored, what Compose reads
    .env.local                optional, gitignored, host-only overrides

``.env.local`` exists for values that are genuinely per-host and not secret —
a different ``PROMETHEUS_RETENTION`` on the mini, say. It is merged over the
decrypted values by key, so the generated ``.env`` never has duplicate keys and
does not depend on any consumer's last-wins/first-wins parsing.

Commands: init, import, edit, materialize, check, recipients, rotate.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SECRETS_FILE = ROOT / "secrets" / "secrets.enc.env"
ENV_FILE = ROOT / ".env"
ENV_LOCAL_FILE = ROOT / ".env.local"
ENV_EXAMPLE_FILE = ROOT / ".env.example"
SOPS_CONFIG = ROOT / ".sops.yaml"

# One key path on every host, macOS and Linux alike.
#
# Left to itself sops resolves this with Go's os.UserConfigDir(), which differs
# per platform (~/Library/Application Support on macOS, ~/.config on Linux).
# That difference has no upside here and real downside: runbooks and backup
# instructions stop being copy-pasteable, and a key written to the "other"
# platform's path is invisible to sops with an error that names only the
# environment variables it checked — indistinguishable from having no key.
#
# So we pin the location and pass it to sops explicitly on every invocation
# (see run_sops). SOPS_AGE_KEY_FILE still wins if it's already set, which is
# how CI supplies a key.
AGE_KEY_FILE = Path(
    os.environ.get(
        "SOPS_AGE_KEY_FILE",
        Path.home() / ".config" / "sops" / "age" / "keys.txt",
    )
)

# Where sops would have looked on its own, and where earlier versions of this
# script put the key on macOS. A key sitting in one of these is adopted by
# `init` rather than generating a second identity on top of it — the first one
# is probably already a vault recipient.
ADOPTABLE_AGE_KEY_FILES = [
    path
    for path in (
        Path.home() / "Library" / "Application Support" / "sops" / "age" / "keys.txt",
        Path.home() / ".config" / "sops" / "age" / "keys.txt",
        Path(os.environ["XDG_CONFIG_HOME"]) / "sops" / "age" / "keys.txt"
        if os.environ.get("XDG_CONFIG_HOME")
        else None,
    )
    if path is not None and path != AGE_KEY_FILE
]


def misplaced_age_key() -> Path | None:
    """An identity somewhere other than the pinned path, when that path is empty."""
    if AGE_KEY_FILE.exists():
        return None
    return next((path for path in ADOPTABLE_AGE_KEY_FILES if path.exists()), None)


def sops_native_key_path() -> Path:
    """Where a bare `sops` invocation looks, absent SOPS_AGE_KEY_FILE."""
    if sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support"
    elif sys.platform == "win32":
        base = Path(os.environ.get("AppData", Path.home() / "AppData" / "Roaming"))
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return base / "sops" / "age" / "keys.txt"


def link_native_key_path() -> Path | None:
    """Point sops' native lookup at the pinned key, so bare `sops` works too.

    Everything in this repo goes through run_sops, which passes the pinned path
    explicitly — but people do run `sops -d` by hand. Rather than make them
    export a variable, leave a symlink where sops would look anyway. Returns
    the link if one was created.

    Never replaces a real file: an existing identity there is somebody's
    deliberate setup, and clobbering it could lock them out of another vault.
    """
    native = sops_native_key_path()
    if native == AGE_KEY_FILE or not AGE_KEY_FILE.exists():
        return None
    if native.is_symlink():
        if native.resolve() == AGE_KEY_FILE.resolve():
            return None
        return None
    if native.exists():
        return None
    native.parent.mkdir(parents=True, exist_ok=True)
    native.symlink_to(AGE_KEY_FILE)
    return native


# secrets.enc.env is a dotenv whose values happen to look like ENC[...]. sops
# can infer that from the .env suffix, but being explicit means a rename can't
# silently switch it to YAML parsing and mangle a value containing a colon.
SOPS_FORMAT_ARGS = ["--input-type", "dotenv", "--output-type", "dotenv"]

# Metadata keys sops appends to an encrypted dotenv. Excluded from the key
# diffing in `check`, since they aren't application config.
SOPS_METADATA_PREFIX = "sops_"

# Marker on the first line of a generated .env. Its absence means the file was
# hand-written, and materialize refuses to clobber it without --force.
GENERATED_MARKER = "# GENERATED by scripts/manage_secrets.py — do not edit."

ENV_HEADER = f"""{GENERATED_MARKER}
# Source of truth: secrets/secrets.enc.env  (encrypted, committed)
# Change a secret:  make secrets-edit
# Host-only, non-secret overrides: .env.local (merged over the decrypted values)
"""

# Values are secret; a generated .env should never be group- or world-readable.
ENV_FILE_MODE = 0o600
AGE_KEY_FILE_MODE = 0o600


class SecretsError(RuntimeError):
    """A failure with a message already written for a human."""


# --------------------------------------------------------------------------
# dotenv handling
#
# Deliberately dumb: split on the first `=` and keep the remainder byte-for-
# byte. Cookie-header values contain `=`, `;` and quotes, and Compose passes
# the raw remainder through, so any "helpful" unquoting here would corrupt it.
# --------------------------------------------------------------------------


def parse_dotenv(text: str) -> list[tuple[str, str]]:
    """Return ``(key, raw_line)`` pairs in file order, skipping blanks/comments."""
    entries: list[tuple[str, str]] = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key = stripped.split("=", 1)[0].strip()
        if key:
            entries.append((key, stripped))
    return entries


def merge_dotenv(
    base: list[tuple[str, str]],
    overrides: list[tuple[str, str]],
) -> list[tuple[str, str]]:
    """Overlay ``overrides`` onto ``base`` by key, in place, appending new keys."""
    remaining = dict(overrides)
    merged = [(key, remaining.pop(key, raw)) for key, raw in base]
    merged.extend(remaining.items())
    return merged


def keys_in(text: str) -> set[str]:
    """Application keys in a dotenv, excluding sops' own metadata."""
    return {
        key
        for key, _ in parse_dotenv(text)
        if not key.startswith(SOPS_METADATA_PREFIX)
    }


def keys_of(path: Path) -> set[str]:
    return keys_in(path.read_text()) if path.exists() else set()


# --------------------------------------------------------------------------
# sops plumbing
# --------------------------------------------------------------------------


def require_tools(*names: str) -> None:
    missing = [name for name in names if shutil.which(name) is None]
    if missing:
        raise SecretsError(
            f"missing required tool(s): {', '.join(missing)}\n"
            f"Install with: brew install {' '.join(missing)}  (or `make install`)"
        )


def run_sops(args: list[str], *, capture: bool = True) -> str:
    require_tools("sops")
    # Hand sops the pinned key path rather than letting it guess per platform.
    # This is what makes AGE_KEY_FILE authoritative everywhere; without it, sops
    # would look somewhere else entirely on macOS. SOPS_AGE_KEY takes priority
    # inside sops when set (CI passes the key by value), so this doesn't
    # override that path.
    env = {**os.environ, "SOPS_AGE_KEY_FILE": str(AGE_KEY_FILE)}
    proc = subprocess.run(
        ["sops", *args],
        cwd=ROOT,
        capture_output=capture,
        text=True,
        env=env,
    )
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip() if capture else ""
        # sops' "did not find keys in locations ..." lists only the env-var
        # paths it consulted, never the per-platform default, so a key in the
        # wrong directory looks identical to no key at all. Name the real cause.
        stranded = misplaced_age_key()
        hint = (
            f"\n\nhint: an age identity exists at {stranded}, but this repo keeps"
            f"\n      it at {AGE_KEY_FILE}. Adopt it with:"
            "\n          make secrets-init"
            if stranded is not None
            else ""
        )
        raise SecretsError(f"sops failed ({' '.join(args)})\n{detail}{hint}")
    return proc.stdout if capture else ""


def decrypt() -> str:
    if not SECRETS_FILE.exists():
        raise SecretsError(
            f"{SECRETS_FILE.relative_to(ROOT)} does not exist.\n"
            "First-time setup: `make secrets-init`, then `make secrets-import`."
        )
    plaintext = run_sops(["--decrypt", *SOPS_FORMAT_ARGS, str(SECRETS_FILE)])
    if not parse_dotenv(plaintext):
        raise SecretsError(
            f"{SECRETS_FILE.relative_to(ROOT)} decrypted to nothing usable — "
            "refusing to overwrite .env with an empty file."
        )
    return plaintext


def write_atomic(path: Path, content: str, mode: int) -> None:
    tmp = path.with_name(f"{path.name}.tmp.{os.getpid()}")
    tmp.write_text(content)
    os.chmod(tmp, mode)
    os.replace(tmp, path)


# --------------------------------------------------------------------------
# commands
# --------------------------------------------------------------------------


def cmd_init(_args: argparse.Namespace) -> int:
    """Create this host's age identity if it has none, and print its public key."""
    require_tools("age-keygen")
    stranded = misplaced_age_key()
    if stranded is not None:
        # Relocate rather than generate. A second identity here would leave the
        # first one — the one already listed as a vault recipient — orphaned,
        # and the symptom (decrypt fails, key "exists") is thoroughly confusing.
        AGE_KEY_FILE.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(stranded), str(AGE_KEY_FILE))
        os.chmod(AGE_KEY_FILE, AGE_KEY_FILE_MODE)
        print(f"adopted age identity {stranded}")
        print(f"                  -> {AGE_KEY_FILE}")
        print("(one path on every host, so runbooks and backups match)")
    elif AGE_KEY_FILE.exists():
        print(f"age identity already present at {AGE_KEY_FILE}")
    else:
        AGE_KEY_FILE.parent.mkdir(parents=True, exist_ok=True)
        proc = subprocess.run(
            ["age-keygen", "-o", str(AGE_KEY_FILE)],
            capture_output=True,
            text=True,
        )
        if proc.returncode != 0:
            raise SecretsError(f"age-keygen failed:\n{proc.stderr.strip()}")
        os.chmod(AGE_KEY_FILE, AGE_KEY_FILE_MODE)
        print(f"created age identity at {AGE_KEY_FILE}")

    public_key = next(
        (
            line.split(":", 1)[1].strip()
            for line in AGE_KEY_FILE.read_text().splitlines()
            if line.startswith("# public key:")
        ),
        None,
    )
    if public_key is None:
        raise SecretsError(f"no public key found in {AGE_KEY_FILE}")

    linked = link_native_key_path()
    if linked is not None:
        print(f"linked {linked} -> the same key")
        print("(so a bare `sops -d` works too, without exporting anything)")

    print()
    print("Public key for this host:")
    print(f"  {public_key}")
    print()
    print(f"Next: add it to the age recipients in {SOPS_CONFIG.relative_to(ROOT)},")
    print("removing any placeholder line you don't have a key for yet — sops")
    print("cannot encrypt to a recipient that isn't a real public key. Then:")
    print()
    if SECRETS_FILE.exists():
        # A vault already exists, so this is a host being added to an existing
        # set: an existing holder re-wraps the data key for the new recipient.
        print("  make secrets-rotate   (from a host that can already decrypt)")
    else:
        # First-ever setup. rotate would fail here — there's no vault to re-wrap.
        print("  make secrets-import   (creates the vault from your current .env)")
    print()
    print("This identity is the private half — it is not in the repo and cannot")
    print("be recovered. Back up the file above if losing this host would lock")
    print("you out of every secret.")
    return 0


def main_checkout_env() -> Path | None:
    """The main clone's .env, when ROOT is a linked git worktree.

    Worktrees don't carry gitignored files, so running the migration from one
    finds no .env even though the real one is sitting in the main checkout.
    """
    proc = subprocess.run(
        ["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        return None
    # Resolved to match ROOT, which is resolved — otherwise a symlinked path
    # (macOS /var -> /private/var) makes the "same file?" check below miss.
    candidate = (Path(proc.stdout.strip()).parent / ".env").resolve()
    return candidate if candidate != ENV_FILE.resolve() and candidate.exists() else None


def resolve_import_source(explicit: str | None) -> Path:
    if explicit:
        source = Path(explicit).expanduser()
        if not source.exists():
            raise SecretsError(f"{source} does not exist — nothing to import.")
        return source
    if ENV_FILE.exists():
        return ENV_FILE
    from_main = main_checkout_env()
    if from_main is not None:
        print(f"note: no .env here (this is a git worktree); using {from_main}")
        return from_main
    raise SecretsError(
        f"no plaintext .env found to import.\n"
        f"Looked in: {ENV_FILE}\n"
        "Point at one explicitly with: make secrets-import SOURCE=/path/to/.env"
    )


def active_placeholders(text: str) -> list[str]:
    """Placeholder recipient lines that aren't commented out.

    A commented-out placeholder is inert — sops ignores it — so it shouldn't
    block the migration. Only a live one would be handed to age as a recipient,
    where it fails as an invalid public key.
    """
    return [
        line.strip()
        for line in text.splitlines()
        if "PLACEHOLDER" in line and not line.strip().startswith("#")
    ]


def cmd_import(args: argparse.Namespace) -> int:
    """One-time migration: encrypt an existing plaintext .env into the vault."""
    source = resolve_import_source(args.source)
    if SECRETS_FILE.exists() and not args.force:
        raise SecretsError(
            f"{SECRETS_FILE.relative_to(ROOT)} already exists. Use `make secrets-edit` "
            "to change values, or pass --force to replace the whole vault."
        )
    stale = active_placeholders(SOPS_CONFIG.read_text())
    if stale:
        raise SecretsError(
            f"{SOPS_CONFIG.relative_to(ROOT)} still has placeholder age recipients:\n"
            + "\n".join(f"    {line}" for line in stale)
            + "\nsops cannot encrypt to a recipient that isn't a real public key.\n"
            "Delete or comment out any host you don't have a key for yet — you can\n"
            "add it later with `make secrets-init` on that host, then "
            "`make secrets-rotate`."
        )

    SECRETS_FILE.parent.mkdir(parents=True, exist_ok=True)
    # sops matches creation rules against the input path, but the input here is
    # wherever the plaintext .env happens to live. Override the name so the
    # secrets/ rule (and therefore the right age recipients) is what applies.
    ciphertext = run_sops(
        [
            "--encrypt",
            *SOPS_FORMAT_ARGS,
            "--filename-override",
            str(SECRETS_FILE.relative_to(ROOT)),
            str(source),
        ]
    )
    SECRETS_FILE.write_text(ciphertext)
    print(f"encrypted {source} -> {SECRETS_FILE.relative_to(ROOT)}")
    print(f"imported {len(keys_of(SECRETS_FILE))} keys; commit the encrypted file.")
    return 0


def cmd_edit(_args: argparse.Namespace) -> int:
    """Open the vault in $EDITOR, decrypted in a temp file, re-encrypted on save."""
    if not SECRETS_FILE.exists():
        raise SecretsError(
            f"{SECRETS_FILE.relative_to(ROOT)} does not exist. "
            "Run `make secrets-import` to create it from your current .env."
        )
    run_sops(["edit", str(SECRETS_FILE)], capture=False)
    print("vault updated — commit secrets/secrets.enc.env, then `make secrets`.")
    return 0


def cmd_materialize(args: argparse.Namespace) -> int:
    """Decrypt the vault (+ .env.local) into .env. Idempotent and quiet."""
    if (
        ENV_FILE.exists()
        and not ENV_FILE.read_text().startswith(GENERATED_MARKER)
        and not args.force
    ):
        raise SecretsError(
            ".env exists but wasn't generated by this script — refusing to "
            "overwrite it.\nIf it holds the real secrets, import it first:\n"
            "    make secrets-import\n"
            "If it's stale, discard it:\n"
            "    make secrets-force"
        )

    merged = merge_dotenv(
        [
            (key, raw)
            for key, raw in parse_dotenv(decrypt())
            if not key.startswith(SOPS_METADATA_PREFIX)
        ],
        parse_dotenv(ENV_LOCAL_FILE.read_text()) if ENV_LOCAL_FILE.exists() else [],
    )
    content = ENV_HEADER + "\n" + "\n".join(raw for _, raw in merged) + "\n"

    # Skip the write when nothing changed: Tilt and Compose both watch .env,
    # and a fresh mtime on every `tilt up` would churn the stack for nothing.
    if ENV_FILE.exists() and ENV_FILE.read_text() == content:
        if args.verbose:
            print(f".env already current ({len(merged)} keys)")
        return 0

    write_atomic(ENV_FILE, content, ENV_FILE_MODE)
    print(f"wrote .env from {SECRETS_FILE.relative_to(ROOT)} ({len(merged)} keys)")
    return 0


def read_staged(path: Path) -> str:
    """Return a path's content as staged in the git index."""
    relative = path.relative_to(ROOT)
    proc = subprocess.run(
        ["git", "show", f":{relative}"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        raise SecretsError(f"{relative} is not staged:\n{proc.stderr.strip()}")
    return proc.stdout


def cmd_check(args: argparse.Namespace) -> int:
    """Validate the vault. Runs in CI, where no age identity is available."""
    errors: list[str] = []
    warnings: list[str] = []

    # The pre-commit hook passes --staged: what matters at commit time is the
    # blob about to enter history, which isn't necessarily what's on disk.
    if getattr(args, "staged", False):
        vault_text = read_staged(SECRETS_FILE)
    elif SECRETS_FILE.exists():
        vault_text = SECRETS_FILE.read_text()
    else:
        raise SecretsError(f"{SECRETS_FILE.relative_to(ROOT)} is missing.")

    entries = parse_dotenv(vault_text)
    by_key = dict(entries)

    if f"{SOPS_METADATA_PREFIX}version" not in by_key:
        errors.append(
            f"{SECRETS_FILE.relative_to(ROOT)} has no sops metadata — it is not "
            "an encrypted file. Do not commit it."
        )

    # Every application value must be ciphertext. Catches the failure that
    # actually matters: a hand-edited vault committed in the clear.
    for key, raw in entries:
        if key.startswith(SOPS_METADATA_PREFIX):
            continue
        value = raw.split("=", 1)[1]
        if not value.startswith("ENC["):
            errors.append(f"{key} is not encrypted in {SECRETS_FILE.relative_to(ROOT)}")

    for line in active_placeholders(SOPS_CONFIG.read_text()):
        errors.append(
            f"{SOPS_CONFIG.relative_to(ROOT)} still has a placeholder recipient: {line}"
        )

    tracked = subprocess.run(
        ["git", "ls-files", "--error-unmatch", ".env", ".env.local"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    for path in tracked.stdout.split():
        errors.append(f"{path} is tracked by git — plaintext secrets must not be committed.")

    vault_keys = keys_in(vault_text)
    documented = keys_of(ENV_EXAMPLE_FILE)
    for key in sorted(vault_keys - documented):
        errors.append(f"{key} is in the vault but undocumented in .env.example")
    for key in sorted(documented - vault_keys):
        warnings.append(f"{key} is documented in .env.example but unset in the vault")

    # Errors first, and warnings withheld when there are any: a real failure
    # shouldn't scroll off the top behind twenty "documented but unset" notes.
    for error in errors:
        print(f"error: {error}", file=sys.stderr)
    if errors:
        return 1
    for warning in warnings:
        print(f"warn: {warning}")
    print(f"secrets check passed ({len(vault_keys)} keys, all encrypted)")
    return 0


def cmd_recipients(_args: argparse.Namespace) -> int:
    """Show which age keys can currently decrypt the vault."""
    if not SECRETS_FILE.exists():
        raise SecretsError(f"{SECRETS_FILE.relative_to(ROOT)} is missing.")
    recipients = [
        raw.split("=", 1)[1]
        for key, raw in parse_dotenv(SECRETS_FILE.read_text())
        if key.startswith(f"{SOPS_METADATA_PREFIX}age") and key.endswith("recipient")
    ]
    if not recipients:
        raise SecretsError("no age recipients found in the vault metadata.")
    print(f"{len(recipients)} age recipient(s) can decrypt the vault:")
    for recipient in recipients:
        print(f"  {recipient}")
    print()
    print(f"Intended set is declared in {SOPS_CONFIG.relative_to(ROOT)}; if they")
    print("disagree, run `make secrets-rotate`.")
    return 0


def cmd_rotate(_args: argparse.Namespace) -> int:
    """Re-encrypt the vault's data key for the current .sops.yaml recipients."""
    if not SECRETS_FILE.exists():
        # Distinct from "something went wrong": rotate re-wraps an existing
        # vault, so on first-ever setup the command you want is import.
        raise SecretsError(
            f"{SECRETS_FILE.relative_to(ROOT)} does not exist yet, so there is "
            "nothing to re-encrypt.\nFirst-time setup creates the vault instead:"
            "\n    make secrets-import"
        )
    run_sops(["updatekeys", "--yes", str(SECRETS_FILE)], capture=False)
    print("recipients updated — commit secrets/secrets.enc.env.")
    print()
    print("Note: this re-wraps the data key. If you removed a recipient, that")
    print("holder still knows the current values — rotate the credentials")
    print("themselves at the provider too.")
    return 0


COMMANDS = {
    "init": (cmd_init, "create this host's age identity and print its public key"),
    "import": (cmd_import, "encrypt an existing plaintext .env into the vault"),
    "edit": (cmd_edit, "open the vault in $EDITOR"),
    "materialize": (cmd_materialize, "decrypt the vault into .env"),
    "check": (cmd_check, "validate the vault (no age identity needed)"),
    "recipients": (cmd_recipients, "list age keys that can decrypt the vault"),
    "rotate": (cmd_rotate, "re-encrypt for the .sops.yaml recipient list"),
}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)
    for name, (handler, help_text) in COMMANDS.items():
        sub = subparsers.add_parser(name, help=help_text)
        sub.set_defaults(handler=handler)
        if name == "import":
            sub.add_argument("--source", help="plaintext dotenv to import (default: .env)")
            sub.add_argument(
                "--force", action="store_true", help="replace an existing vault"
            )
        if name == "check":
            sub.add_argument(
                "--staged",
                action="store_true",
                help="validate the blob in the git index rather than the worktree",
            )
        if name == "materialize":
            sub.add_argument(
                "--force", action="store_true", help="overwrite an unmanaged .env"
            )
            sub.add_argument(
                "--verbose", action="store_true", help="report even when unchanged"
            )

    args = parser.parse_args(argv)
    try:
        return args.handler(args)
    except SecretsError as err:
        print(f"error: {err}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
