# F-09 · Real-app device verification (GREEN build)

> **Superseded.** This verifies iteration 1. Its conclusion led to iteration 2, the committed fix `f88e250`, verified on device in `05-*`/`06-*`. See [../iteration2.md](../iteration2.md). Manual TalkBack verification remains **NOT VERIFIED**.

- **Date:** 2026-11-05
- **Branch:** `fix/android-accessible-answer-feedback` (uncommitted GREEN change)
- **Device:** dedicated AVD `medium_phone` (`emulator-5556`), API 36. Display 1080×2400 @ 420 dpi, font scale 1.0.
- **Personal emulator:** `emulator-5554` (Pixel_9a) was not touched.
- **Build:** the debug APK from the GREEN phase, installed 12:03:17.
- **Content:** downloaded at first launch from Firestore. This was a read-only download through the app's normal sync path; nothing was written to Firebase.
- **Onboarding:** language *Deutsch*, state *Berlin*.

## Journey

1. `adb -s emulator-5556 shell am start -W -n com.nextdaymobilestudio.lebenindeutschland/.MainActivity`
2. Selected *Deutsch*, then *Continue*. Selected *Berlin*, then *Weiter*. The content download finished and Home was shown.
3. Tapped *Lernen starten* to open question 1 of 310: "Eine Partei im Deutschen Bundestag will die Pressefreiheit abschaffen. Ist das möglich?" The correct answer is **C**.
4. Selected **A** (wrong) and tapped *Antwort prüfen*.
5. Captured the state immediately after submit. Then swiped the list up and captured again.

All taps were made with `adb -s emulator-5556 shell input tap …`. The captures used:
- `android layout --device=emulator-5556 [--full] --pretty -o …`
- `android screen capture --device=emulator-5556 -o …`

| File | Content |
|---|---|
| `01-before-submit.png` | Question before any selection |
| `02-after-submit.png`, `02-after-submit-layout-full.json` (the filtered `02-after-submit-layout.json` is kept local as a duplicate) | Immediately after *Antwort prüfen* |
| `03-scrolled.png`, `03-scrolled-layout-full.json` | After scrolling to the result card |
| `04-explanation-length-stats.txt` | Explanation length distribution, from a read-only copy of the app DB on this emulator |

## Confirmed

| # | Behaviour | Evidence |
|---|---|---|
| C1 | **Visual result does not depend on colour.** The wrong pick A shows ✕ and the correct option C shows ✓. B and D show no icon. | `02-after-submit.png` |
| C2 | **The result card is not on screen and not in the accessibility tree right after submit.** The full layout dump has no "Noch nicht richtig", "Erklärung" or explanation node. The visible viewport ends inside option D, and the button reads *Nächste Frage*. | `02-after-submit-layout-full.json`, `02-after-submit.png` |
| C3 | **After scrolling, the card is present and visible** (bounds `[53,1268][1027,2027]`). It holds three texts: verdict "Noch nicht richtig" (18 chars), "Erklärung" (9) and the explanation (231). Merged, that is **260 chars / 37 words**. | `03-scrolled.png`, `03-scrolled-layout-full.json` |
| C4 | Option node states after submit: A reports `CHECKED`. B, C and D report `CHECKABLE, CLICKABLE`. | `02-after-submit-layout-full.json` |
| C5 | **Explanation length across the catalogue** (`de`, n = 460): median 168 chars / 22 words, 90th percentile 240 / 33, max 344 / 46. | `04-explanation-length-stats.txt` |
| C6 | TalkBack is installed on this image (`com.google.android.marvin.talkback`) but not enabled. | `adb shell pm list packages`, `settings get secure enabled_accessibility_services` → `null` |

**Consequence of C2.** On this device, at the default font scale and with a four-option question, the polite live region **cannot announce the verdict at submit time**. The card is a `LazyColumn` item that is not composed until it is scrolled near. The `explanationCardIsPoliteLiveRegion` test passes only because it calls `performScrollTo()` first. So the instrumented test proves the property exists. It does **not** prove that the card is announced when the user submits.

## NOT VERIFIED

| # | Item | Why |
|---|---|---|
| N1 | **TalkBack speech output** (what is spoken at submit, and when scrolling to the card) | The Android CLI and `adb` cannot capture spoken output |
| N2 | `stateDescription` on options in the real app | `android layout` (uiautomator) does not expose `stateDescription` or the enabled flag. It is verified only by the instrumented test (`green-suite-raw.txt`). |
| N3 | TalkBack focus and traversal order | The dump lists the scroll content (options) before the top bar and the *Nächste Frage* button. This is tree order, not necessarily TalkBack's traversal order. |
| N4 | Whether options B–D are actionable after submit | The dump reports `CLICKABLE`, but uiautomator does not show the enabled state. Compose keeps the `OnClick` action on disabled nodes (the RED dump showed `[Disabled]` with `OnClick`). This was pre-existing and is not caused by this change. |
| N5 | Whether the live region fires when the card is scrolled into view | Requires TalkBack |
| N6 | `ar` (RTL) icon position, 200% font scale, colour-blindness simulation | Not run |

### Manual TalkBack steps (on `medium_phone` only)

1. Enable TalkBack: *Settings → Accessibility → TalkBack → On*. Alternatively, run `adb -s emulator-5556 shell settings put secure enabled_accessibility_services com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService` followed by `adb -s emulator-5556 shell settings put secure accessibility_enabled 1`.
2. Open *Lernen starten*. Swipe to a wrong option, double-tap it, swipe to *Antwort prüfen* and double-tap.
3. **Record:** is anything spoken without moving focus? If the verdict is not spoken, C2 is confirmed as a user-facing failure.
4. Swipe back to option A and record the spoken text. It should include "Noch nicht richtig". Note whether it says "ausgewählt" or "deaktiviert". Then do the same on C, which should include "Richtig".
5. Swipe forward to the card. Record whether it is one focus stop and how long it reads. Note whether scrolling it into view triggers an announcement.
6. Repeat in `ar` (Arabic). Check the ✓/✕ position and the reading order.
7. Disable TalkBack afterwards.

## Announcement length (mergeDescendants = true)

- A merged polite announcement is the verdict, then "Erklärung", then the explanation. At about 150 words/min that is roughly **10 s at the median and up to about 20 s at the maximum** (German). This is an estimate, not a measurement.
- Polite announcements wait for current speech, and the user can interrupt them. Ten seconds of explanation is arguably useful content, not noise. But it delays the one-word verdict to the start of a long utterance, and it cannot be skipped piece by piece.
- Merging also removes the separate stops for verdict, label and explanation. Long-text navigation (by sentence or word) inside one merged node still works in TalkBack, but it is less discoverable.
- **Assessment:** this is a low-to-medium risk on its own. It is currently moot, because C2 shows the region is usually not composed at submit time.

## Conclusion

The visual part of F-09 is fixed (C1). The spoken-verdict part is **not achieved in the real app** for at least this common case (C2).

Production changes are required before F-09 can be marked Fixed. They need approval.

1. Make the verdict perceivable at submit time. Either:
   - on submit, scroll the card into view (`LazyListState.animateScrollToItem` in a `LaunchedEffect(state.isSubmitted)`), or
   - put the verdict announcement on an element that is always composed, such as near the action button.
2. Put `liveRegion` on the verdict row only and drop `mergeDescendants` on the card. That gives a short announcement (about 1–2 s) with the explanation still navigable.
3. Add a test that does **not** call `performScrollTo()`. It should assert that the verdict node exists and is displayed right after submit, so that the C2 gap is regression-tested.
