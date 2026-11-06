# AI-Assisted Android Engineering Workflow

Status: **implemented** (2026-11-06). The four reviewer agents and the three skills below exist under `.claude/` and are committed with this document. The fix loop described in §3 as the original `android-pr` proposal is not implemented: `android-pr` became the PR gate, and the fix loop stays manual (§5).

## 1. Inventory: what already exists (checked 2026-11-05)

| Asset | Location | Notes |
|---|---|---|
| `compose-reviewer` subagent | `.claude/agents/compose-reviewer.md` (untracked) | Read-only (Read/Glob/Grep), Sonnet, Compose, lifecycle, a11y and offline focus. Used in this audit as the UI/performance reviewer. |
| `goldie` skill | `.agents/skills/goldie` (+ `skills-lock.json`) | Store screenshots and video. Out of audit scope. |
| Android CLI | `android` 1.1.16717390 in `~/.local/bin` | Commands used in this audit: `info`, `describe`, `studio check`, `studio render-compose-preview`, `analyze`, `layout`, `screen capture`, `emulator list`, `docs search/fetch`. |
| Plugin skill `android-cli` | Claude Code plugin | Loaded in this audit for layout, screen and journey conventions. |
| Plugin skills `android-skills:*` | Claude Code plugin | Relevant ones: `testing-setup`, `jetpack-compose-performance`, `r8-analyzer`, `anr-audit-and-fix`, `edge-to-edge`, `adaptive`, `play-policy-insights`, `android-intent-security`. |
| No `CLAUDE.md` / `AGENTS.md` | — | Recommended (see §5). |

Rule: reuse these assets. Don't duplicate them. The proposed agents below cover scopes that `compose-reviewer` does not.

## 2. Subagents (`.claude/agents/`)

All of them are read-only (`tools: Read, Glob, Grep`, model Sonnet), so a reviewer can never edit code or claim to have run anything. They all use the same output contract (§4).

| Agent | Scope | Doesn't cover (handled elsewhere) |
|---|---|---|
| `compose-reviewer` | Compose state, lifecycle, recomposition, a11y semantics, RTL/l10n, timer UX | Repository/Room internals |
| `android-architecture-reviewer` | Layer boundaries, ViewModel/coordinator design, repositories, Room transactions and migrations, sync, DI container (manual `AppContainer`; Koin planned), Navigation3 keys, ADR drift | Compose UI details |
| `android-testing-reviewer` | Coverage map, coroutine/Flow/Room test practice, missing regression tests, test quality, CI test gating | Writing the tests |
| `android-security-reviewer` | Firestore rules, client trust boundaries, content integrity, storage and backup, manifest exposure, release hardening, CI supply chain, privacy-policy parity. **Never prints secret values.** | Play policy (use `play-policy-insights`) |

## 3. Skills (`.claude/skills/`)

All three are invoked by the user only (`disable-model-invocation: true`), and none can edit code (`disallowed-tools: Edit, NotebookEdit`, plus guardrails that hold across turns).

| Skill | Purpose | Writes |
|---|---|---|
| `/android-audit` | Runs the selected reviewers in parallel on a scope (`diff`, `pr <n>`, `range`, `full`, areas, features or paths). Merges and de-duplicates their findings against `findings.md`, classifies them as Regression, New, Existing or Documented limitation, and re-reads the cited lines for the top N. | One untracked report under `docs/android-audit/reports/` |
| `/android-verify` | Runs allowlisted checks through `run_gradle.sh`: `ci` (the CI command), `ci --fresh` (unit tests and lint forced with per-task `--rerun`), `focused --tests … [--rerun]`, `instrumented` on a dedicated emulator, `release`, `content` and `remote`. `parse_reports.py` reads the JUnit and lint XML and reports Passed, Passed (up to date), Failed, Not run or Needs verification per check. Every summary records a **fingerprint** (commit plus uncommitted Android changes) so later steps can tell whether it still describes the code. | Scratchpad files; an untracked summary under `docs/android-audit/reports/verify/`; `evidence/F-xx/` only with `--finding` |
| `/android-pr` | The PR gate. `prepare` (read-only) classifies files (PR, never-stage, needs-approval, unrelated, secret risk), checks local verification and audit evidence for staleness, and drafts a title, commit message and body that separate *Verified* from *Not verified*, keeping local and GitHub CI results apart. `branch`, `commit`, `push`, `open` and `update` each run only after their own explicit approval; `commit` stages an exact file list and checks the staging area before committing. `status` reads GitHub checks. It never merges: the owner merges on GitHub. | Scratchpad drafts; Git and GitHub only after approval |

Shared by all three: `.claude/tools/git_read.py`, a read-only git wrapper that the skills pre-approve instead of free-argument rules like `git diff *` (which would also allow `--output` writes or `branch -D`), and `.claude/tools/safe_paths.py`, which limits script output to new files in temporary directories or named evidence folders.

Reused instead of duplicated: R8 analysis (`r8-analyzer`), Compose performance (`jetpack-compose-performance`) and test setup (`testing-setup`).

## 4. Shared finding format

```
ID · Category · Type (Defect | Risk | Safeguard | Suggestion)
Severity (Critical/High/Medium/Low) · Confidence (Confirmed/Probable/Needs verification)
Evidence: path:line + short quote (never secrets)
Impact · Fix · Regression test (name + level) · Effort S/M/L · Verification method
```

## 5. Workflow loop

```
review ──► triage ──► implement ──► verify ──────► PR ──────────► merge
  │          │           │             │              │               │
android-   human picks  test first,   android-       android-pr:     human, on
audit      1 finding    then fix      verify         prepare, then   GitHub
(read-     (approval    (manual,      (local,        approved        (never
only)      gate)        approved)     fingerprinted) commit, push,   automated)
                                                     open
```

After the PR is open, `/android-pr status <n>` records GitHub CI on the PR's head SHA. A post-merge `/android-audit range` feeds new findings back into the register.

Guardrails:
- Reviewers are read-only.
- Production edits only happen after the human approves a specific finding ID.
- Branch creation, commit, push and PR creation each need their own explicit approval. Nothing is merged automatically.
- Each PR fixes one finding, or one tightly related pair.
- Each PR includes a failing-then-passing test.
- No production Firebase writes, deploys or emulator data wipes without explicit approval.

Recommended `CLAUDE.md` contents: module map, build commands, "manual DI via `AppContainer`", "progress is local-only", the finding format above, and the rule that `connectedAndroidTest` uninstalls the app on the device.

## 6. Evidence capture for a public portfolio

For each PR, store evidence under `docs/android-audit/evidence/<finding-id>/`:

| File | Captured by |
|---|---|
| `before.md` / `after.md` | Test names and pass/fail counts from JUnit XML. Lint warning counts by ID. |
| `layout-before.json` / `layout-after.json` | `android layout` on the affected screen (e.g. answer state descriptions for F-09) |
| `screen-before.png` / `screen-after.png` | `android screen capture` |
| `preview.png` | `android studio render-compose-preview` |
| `apk.txt` | `android analyze <apk>` for size and R8 (F-11) |

Rules: record exact commands and dates. Never commit screenshots that show personal data. Make claims only from captured artefacts.
