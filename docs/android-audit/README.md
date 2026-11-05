# Android Engineering Audit (2026-11-05)

A read-only, evidence-based audit of the Leben in Deutschland Android app. The audit itself changed no production code; its only output is `docs/android-audit/`. The first fix from it, F-09 (PR-L), was implemented afterwards in commit `f88e250`. See [evidence/F-09/iteration2.md](evidence/F-09/iteration2.md).

| Document | Contents |
|---|---|
| [findings.md](findings.md) | 32 findings, ranked top 10, "done well" |
| [test-gap-analysis.md](test-gap-analysis.md) | Coverage map, gaps, 10 prioritised tests |
| [improvement-roadmap.md](improvement-roadmap.md) | Phased PR plan, first three PRs |
| [ai-workflow.md](ai-workflow.md) | Proposed subagents, skills, workflow, evidence capture |

## Executive summary

The Android app is in better shape than the README's "foundation phase" status suggests. The content pipeline is the strongest part:
- Hash-verified, canonical-JSON packages are validated before any write.
- Room uses explicit migrations with no destructive fallback.
- Navigation3 restores the back stack after process death (reproduced on the emulator).
- Firestore rules are read-only for content and default-deny for everything else.
- No secrets are in git history.

The main risks are in **offline-first startup** and **user-data lifecycle**:
- Returning users wait on a network refresh with no timeout (F-01).
- A new content release would make all progress look empty (F-02).
- Restarting a completed catalogue deletes the Wrong Answers list (F-03).
- The mock exam has two integrity gaps around timeout and submit (F-04, F-05).

These are not caught today. **No ViewModel is unit-tested, and the instrumented Room, migration and UI tests never run in CI** (F-06, F-07).

Anonymous Firebase Auth, Hilt and WorkManager are documented in the README and spec but **not implemented** (F-10). Right now that is a documentation issue, not a security one, because progress is local-only.

> **Update (2026-11-05, after #29):**
> - Anonymous Firebase Authentication is now implemented, and Firestore content reads require an authenticated caller. Hilt and WorkManager are still not implemented (see F-10).
> - The audit's privacy-policy claim now **needs verification** (see *Done well → Privacy* in `findings.md`).
> - F-19 (App Check) remains open at Low severity.

Severity count: Critical 0 · High 4 · Medium 12 · Low–Medium 1 · Low 15.

## Method

1. **Discovery:** repo structure, ADRs 0001–0013, product spec, Gradle config, CI, `firestore.rules`, existing agents and skills.
2. **Multi-agent review:** four read-only subagents ran in parallel:
   - `general-purpose` agents as the architecture, testing and security reviewers
   - the existing `compose-reviewer` as the UI and performance reviewer
3. **Lead verification:** the main session re-read the cited lines for all top-10 findings. Findings marked "verified" in `findings.md` were checked this way.
4. **Tool validation:** Gradle and the Android CLI, run against the connected emulator (below).

## Verification log

### Executed successfully

| Command | Result |
|---|---|
| `./gradlew assembleDebug lintDebug testDebugUnitTest` (same as CI) | BUILD SUCCESSFUL. **67 unit tests, 0 failures.** Lint: 0 errors, **55 warnings** (27 PluralsCandidate, 7 UnusedResources, 6 NewerVersionAvailable, 6 GradleDependency, 3 ModifierParameter, 2 MonochromeLauncherIcon, 2 AndroidGradlePluginVersion, 1 RedundantLabel, 1 EmptySuperCall) |
| `./gradlew assembleDebugAndroidTest assembleRelease` | BUILD SUCCESSFUL (mostly up to date). Instrumented tests compile. |
| `android info`, `android describe` | SDK and CLI 1.1.16717390. Variants debug and release resolved. |
| `android studio check` | Android Studio 2026.2.1 running, project READY |
| `android analyze app-release-unsigned.apk` | 19.17 MB uncompressed dex. Plus `mapping.txt` grep: 0 app-package entries (F-11) |
| `android docs search/fetch` (packageScope) | Confirms `packageScope` is meant for gradual R8 adoption |
| `android emulator list`, `adb devices` | AVDs `Pixel_9a` and `Pixel_9_Pro_Goldie`. `emulator-5554` running. |
| Launch installed app (`am start -W`) | Cold start TotalTime 6383 ms. This is a **debuggable** build, so it is not a performance benchmark. |
| `android layout` on Home and Study | All clickables labelled and ≥ 48 dp. Answer options expose CHECKABLE semantics. |
| `android screen capture` on Home and Study | Visually inspected. German UI renders correctly. |
| Process-death journey (`HOME`, then `am kill`, then relaunch) | Back stack and "Frage 31 von 310" restored. A different question was shown due to reshuffle (F-32). |
| `android studio render-compose-preview HomeScreen.kt HomeScreenPreview --print-semantics` | Rendered PNG and semantics tree |

### Failed

| Command | Failure | Interpretation |
|---|---|---|
| `android studio render-compose-preview StudySessionScreen.kt StudySessionScreenPreview` | `Unresolved reference 'coil3'` / `'AsyncImage'` | Gradle compiles this file. Studio's model is probably out of date (needs a Gradle sync after Coil was added). **This is an environment issue, not a code defect.** Re-check after syncing. |
| First `am kill` attempt | Process survived (not yet cached) | Repeated after a delay, which succeeded |

### Not run (and why)

| Check | Reason |
|---|---|
| `connectedDebugAndroidTest` | It installs a test APK and **uninstalls the app afterwards**, which would wipe the emulator's existing app data (progress and Goldie state). It needs owner approval or a dedicated, fresh emulator. |
| Answering questions or taking a mock exam on device | It would change the stored progress on the emulator |
| Release-build runtime check / macrobenchmark | The release APK is unsigned, and there is no benchmark module |
| TalkBack, font scale 200%, RTL locale and landscape passes | Need a manual session. Listed per finding as "manual verification". |
| Firestore rules emulator tests | No rules test harness exists. No production Firebase access was attempted. |
| Coverage measurement | No Kover/JaCoCo configured |

### Needs manual verification

F-01 (offline startup timing), F-02 (release change on device), F-09 (TalkBack), F-12 (language-change race), F-14 (scroll position) and F-16 (RTL rendering).

## Scope notes

- Repository state preserved: the modified `.gitignore` and the untracked `.agents/`, `.argent/`, `.claude/`, `goldie/` and `skills-lock.json` were left as they were.
- Secrets: `google-services.json` and `local.properties` were confirmed gitignored and untracked. Their contents were not read.
- iOS was out of scope.
