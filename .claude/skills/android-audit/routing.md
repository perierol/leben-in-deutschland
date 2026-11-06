# Routing: scope, reviewer selection and delegation

Paths below that start with `…/` are relative to `android/app/src/main/java/com/nextdaymobilestudio/lebenindeutschland/`. Test sources mirror that layout under `android/app/src/test/` and `android/app/src/androidTest/`.

## Scope grammar

```
/android-audit [scope] [--top N] [--plan]

scope := diff                  # default: main...HEAD plus uncommitted changes to tracked files
       | full                  # whole app, all four reviewers
       | pr <number>           # files changed in a GitHub PR
       | range <a>..<b>        # files changed between two commits
       | <area>...             # one or more of: compose, ui, architecture, arch, testing, tests, security, sec
       | <feature>...          # one or more of: contentbootstrap, examhistory, home, languageselection,
                               #   mockexam, settings, stateselection, studysession
       | <path>...             # repository-relative files or directories
```

- Areas, features and paths can be combined, for example `architecture testing mockexam`. An area restricts *which reviewers* run, and a feature or path restricts *what they review*.
- With an area but no feature or path, the reviewer reviews its whole scope.
- Anything that is not a keyword, a number after `pr`, or a range after `range` is treated as a path. A path that does not exist is an error: list it and stop.

## Resolving the file list

`G` below stands for `python3 -I .claude/tools/git_read.py --repo <root>`. Run git only through it (or the exact load-time commands), never as `git diff …` or `git log …`: plain git commands with free arguments can write files (`--output`) or delete branches (`branch -D`).

| Scope | Commands (read-only) | Diff file for reviewers |
|---|---|---|
| `diff` | `G merge-base main HEAD`, `G diff <base> --name-only`, `G status` | `G --out <S>/android-audit-<slug>.diff diff <base>` (includes uncommitted tracked changes) |
| `pr <n>` | `gh pr view <n> --json number,title,state,baseRefName,headRefOid`, `gh pr diff <n> --name-only` | `gh pr diff <n>`, saved to the scratchpad with Write |
| `range <a>..<b>` | `G rev-parse <a>`, `G rev-parse <b>`, `G diff <a> <b> --name-only` | `G --out <S>/android-audit-<slug>.diff diff <a> <b>` |
| `full`, area, feature, path | none | none. Reviewers read the code directly. |

- Untracked files are not part of a diff scope unless they are named as paths. List them in the report header as "not reviewed".
- Save the diff and the file list in the session scratchpad, for example `android-audit-<scope-slug>.diff` and `.files.txt`. Pass their absolute paths to the reviewers.
- **Reviewable files** are everything except: `docs/**` other than `docs/adr/**`, `*.md` outside `docs/adr/`, images, `goldie/**`, `ios/**`, and generated files under `build/`. A scope with no reviewable files stops before delegation.

## Path routing

Apply every matching row. A file can select several reviewers.

| Changed path matches | Selects | Security focus area |
|---|---|---|
| `…/feature/*/ui/**`, `*Screen.kt`, `*Route.kt`, `*TestTags.kt`, `…/ui/theme/**`, `android/app/src/main/res/values*/**`, `android/app/src/main/res/drawable*/**` | compose | — |
| `*ViewModel.kt`, `*Coordinator.kt`, `…/core/database/**`, `…/core/preferences/**`, `…/core/content/sync/**`, `…/app/**`, `…/LebenInDeutschlandApplication.kt`, `…/MainActivity.kt`, `docs/adr/**`, `android/app/schemas/**` | architecture | — |
| `android/app/src/test/**`, `android/app/src/androidTest/**`, `.github/workflows/android-ci.yml` | testing | — |
| any `android/app/src/main/**/*.kt` | testing (protects the changed code) | — |
| `firestore.rules`, `firebase.json` | security | Firestore rules and client trust boundary |
| `…/core/content/remote/**` | security | Firestore rules and client trust boundary; Anonymous authentication |
| `…/core/content/sync/**` | security | Content integrity |
| `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/res/xml/**` | security | Data at rest, backup, platform |
| `android/app/build.gradle.kts`, `android/build.gradle.kts`, `android/gradle/libs.versions.toml`, `android/gradle.properties`, `android/settings.gradle.kts`, `.gitignore` | security; also architecture when dependencies change | Build, secrets, release hardening |
| `.github/workflows/**` | security | CI supply chain |
| `website/privacy/**` | security | Privacy parity |
| `scripts/content/firestore_upload*` | security | Build, secrets, release hardening (credential handling only) |

### Area keywords
| Keyword | Reviewer |
|---|---|
| `compose`, `ui` | `compose-reviewer` |
| `architecture`, `arch` | `android-architecture-reviewer` |
| `testing`, `tests` | `android-testing-reviewer` |
| `security`, `sec` | `android-security-reviewer`. With no path, use the focus areas "Firestore rules and client trust boundary" and "Anonymous authentication" unless the user names others. |

### Feature keywords
A feature limits the scope to `…/feature/<feature>/**` plus its tests. For reviewer selection, apply the path-routing table to the files in that directory. A feature directory always contains UI, ViewModel and coordinator code, so it normally selects compose, architecture and testing, and selects security only for the paths listed above.

### `full`
`full` selects all four reviewers. Give the security reviewer every focus area, and give the other three no path restriction.

### Limits
- At most four reviewers per run, one of each, so the run stays well under the session's concurrent-subagent limit.
- Never select a reviewer outside `.claude/agents/`.

## Delegation prompt

Fill in this template for each reviewer. Keep it self-contained, because the reviewer does not see this conversation.

```
Read-only <full | focused> review of the Leben in Deutschland Android app.
Repository root: <absolute path>. Branch <branch>, commit <short sha>.

Scope: <scope type and description>.
<For diff scopes:> The unified diff is at <absolute scratchpad path>.diff and the
changed files are listed in <absolute scratchpad path>.files.txt. Read the diff in
full first, then read the changed files in full in the working tree.
Paths in scope: <list>.
<For the security reviewer:> This is a FOCUSED review. Focus areas: <names from
your focus-area mapping>.

Follow your mandatory reading rules, scope boundaries and output format exactly,
including the "Partial review" / "Complete review" title.
Reference existing findings in docs/android-audit/findings.md by ID instead of
re-reporting them, unless you have new evidence.
<For diff scopes:> Classify every item as NEW (introduced or exposed by this
change) or EXISTING (present before it, or already documented), and say which.
Do not edit any files. Do not run anything.
```
