# iOS Architecture Note

- Status: Foundation in place; open questions below need confirmation
- Date: 2026-11-15
- Related: ADR 0001 (Android as primary platform), ADR 0002 (language packages),
  ADR 0003 (Android application architecture), `docs/product-specification.md`

The iOS app is a native SwiftUI companion to the Android app. Android is the
source of truth for features, product rules and question content. This note
records the iOS stack and the decisions that are still open. Once a decision is
confirmed it should move into its own ADR.

## Environment at setup

- macOS 15.6.1, Xcode 26.2 (Swift 6.2.3), iOS 26.2 simulator runtime
- No XcodeGen or Tuist; the project is a plain Xcode project

## Stack

| Concern | Choice |
|---|---|
| Language and UI | Swift, native SwiftUI |
| Minimum iOS | iOS 17.0 (required by `@Observable` and SwiftData) |
| Concurrency | async/await and `Task`; `AsyncSequence` only for continuing streams |
| UI state | `@Observable` view models owned by views through `@State` |
| Structure | MVVM + Repository |
| Dependency injection | Initializer injection through `AppContainer`; no DI framework |
| Local data | SwiftData for question content and study progress (not added yet) |
| Backend | Existing Firebase project: anonymous Auth + Firestore (not added yet) |
| Interface text | String Catalog `Localizable.xcstrings`, German source language |
| Tests | Swift Testing for unit tests; XCTest for UI tests |

## Project layout

```text
ios/
├── LebenInDeutschland.xcodeproj
├── LebenInDeutschland/
│   ├── App/                 App entry point and AppContainer
│   ├── Core/                Shared data and services (Content/, later Database/, Firebase/)
│   ├── Feature/             One folder per screen or flow (Home/, later Practice/, ...)
│   └── Resources/           Assets.xcassets and Localizable.xcstrings
├── LebenInDeutschlandTests/
└── LebenInDeutschlandUITests/
```

The folders mirror Android's `app` / `core` / `feature` packages. The Xcode
project uses synchronized folders, so new files in these folders are added to the
app target automatically.

## How the layers fit

```text
LebenInDeutschlandApp ─creates→ AppContainer ─creates→ repositories
        │                            │
        └──→ HomeView(viewModel) ←─makeHomeViewModel()
                 │
          HomeViewModel ──async──→ ContentRepository (protocol)
```

- **Views** render view-model state and forward user actions. They never touch
  SwiftData, Firestore or the network.
- **View models** are `@Observable` classes. They get repositories through their
  initializer and expose read-only state.
- **Repositories** are protocols, so previews and tests can supply fakes.
  `PlaceholderContentRepository` stands in until real storage exists.
- **`AppContainer`** is the counterpart of Android's `AppContainer`. It builds
  long-lived dependencies once and has `make…ViewModel()` factory methods.

The app target uses Xcode 26's defaults: code runs on the main actor unless it
opts out (`SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`, approachable concurrency).
Slow work such as database imports or network sync should move off the main actor
with `@ModelActor` or `nonisolated` async code.

## Localization

- German is the development and fallback language, the same as Android
  (`unqualifiedResLocale=de`). The project registers the 21 Android languages.
- Keys reuse the Android resource names (`home_title`), and values are copied
  from `android/app/src/main/res/values*/strings.xml`. Android's `%1$d` becomes
  `%1$lld`.
- Xcode generates type-safe symbols from the catalog
  (`STRING_CATALOG_GENERATE_SYMBOLS`), so code uses `Text(.homeTitle)`.
- **Every key needs a value in all 21 languages.** With Android-style keys, iOS
  shows the raw key (for example `home_content_not_installed`) for a missing
  language instead of falling back to German. A temporary string can use German
  marked `needs_review`, as `home_content_not_installed` does now.
- Question content (questions, answers, explanations) does not belong in the
  String Catalog. It comes from the Firestore schema-3 language packages, as on
  Android.

## Screens and visual design

- **Every screen must match Android's visual design**: layout, typography scale,
  colors, spacing, component styles and states, including RTL.
- **Navigation may be iOS-native.** For example, use `NavigationStack` push and
  back-swipe, sheets, and toolbar placement where Android uses its own
  patterns. The visual result of each screen still follows Android.
- The placeholder Home screen and the temporary language picker predate this
  requirement. They are rebuilt when the real screens are implemented.

## Question content

The iOS app reuses Android's schema-3 content format unchanged (ADR 0002,
`docs/product-specification.md` §7–§8). Work proceeds in small PRs: canonical
JSON and hashing (done), validator, SwiftData store, image store, then Firebase.

Decided:

