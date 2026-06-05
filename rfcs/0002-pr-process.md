# RFC 0002: PR review and merge process

- Status: Accepted
- Author: William Chen
- Date: 2026-06-05
- Related: PR #20 (self-approve workflow)

## Decision log

| # | Date | Decision | Rationale |
|---|---|---|---|
| 1 | 2026-06-05 | **Branch protection requires 1 approving review on `master`** even for the sole human contributor. | Forces a deliberate "I have re-read this" step between authoring and merging. AI-generated edits look fine on first read; the second pass catches what the first missed. |
| 2 | 2026-06-05 | **Approval comes from a GitHub App identity** (`wwchen-self-approve`), not from `wwchen` directly. | GitHub explicitly forbids the human author from approving their own PR. A separate App identity is the supported workaround. |
| 3 | 2026-06-05 | **Approval is triggered by a manual `lgtm` comment**, not by `pull_request` events. | Otherwise every push silently re-stamps the PR as approved — defeating the human-gate goal. The comment is the explicit "I have read this" signal. |
| 4 | 2026-06-05 | **Comment matches `lgtm` exactly** (case-sensitive, no surrounding text). | Avoids accidental approvals from quoting an earlier "lgtm" or typing "lgtm but…". |
| 5 | 2026-06-05 | **Comment author must be `wwchen`.** | Guards against Dependabot, future collaborators, or AI bots auto-stamping. |
| 6 | 2026-06-05 | **Auto-merge enabled per PR via `gh pr merge --auto --squash`** rather than always-on. | Per-PR opt-in lets me hold back work that needs manual coordination (e.g. requires a deploy first). |
| 7 | 2026-06-05 | **Squash-merge** as the merge strategy. | Branch commit history is messy AI-iteration noise; the PR title + body are the durable artifact. |

## Summary

The PR process for this repo is: branch → push → open PR → CI runs → re-read the diff → comment `lgtm` → App approves → auto-merge lands it. Branch protection enforces that the approval happened. The author and the approver are the same human but different GitHub identities, which is the load-bearing distinction that makes "self-approve" meaningfully different from "no review."

## Motivation

This is a solo project but a meaningful fraction of code is AI-generated. Without a hard pause between authoring and landing, the workflow degrades to "push and hope" — and the AI's confident-looking output makes that especially risky. The fix is to make merging require a deliberate, separate signal.

GitHub's own "require approval" branch protection is the right primitive, but it's normally aimed at multi-contributor repos. Adapting it to a solo workflow needs:

1. **A second identity** to provide the approval (humans can't approve their own PRs).
2. **A manual signal** to invoke that approval (otherwise the bot defeats the purpose).
3. **Cheap operation** — anything that takes more than typing `lgtm` will get bypassed under deadline pressure.

## The flow

```
branch → push → PR opened → CI runs → re-read diff → "lgtm" comment
                                                           │
                                                           ▼
                                       Self-approve workflow runs
                                                           │
                                                           ▼
                                       App posts APPROVED review
                                                           │
                                                           ▼
                                  Branch protection satisfied + auto-merge fires
                                                           │
                                                           ▼
                                            Squash-merge to master
```

## Components

### Branch protection on `master`
- Require pull request before merging
- Require 1 approving review
- Require status checks (CI: lint, backend-tests, smoke)
- Require branches to be up to date

### GitHub App: `wwchen-self-approve`
- Personal-account-owned, installed only on this repo
- Permissions: `Contents: read/write`, `Pull requests: read/write`
- Webhook disabled (the App is a passive identity, not an event handler)
- Credentials stored as repo secrets:
  - `SELF_APPROVE_APPID`
  - `SELF_APPROVE_PRIVATEKEY`
  - `SELF_APPROVE_INSTALLATIONID`

### Workflow: `.github/workflows/self-approve.yml`
- Trigger: `issue_comment` (created)
- Guard: comment is on a PR, by `wwchen`, body is exactly `lgtm`
- Mints a short-lived installation token via `actions/create-github-app-token`
- Calls `gh pr review --approve` as the App
- The App's name appears as the approving reviewer

### Auto-merge
Enabled per-PR by the author via `gh pr merge <num> --auto --squash` immediately after opening. Once approval and CI are green, GitHub squash-merges automatically.

## Author flow (the actual day-to-day)

```bash
git checkout -b feat/whatever
# ... commit, push ...
gh pr create --base master --title "..." --body-file pr_body.md
gh pr merge <num> --auto --squash    # arms auto-merge

# CI runs. Re-read the diff in the GitHub UI.
# When satisfied:
gh pr comment <num> --body "lgtm"

# Walk away. Land happens automatically when:
#   - self-approve workflow runs and the App approves
#   - CI checks all return green
```

If the diff is wrong, just don't comment `lgtm`. Push fixes; the prior approval (if any) is for the prior commit; comment `lgtm` again on the new state.

## Why not the alternatives

- **Auto-approve on every push** — defeats the human-gate goal. The original wiring did this; rejected on contact with reality.
- **Drop the approval requirement** — works, but loses the deliberate pause. The whole point of this RFC is keeping that pause.
- **Use a PAT from a second GitHub account** — works, but requires maintaining a second account. App is cleaner.
- **Manual `gh pr review --approve`** before `--auto` — would work, but defeats the "approver ≠ author" invariant since I'd be running the review command from my own account, and GitHub blocks that anyway.
- **Label trigger (`approved`)** — was considered. Comment is faster to type and leaves a clearer history.

## Edge cases

- **CI failure after `lgtm`** — auto-merge waits; nothing lands. Push a fix; prior approval is dismissed by GitHub if "Require approval after new commits" is on (it is by default for "Require approval from someone other than the last pusher"). Re-comment `lgtm`.
- **Workflow file changes on a PR** — `issue_comment` runs the workflow from the **default branch**, not the PR branch. So workflow changes only take effect after merge to `master`. This is GitHub's behavior, not a choice; testing workflow changes requires merging them first.
- **Dependabot / external PRs** — `comment.user.login == 'wwchen'` guard means even if an external contributor types `lgtm`, the workflow no-ops.
- **Drafts** — comments on drafts still work since the trigger is `issue_comment` not `pull_request`. If a draft shouldn't be approvable, GitHub's auto-merge already refuses to merge drafts, so this is naturally safe.

## Future considerations

- If a second human contributor joins, drop the App and use real reviews. This whole RFC stops being relevant.
- If AI agents start opening PRs autonomously, `comment.user.login == 'wwchen'` continues to gate approval to me — agents can open and update PRs but can't land them. That's the desired behavior.
- Multi-line `lgtm` comments (e.g. `lgtm — ship it`) currently don't trigger because of the exact-match guard. If that becomes annoying, relax to `startsWith('lgtm')`. Trade-off: easier accidental approval from quoted text.
