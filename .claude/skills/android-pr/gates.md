# Gates and evidence

`pr_state.py state` decides G0–G5. You judge G6 and G7 from the sources named here. A gate is **Passed**, **Not required**, **Unverified** (it doesn't block, but goes under *Not verified*) or **Blocked**.

| Gate | Decided by | Blocked when | Unverified when |
|---|---|---|---|
| G0 scope | `pr_state.py` | no committed or included files | — |
| G1 branch | `pr_state.py` | on `main` or a detached HEAD | — |
| G2 staging | `pr_state.py` | anything is already staged before `commit` | — |
| G2b file names | `pr_state.py` | a changed or untracked path contains a control character (such as a newline) or starts with `"`. Such a path is classed `invalid-name` and never staged | — |
| G3 secrets | `pr_state.py` | a secret-like file name (case-insensitive) in the PR or the working tree, or a marker in a PR file: private-key block, service-account JSON, `private_key` field, GitHub token, Google API key or `oauth_token` field. The first and last MiB of each file are scanned | — |
| G4 local verification | `pr_state.py` | files in Android CI scope changed and there is no **current, passing** `android-verify ci` summary | — |
| G4b instrumented | `pr_state.py` | — | `androidTest/`, `res/` or `feature/*/ui/` changed and there's no current passing instrumented summary |
| G5 audit | `pr_state.py` | a current audit report lists Regressions | reviewable files changed and no audit report is current |
| G6 register | you | the PR says it fixes `F-xx`, but `findings.md` in the PR doesn't update that entry's status | a fix PR has no RED/GREEN evidence for the finding |
| G7 GitHub CI | you, in `status` | a check failed on the PR's head SHA | checks pending, or they ran on a different SHA than the local `HEAD` |

## Local evidence (`android-verify`)
- **Where:** `docs/android-audit/reports/verify/*-summary.txt` (local, untracked; it survives sessions), the session scratchpad (`--evidence-dir <S>`), and committed `docs/android-audit/evidence/**/*-summary.txt`.
- **Current** means the summary's `head` and `android_tree` are accepted by `android-verify/fingerprint.py`: the uncommitted Android changes are the same, and no file in `android/` or `.github/workflows/android-ci.yml` changed between the verified commit and `HEAD`. A docs-only commit therefore keeps evidence current.
- **Stale** evidence is listed with its reason and never cited as verifying this PR.
- **Legacy** summaries (no fingerprint, such as the committed F-04/F-05/F-09 evidence) can be cited as history ("verified on 2026-11-05 at `ac2cb54`"), never as verifying the current code.
- **Passing** means build, unit tests and lint all start with `Passed`, and the fingerprint didn't change during the run. Report how fresh it was: *fresh* (`ci --fresh`), *executed*, or *partly up to date*. An up-to-date pass is accepted, and labelled as such in the body.

## Audit evidence (`android-audit`)
- **Where:** `docs/android-audit/reports/*.md`.
- **Current** means the report header's commit is in the repository, and no reviewable file (android-audit's routing list) changed after it, committed or not.
- Also check by reading that the report's **scope** covers the PR's diff. A `pr <n>` or `diff` report of an earlier head with the same reviewable files is fine; an area-only report is not.

## G7 GitHub CI
- **Source:** `gh pr checks <pr>` and `gh pr view <pr> --json headRefOid`, only.
- **Android CI** runs only when `android/**` or `.github/workflows/android-ci.yml` changes. If `pr_state.py` says `android ci: not triggered`, the absence of the check is expected. Write "Android CI: not triggered (path filter)", which is neither a pass nor a failure.
- **Netlify** checks (deploy preview, header and redirect rules, *Pages changed*) run on every PR. Report each one with its conclusion, including NEUTRAL.
- **Head SHA:** a check result counts only for the SHA it ran on. Name it, e.g. "passed on `ac2cb54`".
- GitHub CI results are never written into a local summary, and local results are never presented as CI results.

## In the PR body
| Evidence | Goes under |
|---|---|
| current, passing local summary | *Verified (local)*: tier, freshness, counts, run ID, fingerprint |
| GitHub check passed on the PR head | *Verified (GitHub CI)*: check name, SHA, run link |
| stale, legacy, missing, Unverified gates, manual or device checks not done | **Not verified**, each with the reason |
| tests of the skills' own scripts (`python3 -I -m unittest …`) | *Verified (local)* as plain command results, run in this session |
