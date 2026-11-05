# AI-Assisted Android Engineering Workflow

Status: **proposal**. Of the files described here, only this audit's documentation exists. The agent and skill definitions below are proposed and have not been created yet, pending approval.

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

## 2. Proposed subagents (`.claude/agents/`)

All of them are read-only (`tools: Read, Glob, Grep`), so a reviewer can never edit code or claim to have run anything. They all use the same output contract (§4).

| Agent | Scope | Doesn't cover (handled elsewhere) |
|---|---|---|
| `compose-reviewer` *(exists; keep)* | Compose state, lifecycle, recomposition, a11y semantics, RTL/l10n, timer UX | Repository/Room internals |
| `android-architecture-reviewer` *(new)* | Layer boundaries, ViewModel/coordinator design, repositories, Room transactions and migrations, sync, DI container, Navigation3 keys, ADR drift | Compose UI details |
| `android-testing-reviewer` *(new)* | Coverage map, coroutine/Flow/Room test practice, missing regression tests, test quality, CI test gating | Writing the tests |
| `android-security-reviewer` *(new)* | Firestore rules, client trust boundaries, content integrity, storage and backup, manifest exposure, release hardening, CI supply chain, privacy-policy parity. **Never prints secret values.** | Play policy (use `play-policy-insights`) |

Each agent file follows the structure of `compose-reviewer.md`: role and boundaries, product context to verify against code, review workflow, scenario checks, severity, and output format. The prompts used in this audit are the starting point for each.

## 3. Proposed skills (`.claude/skills/`)

| Skill | Purpose | Uses |
|---|---|---|
| `android-audit` | Runs the four reviewers in parallel with a scope (full, or `git diff main...HEAD` paths). Merges and de-duplicates findings into `docs/android-audit/findings.md` format, re-reads cited lines for the top N, and marks each finding Verified or Unverified. | The 4 subagents |
| `android-verify` | Runs the safe evidence pipeline and writes a dated evidence folder (§6). Reports executed, failed and not-run checks separately. | Gradle, `android` CLI, `android-cli` skill |
| `android-pr` | For one finding ID: write the failing regression test first, then the fix, then `android-verify`, then update the finding's status and the before/after evidence. It stops for approval before any change to production code. | `android-skills:testing-setup`, `compose-reviewer` |

Not proposed, because they already exist: R8 analysis (`r8-analyzer`), Compose performance (`jetpack-compose-performance`) and test setup (`testing-setup`). `android-pr` calls these directly.

## 4. Shared finding format

```
ID · Category · Type (Defect | Risk | Safeguard | Suggestion)
Severity (Critical/High/Medium/Low) · Confidence (Confirmed/Probable/Needs verification)
Evidence: path:line + short quote (never secrets)
Impact · Fix · Regression test (name + level) · Effort S/M/L · Verification method
```

## 5. Workflow loop

```
review ──► triage ──► implement ──► verify ──► document
  │          │           │             │           │
android-   human picks  android-pr:   android-    finding status →
audit      1 finding    test first,   verify      Fixed (PR #),
(read-     (approval    then fix      (Gradle +   evidence folder,
only)      gate)                      CLI)        ADR if needed
```

Guardrails:
- Reviewers are read-only.
- Production edits only happen after the human approves a specific finding ID.
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
