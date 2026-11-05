# Android Improvement Roadmap

Each item maps to findings in [findings.md](findings.md). Each one is sized to make a focused, reviewable PR with a regression test. Status (2026-11-05): PR-L is implemented (commit `f88e250`, not yet merged; manual TalkBack verification NOT VERIFIED). Nothing else on this roadmap has been implemented yet.

## Phase 0: Safety net (do first)

Later fixes need these tests to protect them.

| PR | Scope | Findings | Effort |
|---|---|---|---|
| **PR-A** | `test(android): add coroutine test toolchain and MockExamViewModel tests`. Adds `kotlinx-coroutines-test`, Turbine and `MainDispatcherRule`, plus timer, auto-submit, restore-after-deadline and double-submit tests. | F-07 | M |
| PR-B | `ci(android): run instrumented tests on a Gradle Managed Device` | F-06 | M |

## Phase 1: Correctness and data integrity

| PR | Scope | Findings | Effort |
|---|---|---|---|
| **PR-C** | `fix(android): open cached content immediately and refresh in background` | F-01, F-13 | S–M |
| PR-D | `fix(android): serialise mock exam mutations and persist before archiving` | F-04, F-05 | M |
| PR-E | `fix(android): preserve wrong answers when restarting a completed catalogue` (with an ADR 0008 amendment) | F-03 | S |
| PR-F | ADR 0014 on release activation and progress carry-over, plus the implementation and retention policy | F-02, F-21 | M–L |
| PR-G | Migration chain and DAO tests | F-22, F-23 | S |

## Phase 2: Architecture hygiene

| PR | Scope | Findings | Effort |
|---|---|---|---|
| PR-H | `HomeViewModel` and `ExamHistoryDetailViewModel`, moving data access out of composables | F-08 | M |
| PR-I | Split `LocalContentRepository` and remove the default implementations | F-20 | M |
| PR-J | `ContentBootstrapKey(languageCode)` and Settings double-tap guard | F-12, F-27 | S |
| PR-K | Doc alignment: README, spec and ADR 0003 (Auth, Hilt, WorkManager status; rules path) | F-10 | S |

## Phase 3: Accessibility, localisation and UX

| PR | Scope | Findings | Effort |
|---|---|---|---|
| **PR-L** ✅ | `fix(android): announce correct and wrong answers to assistive technologies`. Implemented as `f88e250` with the message "fix(android): improve answer feedback accessibility". | F-09 | S |
| PR-M | Plurals across 21 locales | F-15 | M |
| PR-N | Mock exam scroll reset, study transitions without Loading flash | F-14, F-26 | S |
| PR-O | RTL arrow, German locale spans | F-16 | S |
| PR-P | Content width constraints, history retry | F-28 | M |

## Phase 4: Release hardening

| PR | Scope | Findings | Effort |
|---|---|---|---|
| PR-Q | Full R8 with keep rules, release smoke test, before/after APK size (`android analyze`) | F-11 | M |
| PR-R | Network security config, image URL allowlist | F-17, F-18 | S |
| PR-S | App Check, budget alert (needs a Firebase console change, so the owner must do it) | F-19 | M |
| PR-T | Backup policy, CI SHA pinning, Dependabot | F-30, F-31 | S |

## Recommended first three PRs

1. **PR-A: test toolchain and `MockExamViewModelTest`.**
   - Highest learning value: coroutine virtual time and dispatcher rules.
   - No production behaviour change.
   - Unblocks PR-C and PR-D.
2. **PR-C: open cached content immediately.**
   - Confirmed, user-visible High defect.
   - Small diff, matches what ADR 0006 already states.
   - Clean before/after: a manual offline check plus a unit test.
3. **PR-L: accessible answer feedback.**
   - Small Compose and semantics change with a measurable before/after.
   - Evidence: an `android layout` dump showing the state description, plus a semantics test.
   - Good portfolio value.

PR-D (mock exam integrity) is the next candidate. It should land after PR-A, so the race has a test that reproduces it first.
