# F-09 · Iteration 2: auto-reveal verdict, verdict-only live region (2026-11-05)

- **Device:** dedicated `medium_phone` (`emulator-5556`), API 36. `emulator-5554` was untouched.
- **In-app locale:** German. This was chosen during the device journey, so the strings resolve to German.

## Test spec change (explicit)

`explanationCardIsPoliteLiveRegion` (iteration 1) asserted the old contract, which made the whole card a live region. It was **replaced** by `onlyVerdictIsPoliteLiveRegion`, which asserts the new contract:
- the verdict node is Polite and does not contain the explanation;
- the card node and the explanation node have no LiveRegion.

All other existing assertions are unchanged.

## RED → [`red2-instrumentation-raw.txt`](red2-instrumentation-raw.txt)

Production code at this point was iteration 1. Result: `Tests run: 4, Failures: 2`.

| Test | Result | Failure |
|---|---|---|
| `onlyVerdictIsPoliteLiveRegion` | ❌ | `:206`: the verdict node is the merged card, with `Text = [Noch nicht richtig, Erklärung, Das ist die Erklärung.]` and `LiveRegion = Polite` |
| `submittingAnswerRevealsVerdictWithoutManualScroll` | ❌ | `:228`: "… 'Noch nicht richtig' … is not displayed!" |
| `verdictRevealDoesNotRepeatOnRecomposition` | ✅ | Expected to pass, since there is no auto-scroll yet. It guards GREEN. |
| `submittedAnswerShowsCollapsedMemoryKeyAndExpandedExplanation` (control) | ✅ | — |

## GREEN

Build log `green2-build.txt` is kept local. It shows `compileDebugKotlin` executed. The APK (13:12:03) is newer than the source (13:11:53).

Production change, `StudySessionScreen.kt` only:
- **Auto-reveal.** `QuestionContent` now has a `rememberLazyListState()`. A `LaunchedEffect(question.id, isSubmitted)` scrolls to the explanation item once per question. The guard is `rememberSaveable(question.id) { hasRevealedFeedback }`, so there is no re-scroll on recomposition or rotation and no focus request.
- **Verdict-only live region.** `liveRegion = Polite` and `mergeDescendants` moved from the card to the verdict `Row` (icon and verdict text). The card's label and explanation are separate nodes again.
- **Item index.** A new `explanationItemIndex(question)` mirrors the `LazyColumn` item order.

| Run | Result | Raw |
|---|---|---|
| Targeted (same 4) | `OK (4 tests)` | [`green2-targeted-raw.txt`](green2-targeted-raw.txt) |
| `StudySessionScreenTest` + `StudySessionLocalizationTest` | `OK (14 tests)` (13 + 1) | [`green2-suite-raw.txt`](green2-suite-raw.txt) |
| `assembleDebug lintDebug testDebugUnitTest` | BUILD SUCCESSFUL. 67 unit tests, 0 failures. Lint 55 warnings, 0 errors (unchanged). | [`green2-ci-reports-summary.txt`](green2-ci-reports-summary.txt). The console log `green2-ci-checks.txt` is kept local because it contains absolute local paths. |

## Real app, before and after (`device/`)

- **Before (iteration 1):** `02-after-submit*`. The card is absent from the tree and off-screen right after submit.
- **After (iteration 2):** `06-after-fix-submit.png` and `06-after-fix-submit-layout-full.json`. Right after tapping *Antwort prüfen*, with no manual scroll:
  - The list auto-scrolled.
  - The verdict "Noch nicht richtig" (bounds `[195,1325][567,1381]`), the label and the explanation are all on screen and in the tree.
  - ✕ shows on A and ✓ on B. All four options remain visible.

## Automated vs manual

- **Automated (semantics):** state descriptions on the options; only the verdict is Polite; the explanation is separate and not live; the verdict is displayed after submit without a manual scroll; there is no re-scroll on recomposition.
- **NOT VERIFIED (needs TalkBack):**
  - the actual speech on submit;
  - no duplicate announcement when scrolling the verdict out and back in;
  - focus stays on the action button;
  - RTL `ar`, 200% font scale, and reduced-motion behaviour of `animateScrollToItem`.

## Independent review (compose-reviewer, static, 2026-11-05)

**Verdict: ready for commit, with no blocking issues.** All counts above were verified against the raw logs.

Confirmed by the review:
- `explanationItemIndex` is correct for every memory-key and image combination.
- The `Loading` state between questions (`StudySessionViewModel.kt:105`) discards the saveable guard, so each new question reveals again.
- Rotation does not re-scroll.
- The verdict `Row` merges to a single verdict node, the explanation stays navigable, and no focus is requested.
- There are no unrelated code changes.
- Replacing `explanationCardIsPoliteLiveRegion` with `onlyVerdictIsPoliteLiveRegion` is a legitimate spec change, not a weakening.

Non-blocking follow-ups:
1. **`verdictRevealDoesNotRepeatOnRecomposition` is weak.** It passed in RED, and it fails only if *both* the effect keys and the guard break. Tighten it (e.g. add a rotation test with `StateRestorationTester`) or mutation-test it.
2. **Missing index cases.** Add reveal tests for a question with an image and for one with no memory key. Also add a correct-pick state-description test and a next-question re-reveal test.
3. **Process death and the saveable guard (Low).** If a restored session came back on the same question id unsubmitted, the saved guard could skip one auto-scroll. In the earlier `am kill` check (audit F-32) the session reshuffled to a different question, so this is unlikely. Switching to `remember(question.id)` would remove it.
4. **Options can scroll out of view.** `animateScrollToItem` puts the card at the top. With long explanations or 200% font, the ✓/✕ options can scroll above the viewport. Consider a negative `scrollOffset`. On device at default font, all four options stayed visible (`06-after-fix-submit.png`).

## Open items

- The sensitivity of `verdictRevealDoesNotRepeatOnRecomposition` was not mutation-tested (see follow-up 1).
- `explanationItemIndex` duplicates the item order of the list and must be updated if items change. The reveal test covers the default fixture only (memory key present, no image).
