# secrets/

`secrets.enc.env` is the source of truth for every runtime secret in this
stack. It is a dotenv file whose **values** are age-encrypted and whose **keys**
are plaintext, so `git diff` tells you *which* secret changed without leaking
*what* it changed to. It is committed on purpose.

Nothing reads it directly. `scripts/manage_secrets.py materialize` decrypts it into a
gitignored `.env`, which is what Docker Compose, the Tiltfile, and the host
fetchers actually consume. `make run` and `tilt up` do that for you.

Full documentation — first-time setup, adding a host, rotation, recovery —
lives in [`docs/secrets.md`](../docs/secrets.md).

Quick reference:

```
make secrets          # decrypt into .env (usually automatic)
make secrets-edit     # change a value
make secrets-check    # validate; also runs in CI
```
