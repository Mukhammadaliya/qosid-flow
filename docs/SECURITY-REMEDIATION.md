# Security remediation — secrets in git history

**Status: OPEN.** Nothing in this document has been executed. It records what is exposed,
what only the owner can do about it, and the trade-off between the two cleanup routes.

Discovered during the 2026-07-24 pre-award audit. The **working tree is clean** — all three
secrets were removed from `HEAD` long ago. They remain reachable in **git history**, which is
what makes this a release blocker rather than a nuisage: the business plan is **open-core**,
so this repository is expected to be published. Publishing it publishes its history.

---

## 1. What is exposed

Values are shown as truncated prefixes only. Do not paste full secrets into this file.

| # | Secret | Where | Severity |
|---|--------|-------|----------|
| 1 | Production source-system credential — `username: admin@head` plus a plaintext password, for `https://app3.greenwhite.uz/x24` | `src/main/resources/application-dev.yml`, many commits (e.g. `29f58a8`, `63a4873`, `0dcb53a`) | **Highest.** A live credential for a production system, not a test fixture. |
| 2 | Telegram bot token, prefix `8318506270:AAG…` | `src/main/resources/application.yml` (~line 216), 20+ commits (e.g. `262dbaf`, `cc7e721`) | High. This is the bot from the documented hijack incident; **revocation was never confirmed**. |
| 3 | OAuth2 `client-secret`, 128 hex chars, prefix `9E4D73C4…` | `application.yml` in commit `29f58a8ca7f42fcb809e92cecca3dd78fef02231` | High. |

How this was verified: `git grep` with the relevant patterns over every revision from
`git rev-list --all` (281 commits). A sweep for AWS/R2-shaped access key IDs (`AKIA*`/`ASIA*`)
found **none**, and only one Telegram-shaped token exists in the whole history — the old one.
**The current `@qosid_alert_bot` token never entered git**, which means the post-incident
handling of that one was correct.

### Why it happened, and what now prevents it

`.gitignore` had **no `.env` entry**, while `ai-context/SAFETY-RULES.md` mandates a production
`.env` holding every live secret. One `git add -A` on a dev or prod box was enough. Additionally
`.claude/settings.local.json` — which carries a production IP, an SSH key path and a
`curl -u "$SOURCE_USERNAME:$SOURCE_PASSWORD"` command — was excluded only by the developer's
*global* gitignore, so a fresh clone on any other machine would have committed it.

Both gaps are closed in the same commit as this document (`.gitignore` entries, a real
`.env.example`, and a full-history `gitleaks` CI job).

---

## 2. Rotation — owner action, cannot be automated

These live in external systems. **Do all three regardless of which cleanup route is chosen in
§3** — rotation is what actually removes the risk; history cleanup only removes the evidence.

- [ ] **Telegram bot (#2).** In BotFather: `/revoke` on the old bot. Then confirm:
      `curl https://api.telegram.org/bot<OLD_TOKEN>/getMe` → must return **401**.
      A 200 means the token is still live and anyone with the history can read your alert channel.
- [ ] **Source-system password (#1).** Change the `admin@head` password on
      `app3.greenwhite.uz`, update `SOURCE_PASSWORD` in the production `.env`, restart the
      gateway, then verify a pull succeeds (Settings → Source Auth → "Sinash", or watch
      `logs/gateway.log` for a successful pull cycle).
- [ ] **OAuth2 client secret (#3).** Regenerate at the provider, then store the new value
      through the ops sidecar while the gateway is stopped
      (`PUT /ops/api/settings/gateway.source.auth.oauth2.client-secret`) — it is an
      AES-encrypted DB-managed key, so it must not go into `.env`.
- [ ] **Admin console password.** Not in git history, but verified live on 2026-07-24: the
      default `admin`/`admin` was still accepted. Change it. (A forced-change gate now blocks
      every other admin route while the default is in place.)

---

## 3. History cleanup — two routes, owner picks

Both are legitimate. Route B is usually cheaper and safer for an open-core launch; Route A is
the only one that makes *this* repository's history publishable as-is.

### Route A — rewrite history in place

```bash
# 1. Back up first. This is not reversible.
git clone --mirror <remote-url> qosid-flow-backup.git

# 2. Write the literal secrets into replacements.txt, one `literal==>REDACTED` per line.
#    This file must NEVER be committed — keep it outside the repo.
#    (pip install git-filter-repo)
git filter-repo --replace-text /path/outside/repo/replacements.txt

# 3. Push the rewritten history.
git push --force --all
git push --force --tags
```

What this costs:

- **All 281 commit SHAs change.** Every existing clone must be re-cloned; `git pull` will not
  reconcile. Any open pull request is invalidated.
- `master` is protected — force-push requires temporarily removing that protection, which is
  itself a window worth minding.
- **GitHub may keep the old commits reachable from its cache** even after the force-push.
  Removing them for good requires a support request. Until that completes, the secrets are
  still fetchable by SHA.
- Any fork, mirror, or CI cache retains the old objects.

### Route B — rotate, keep this repo private, publish a fresh repo

1. Complete §2 rotation.
2. Keep this repository private; treat its history as compromised-but-contained.
3. For the open-core launch, create a **new public repository** seeded from a single squashed
   commit of the reviewed tree — no historical objects at all.

What this costs: the public repo has no commit history, so the project's development record is
not publicly visible. What it buys: no force-push, no broken clones, no reliance on a provider
purging its cache, and a public repo that is provably clean by construction rather than by
rewrite.

> **Recommendation, not a decision:** if the goal is an open-core release, Route B achieves the
> security objective with strictly less risk. Route A only becomes necessary if the public
> history itself is a deliverable (e.g. to demonstrate development rigour to reviewers).

---

## 4. Verification

After choosing a route, confirm:

```bash
# No telegram-shaped token anywhere in history (expect: no output)
git grep -I -h -o -E "[0-9]{8,11}:AA[A-Za-z0-9_-]{30,40}" $(git rev-list --all) | sort -u

# No credential literals in config files across history (expect: only placeholders)
git grep -I -h -o -E '(password|client-secret)"?[[:space:]]*[:=][[:space:]]*"?[A-Za-z0-9_@+/=.-]{10,}' \
  $(git rev-list --all) -- '*.yml' '*.yaml' | sort -u

# Ignore rules actually resolve to THIS repo's .gitignore, not a global one
git check-ignore -v .env .env.local .claude/settings.local.json
git check-ignore -v .env.example   # expect: no output
```

The `secret-scan` CI workflow runs an equivalent full-history scan on every push to `master`
and `debug`. While §3 is still open its scan step is `continue-on-error: true` — it reports
without blocking, because the three historical findings would otherwise fail every push.
**Remove that line once history is clean**, otherwise the job keeps reporting and never
actually guards the patterns it was added for. The findings are always available as the
`gitleaks-report` artifact on the workflow run.
