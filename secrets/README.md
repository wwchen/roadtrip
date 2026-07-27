# secrets/

Runtime secrets: encrypted in git, mounted as files at runtime, enforced by the
build.

`registry.yaml` is the source of truth for *which* secrets exist and who
receives them. `docker-compose.secrets.yml` is generated from it, so a wrong
`consumers` entry means the container genuinely doesn't get the secret — the
registry can't drift from reality. `*.enc.env` hold the values, age-encrypted
with keys left in plaintext so `git diff` shows *which* secret changed without
showing what it changed to.

```sh
./secrets/manage.py ls        # what exists, where it's set (never values)
./secrets/manage.py set NAME  # change a value
./secrets/manage.py add NAME --description "…" --consumers backend
```

Full documentation — setup, rotation, threat model, the three passwords already
baked into volumes — is in [`docs/secrets.md`](../docs/secrets.md).