- **Download only the selected language package.** This follows the product
  specification and ADR 0002. Each package already contains the German original.
  (Android's code currently also downloads the German package.)
- **Progress is keyed by stable question ID** (ADR 0002), not by release, so a
  new content release keeps the learner's progress. This avoids Android audit
  finding F-02.
- **The active content stays usable until its replacement is fully validated
  and activated.** Nothing is written locally before validation, and a failed or
  cancelled download leaves the current content in place.

Not decided:

- **Removing superseded releases.** Before implementing release cleanup, explain
  its effect on offline language switching (an installed package of another
  language, or of an older release, could be lost) and get the owner's review of
  that decision.

### Canonical JSON and hashes

Every schema-3 hash is SHA-256 over canonical JSON text, produced by the
publisher's `json.dumps(value, ensure_ascii=False, sort_keys=True,
separators=(",", ":"))` in `scripts/content/build_schema3.py`. iOS reproduces it
in `Core/Content/Sync/CanonicalJSON.swift`:

- `NSNumber` values from Firestore are told apart by their Core Foundation type,
  so `true` never becomes `1` and `1.0` never becomes `1`.
- Keys are sorted by Unicode code point. Strings are never normalized, and only
  quotes, backslashes and control characters are escaped.
- Floats use Python's `repr` layout: shortest round-trip digits, with exponent
  notation below 1e-4 and from 1e16.
- `NaN` and infinity are rejected, as on Android.

Expected outputs come from the publisher itself:
`scripts/content/generate_ios_canonical_json_fixtures.py` writes
`ios/LebenInDeutschlandTests/Fixtures/canonical-json-fixtures.json`. A Python
test fails if the publisher's encoding changes without regenerating the
fixtures. An optional iOS test recomputes every hash of a release generated
locally:

```bash
TEST_RUNNER_LID_SCHEMA3_RELEASE_DIR=<path to generated schema-3 release> \
  xcodebuild -project ios/LebenInDeutschland.xcodeproj -scheme LebenInDeutschland \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:'LebenInDeutschlandTests/CanonicalJSONTests/reproducesEveryHashOfAGeneratedRelease()' test
```

Cross-platform note: Android's `CanonicalJson.kt` formats floats with Kotlin's
`Double.toString`, which differs from Python for values that Python writes in
exponent notation or as long whole numbers (`1e-05`, `1e+16`,
`9500000000000000.0`). Current releases contain no such values (checked
2026-11-15), so no release is affected today.

## Decisions that need confirmation

1. **In-app language selection.** Android lets users choose the interface
   language inside the app (AppCompat per-app locales). A prototype (2026-11-15)
   showed that SwiftUI can do the same for the app's own screens. The root view
   applies a persisted choice with `.environment(\.locale, …)` and sets
   `.environment(\.layoutDirection, …)` for Arabic, Persian and Pashto
   (`InterfaceLanguageStore`). A UI test switched German → Turkish → Arabic with
   the device set to English and confirmed that the change survives a relaunch.
   These followed the selection immediately:
   - String Catalog text in `Text`, both generated symbols (`Text(.homeTitle)`,
     including format arguments) and keys (`Text("home_title")`)
   - Dates and numbers formatted inside `Text`
   - SwiftUI's own control labels (`EditButton`: Bearbeiten / Düzenle / تعديل)
   - RTL mirroring, once `layoutDirection` was set (`\.locale` does not set it)

   Limitations:
   - `String(localized:)`, `Date.formatted()` and other Foundation calls outside
     `Text` use the **device** language. View models must hand
     `LocalizedStringResource` values to views rather than finished `String`s, or
     set `resource.locale` explicitly.
   - Text outside the SwiftUI hierarchy follows the device language: permission
     prompts, the share sheet, the keyboard, notifications, widgets, the
     home-screen app name and any UIKit or other framework UI.
   - Each new window scene must apply the same environment at its root.
   - Not verified yet: sheets and alerts, the navigation back button, and the
     language VoiceOver uses to speak the text.
   - With locale `ar`, iOS formats numbers with Latin digits (1,234.5).

   Decision: keep an in-app language selector, matching Android and the
   product specification. Store the interface language separately from the
   installed content language. The interface can switch immediately, while the
   content switches only after the new package has been validated
   (specification §8).

   **Temporary placement:** until onboarding and Settings exist, the selector is
   a globe menu in the toolbar of the placeholder Home screen. When those
   screens are built, it moves into language selection in onboarding and into
   Settings, and leaves Home. `InterfaceLanguageUITests` finds the selector by
   its `interfaceLanguageMenu` identifier, so the test must be updated in the
   same change.
2. **Firebase integration.** The plan is the Firebase Apple SDK through Swift
   Package Manager (FirebaseAuth and FirebaseFirestore only), anonymous sign-in
   before the first content read, and the same rule as Android: an installed
   package opens without waiting for sign-in or the network. This needs an iOS
   app registered in the existing Firebase project with the bundle ID
   `com.nextdaymobilestudio.LebenInDeutschland`. Its `GoogleService-Info.plist`
   must be added locally and is already git-ignored. Firestore rules need no
   change.
3. **Swift language mode.** The template uses Swift 5 language mode. Switching to
   Swift 6 mode turns data-race warnings into compile errors. It is cheapest to do
   early, before Firebase and SwiftData code exists.
4. **SwiftData model and migration strategy.** The model should mirror Android's
   Room content and progress tables (ADR 0004, 0008). It should use
   `VersionedSchema` from the first release so later migrations are explicit.
5. **Converting Android strings.** Strings are copied from Android one key at a
   time for now. A small script that converts `strings.xml` into the catalog,
   with a check that all 21 languages are present, would keep the two platforms
   in sync. It could also run in CI.
6. **Ads.** Android shows AdMob banners on practice screens (ADR 0014). It is
   still open whether iOS gets ads, and with which consent flow (App Tracking
   Transparency and UMP).
7. **iOS CI.** No workflow builds or tests the iOS app yet.
