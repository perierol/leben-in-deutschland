# Leben in Deutschland

[![Android CI](https://github.com/perierol/leben-in-deutschland/actions/workflows/android-ci.yml/badge.svg)](https://github.com/perierol/leben-in-deutschland/actions/workflows/android-ci.yml)

A multilingual, offline-first study app for Germany's *Leben in Deutschland* and
naturalization tests. You learn from the official German questions, with a
translation and an explanation in your own language beside them.

The repository contains:

- **Android app:** the primary platform. The main user flows are implemented.
- **iOS app:** only the Xcode project skeleton so far.
- **Content pipeline:** Python tooling that builds, validates and publishes the
  question catalogue to Cloud Firestore.
- **Website:** a static landing page, support page and privacy policy.

## Contents

- [Repository structure](#repository-structure)
- [Android app](#android-app)
- [Content and languages](#content-and-languages)
- [Firebase](#firebase)
- [Advertising and consent](#advertising-and-consent)
- [Getting started (Android)](#getting-started-android)
- [Tests and CI](#tests-and-ci)
- [Content pipeline](#content-pipeline)
- [iOS app](#ios-app)
- [Website](#website)
- [Documentation](#documentation)
- [AI-assisted development](#ai-assisted-development)
- [Security notes](#security-notes)
- [Licence](#licence)

## Repository structure

```text
android/                Android app (single Gradle module :app)
  app/schemas/          Exported Room schemas, one per database version
ios/                    iOS Xcode project (template skeleton)
scripts/content/        Content build, validation, AI and Firestore upload tooling (Python)
docs/
  product-specification.md      Product rules (draft)
  adr/                          Architecture Decision Records 0001–0014
  android-audit/                Engineering audit, findings register, roadmap
  android-ads-release-checklist.md
website/                Static site deployed with Netlify (netlify.toml)
firestore.rules         Cloud Firestore security rules
firebase.json           Firebase CLI configuration (deploys firestore.rules)
.github/workflows/      Android CI and content-pipeline CI
.claude/                Claude Code agents, skills and tools used for review and verification
```

`.local/` is git-ignored. It holds source catalogues, generated releases and
the Python virtual environment used by the content pipeline.

## Android app

### Features

| Area | What exists in the code |
| --- | --- |
| Onboarding | Language selection, then federal-state selection, then a first content download |
| Content bootstrap | Downloads and verifies the language package from Firestore. An installed package opens without waiting for the network, and a refresh runs in the background |
| Home | Progress dashboard and entry points to practice, Wrong Answers, mock exam, exam history and settings |
| Practice | All active questions (general plus the selected state). Shows the German original, the translation, immediate feedback, the explanation and a memory aid where one exists. Progress is saved locally and the session resumes |
| Wrong Answers | Reviews the questions answered incorrectly |
| Mock exam | 33 questions (30 general, 3 from the selected state), 60-minute timer, no feedback until submission. Results against the 15-point (*Leben in Deutschland*) and 17-point (naturalization) thresholds |
| Exam history | Past mock-exam attempts, with a detail view per attempt |
| Settings | Change language (downloads the new package if needed) or federal state, and open the ad privacy options when the consent SDK requires them |
| Images | Question and answer images are stored on the device, so they work offline |
| Ads | One AdMob banner on practice and Wrong Answers screens only (see [Advertising and consent](#advertising-and-consent)) |

There are no accounts, payments or subscriptions. Study progress and exam history
are stored **only on the device**: no cloud progress sync is implemented, and the
Firestore rules allow no user-data writes.

### Technology

From `android/gradle/libs.versions.toml` and `android/app/build.gradle.kts`:

- Kotlin 2.2.10, Android Gradle Plugin 9.4.1, Gradle 9.6.0 (wrapper)
- `compileSdk` and `targetSdk` 37, `minSdk` 24, Java 17 bytecode
- Jetpack Compose (BOM 2026.09.00) and Material 3
- Navigation 3, with `lifecycle-viewmodel-navigation3`
- Coroutines and Flow
- Room 2.8.5 via KSP, with exported schemas and explicit migrations (database versions 1–4)
- DataStore Preferences
- kotlinx.serialization
- Coil 3 for images
- Firebase BOM 35.0.0: Authentication (anonymous) and Cloud Firestore
- Google Mobile Ads SDK 25.5.0 and User Messaging Platform 4.0.0
- AppCompat per-app language support, with `generateLocaleConfig`
- R8 enabled for release builds

Dependency injection is manual: `AppContainer` in
`LebenInDeutschlandApplication.kt` builds the repositories, the content
synchronizer, the background refresher and the ads controller. Hilt and
WorkManager are **not** used. The background content refresh is an
application-scoped coroutine.

### Code layout

```text
android/app/src/main/java/com/nextdaymobilestudio/lebenindeutschland/
  LebenInDeutschlandApplication.kt   Application and AppContainer (manual DI)
  MainActivity.kt                    Compose host, applies the app language
  app/                               Root composable and Navigation 3 keys
  core/ads/                          AdMob IDs, consent-aware controller, banner
  core/content/remote/               Firestore data source, anonymous authenticator
  core/content/sync/                 Package verification, mapping, sync and refresh
  core/content/images/               Offline image store
  core/database/                     Room database, DAO, entities, repository
  core/preferences/                  Onboarding preferences (DataStore)
  feature/<name>/                    One package per screen: ViewModel or coordinator,
                                     UI state, and ui/ (Route, Screen, TestTags)
  ui/theme/                          Compose theme
```

The architecture is described in ADR 0003, and the database and sync design in
ADRs 0004–0006.

## Content and languages

The product specification describes a catalogue of **460 official German
questions**: 300 general questions and 10 for each of the 16 federal states. A
learner studies the 300 general questions plus the 10 for their selected state.
The mock exam draws 30 general and 3 state questions.

The German wording is treated as the authoritative source. Translations,
explanations and memory aids were generated with AI and may contain errors.

**21 languages** are supported, both for the interface (`res/values-*`; German
is the default resource locale) and for question content (`SupportedLanguage.kt`):

German (`de`), English (`en`), Turkish (`tr`), Ukrainian (`uk`), Arabic (`ar`),
Romanian (`ro`), Polish (`pl`), Russian (`ru`), Bulgarian (`bg`), Croatian (`hr`),
Bosnian (`bs`), Serbian Latin (`sr-Latn`), Greek (`el`), Albanian (`sq`),
Persian/Dari (`fa`), Pashto (`ps`), Kurdish Kurmanji (`ku`), Italian (`it`),
Spanish (`es`), Hungarian (`hu`) and French (`fr`).

Arabic, Persian/Dari and Pashto are right-to-left. The manifest sets
`supportsRtl="true"`.

Content is published as immutable, versioned **schema 3** releases with one
self-contained package per language (ADR 0002). The app verifies a package
(canonical JSON and hashes) before activating it locally, so an invalid
download never replaces the last valid release (ADR 0005).

## Firebase

The Android app uses Firebase Authentication and Cloud Firestore. It signs in
anonymously, with no sign-in screen, before it reads content.

Firestore layout read by the app (`FirestoreContentDataSource.kt`):

```text
contentManifest/current                                  Active release pointer
contentReleases/{releaseId}                              Release metadata
contentReleases/{releaseId}/languagePacks/{lang}         Language package
contentReleases/{releaseId}/languagePacks/{lang}/chunks  Question chunks
```

`firestore.rules` allows **authenticated reads** of `contentManifest` and
`contentReleases` and denies every client write. All other paths are denied.
Content is written only by the content pipeline, which uses a service account.
To deploy the rules with the Firebase CLI, run
`firebase deploy --only firestore:rules --project <project-id>`. The `--project`
flag (or `firebase use`) is needed because `.firebaserc` is not committed.

### Required configuration (not committed)

| File | Purpose |
| --- | --- |
| `android/app/google-services.json` | Firebase Android client configuration for the app ID `com.nextdaymobilestudio.lebenindeutschland`. It is git-ignored. |
| `GoogleService-Info.plist` | Git-ignored in advance for iOS. The iOS project does not use Firebase yet. |

**The app accepts only one Firebase project.**
`FirebaseContentSynchronizerFactory` compares the configured project ID with
its hard-coded `EXPECTED_PROJECT_ID` and refuses to sync from any other
project. You therefore need a `google-services.json` from that project, which
means access to it in the Firebase console. A file from your own Firebase
project will fail the check.

The Google Services Gradle plugin is applied **only if**
`android/app/google-services.json` exists. Without the file the project still
compiles, lints and runs unit tests, which is how CI runs. At runtime, though,
Firebase cannot be initialized, so the first content download ends on the
bootstrap error screen.

## Advertising and consent

As described in ADR 0014 (status: *Proposed*) and
`docs/android-ads-release-checklist.md`:

- One inline adaptive AdMob banner, capped at 60 dp, sits under the top bar on
  the practice and Wrong Answers screen. The mock exam, results, exam history,
  Home and Settings show no ads. The banner is hidden when the screen is too
  short.
- `ConsentAwareAdsController` runs Google's UMP consent flow when Home is shown,
  then initializes the Mobile Ads SDK. Settings shows *Privacy options* when UMP
  requires it. Any failure leaves ads off, and nothing else waits for ads.
- **Debug builds always use Google's published test IDs.**
- **Release builds** read the real IDs from Gradle properties, which must never
  be committed:

  | Gradle property | Value |
  | --- | --- |
  | `lid.admob.appId` | AdMob app ID (`ca-app-pub-…~…`) |
  | `lid.admob.questionDetailBannerId` | Banner ad unit ID (`ca-app-pub-…/…`) |

  Put them in `~/.gradle/gradle.properties`, or provide them as the environment
  variables `ORG_GRADLE_PROJECT_lid.admob.appId` and
  `ORG_GRADLE_PROJECT_lid.admob.questionDetailBannerId`. These names contain
  dots, so a plain shell `export` can't set them, but CI systems or `env` can.

  The `validateReleaseAdMobIds` task runs before every release build and fails
  it if the IDs are missing, malformed or Google test IDs. As a second guard, at
  runtime `AdMobIds.usableAdUnitId` turns ads off if a test ID ever reaches a
  release build.
- Instrumented tests run through `AdsDisabledTestRunner`, so no test requests
  ads or consent.

## Getting started (Android)

### Prerequisites

- **JDK 25.** `android/gradle/gradle-daemon-jvm.properties` pins the Gradle
  daemon to Java 25 (`toolchainVersion=25`), and CI uses Temurin 25. The file
  also lists JDK download URLs that Gradle can use if no matching JDK is
  installed.
- **Android SDK with platform 37** (`compileSdk` and `targetSdk` 37). Point
  Gradle at the SDK with Android Studio, the `ANDROID_HOME` variable, or `sdk.dir`
  in `android/local.properties` (git-ignored).
- `android/app/google-services.json` from the expected Firebase project if you
  want content download to work (see [Firebase](#firebase)).

### Build and run

All Gradle commands run from `android/`:

```bash
cd android
./gradlew assembleDebug          # Debug APK (Google test ad IDs)
./gradlew installDebug           # Install on a connected device or emulator
```

The first launch needs network access to download content. After that, the app
works offline.

A release build (`./gradlew assembleRelease`) also needs the two AdMob Gradle
properties above. Release signing is not configured in the repository: the
output is `app-release-unsigned.apk`.

## Tests and CI

| Kind | Location | Command (from `android/`) |
| --- | --- | --- |
| JVM unit tests (ViewModels, coordinators, sync, mapping, ads, preferences) | `android/app/src/test/` | `./gradlew testDebugUnitTest` |
| Instrumented tests (Compose UI, localization, Room DAO and migrations, offline images, content integrity) | `android/app/src/androidTest/` | `./gradlew connectedDebugAndroidTest` |
| Lint | — | `./gradlew lintDebug` |
| Content pipeline | `scripts/content/tests/` | `python3 -m unittest discover -s scripts/content/tests -p "test_*.py" -v` (from the repository root) |

> `connectedDebugAndroidTest` installs a test APK and **uninstalls the app
> afterwards, which wipes its data** on that device. Use a dedicated emulator.

`ProductionContentIntegrityTest` checks the real downloaded catalogue, so it
needs content installed on the device by a normal app run. A regular connected
run skips it when that content is missing.

### GitHub Actions

- **Android CI** (`.github/workflows/android-ci.yml`) runs on pull requests and
  on pushes to `main` that touch `android/**` or the workflow file, and can be
  started manually. It runs
  `./gradlew assembleDebug lintDebug testDebugUnitTest --stacktrace` on JDK 25,
  without `google-services.json`. **Instrumented tests do not run in CI.**
- **Content Pipeline CI** (`.github/workflows/content-pipeline-ci.yml`) runs on
  changes to `scripts/content/**`. It compiles the scripts, runs the unit tests,
  and builds and validates a fixture catalogue on Python 3.13.

There is no release automation and no iOS CI.

## Content pipeline

`scripts/content/` converts the source catalogue into schema 3 language packages,
validates them, and publishes them to Firestore. **See
[`scripts/content/README.md`](scripts/content/README.md) for the full workflow.**
In summary:

| Script | Purpose |
| --- | --- |
| `build_schema3.py` | Builds schema 3 release packages from a schema 2 catalogue |
| `validate_schema3.py` | Validates structure and explanation readiness |
| `explanation_pipeline.py` | Generates German explanations and their translations with the OpenAI Batch API |
| `memory_key_pipeline.py` | Generates German memory aids with the OpenAI API |
| `firestore_upload_schema3.py` | `plan`, `upload`, `verify` and `activate` steps. It never deletes old releases, and activation is a separate step |

- Building and validating use only the Python standard library.
- The OpenAI scripts need `pip install -r scripts/content/requirements-openai.txt`
  and the `OPENAI_API_KEY` environment variable.
- Firestore upload needs `pip install -r scripts/content/requirements-firebase.txt`
  and `GOOGLE_APPLICATION_CREDENTIALS` pointing to a service-account file
  **outside** the repository.
- Remote and paid commands also require explicit confirmation flags.

The source catalogue (`.local/content/…`) is not in the repository, so a new
developer cannot rebuild real content without it.

## iOS app

`ios/LebenInDeutschland.xcodeproj` contains only Xcode's SwiftUI and SwiftData
starter code: a timestamped `Item` list in `ContentView.swift` and `Item.swift`.
It also has starter unit and UI test targets. The deployment target is iOS 26.2
(iPhone and iPad), and the bundle ID is
`com.nextdaymobilestudio.LebenInDeutschland`. There are no app features, no
Firebase and no Swift package dependencies.

## Website

`website/` is a static HTML/CSS site with a landing page, `support/` and
`privacy/` (with an English version in `privacy/en/`). `netlify.toml` publishes
the folder and sets security headers (CSP, frame denial and others) and
redirects.

## Documentation

- [`docs/product-specification.md`](docs/product-specification.md): product
  rules (draft v0.1). Parts of it, for example cloud progress sync, are not
  implemented.
- [`docs/adr/`](docs/adr/): ADRs 0001–0013 are *Accepted*, and 0014 (ads) is
  *Proposed*.
- [`docs/android-audit/`](docs/android-audit/README.md): engineering audit from
  2026-11-05, findings register (`findings.md`), test-gap analysis and roadmap.
- [`docs/android-ads-release-checklist.md`](docs/android-ads-release-checklist.md):
  AdMob, Play Console, Data safety and privacy-policy steps before an ads
  release.

## AI-assisted development

AI tools are used for research, translation, repetitive implementation, test
discovery, documentation and review. `.claude/` contains the project's Claude
Code setup:

- **Read-only reviewer agents:** architecture, security, testing and Compose.
- **Skills:**
  - `/android-verify` runs Gradle checks through the allowlisted
    `run_gradle.sh` script and records the evidence.
  - `/android-audit` runs the reviewer agents and merges their findings.
  - `/android-pr` prepares PRs.

  The skills' Python helpers have their own tests under `.claude/**/tests/`.

Architecture, security, product behaviour, testing strategy and final code
ownership remain the developer's responsibility.

## Security notes

- Never commit any of these (`.gitignore` already blocks them):
  `google-services.json`, `GoogleService-Info.plist`, service-account or Admin
  SDK JSON files, `*.keystore` and `*.jks`, `keystore.properties`, `.env*`,
  `.local/`.
- Real AdMob IDs belong only in user-level Gradle properties or CI variables.
- Anonymous sign-in failures do not block a device that already has content
  installed. Only a first run without content reports the failure.

## Licence

No licence is granted. This repository is published so that the work can be read
and reviewed, not reused. All rights to the source code are reserved.

The German questions and answers come from the official state catalogue for the
*Leben in Deutschland* test and are not owned by this project. The AI-generated
translations are not redistributable either.
