# Leben in Deutschland

[![Android CI](https://github.com/perierol/leben-in-deutschland/actions/workflows/android-ci.yml/badge.svg)](https://github.com/perierol/leben-in-deutschland/actions/workflows/android-ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A multilingual, offline-first study application for Germany's Leben in Deutschland and naturalization tests.

<!-- Screenshots: drop 3–4 PNGs into docs/screenshots/ and uncomment.
| Home | Study session | Mock exam | Languages |
| --- | --- | --- | --- |
| ![Home](docs/screenshots/home.png) | ![Study session](docs/screenshots/study.png) | ![Mock exam](docs/screenshots/exam.png) | ![Languages](docs/screenshots/languages.png) |
-->

The Android app is in closed testing on Google Play.

The project is being developed as a production-minded Android and iOS application. Its purpose is not only to deliver a useful learning product, but also to demonstrate architecture, testing, performance, accessibility, security, and cross-platform engineering decisions.

## Project Status

The Android application is feature complete for its first release and is in closed
testing on Google Play. iOS has not been started beyond the project skeleton.

| Area | Status |
| --- | --- |
| Firebase content catalog | Prepared |
| Firestore release | Uploaded and activated |
| Android user flows | Home, study session, wrong-answer review, timed mock exam, exam history, settings, language and state selection |
| Android test suite | 19 unit tests, 13 Compose UI tests |
| Android CI | Active (build, lint, unit tests) |
| Google Play | Closed testing |
| Android release automation | Pending |
| Compose UI tests in CI | Pending — they require a device and are not run yet |
| iOS application | Project skeleton only |
| iOS CI | Pending |

## Product Scope

- 460 German source questions from the official catalog
- 300 general questions
- 160 state-specific questions
- 21 supported languages
- German original content shown alongside translations
- Practice mode
- Wrong Answers study mode
- Timed mock examinations
- Anonymous Firebase authentication
- Offline-first content and progress
- Right-to-left support for Arabic, Persian/Dari, and Pashto

## Technology

### Android — in use

- Kotlin
- Jetpack Compose and Material 3
- Navigation 3
- Coroutines and Flow
- Room, with schema export
- DataStore
- Firebase Authentication (anonymous) and Cloud Firestore
- kotlinx.serialization

### Android — planned

- Hilt for dependency injection
- WorkManager for background content refresh
- Baseline profiles

### iOS — planned

The iOS target currently contains only the generated Xcode project. The technology
choices below are the intended direction, not the current state.

- Swift and SwiftUI
- SwiftData
- Swift Concurrency
- Firebase Authentication and Cloud Firestore

## Engineering Goals

- Keep the local database as the source of truth
- Support reliable offline usage after the first content download
- Publish content through immutable and versioned Firestore releases
- Keep business rules independent from the user interface
- Build testable and maintainable application layers
- Verify performance with measurable results
- Support accessibility and right-to-left layouts
- Document important architecture decisions
- Protect administrative credentials and user progress

## AI-Assisted Development

AI tools are used to accelerate research, translation, repetitive implementation, test-case discovery, documentation, and code review.

Architecture, security, product behavior, validation, testing strategy, performance decisions, and final code ownership remain the responsibility of the developer.

AI-generated translations will be clearly disclosed in the applications and may require human review.

## Repository Structure

```text
android/          Android application
ios/              iOS application (project skeleton only)
docs/             Product and engineering documentation
docs/adr/         Architecture Decision Records
firestore.rules   Cloud Firestore security rules
firebase.json     Firebase CLI configuration
scripts/content/  Content build, validation, and upload tooling
website/          Public landing page and privacy policy
```

## Licence

The source code in this repository is released under the [MIT Licence](LICENSE).

The licence covers the code only. The German questions and answers come from the
official state catalogue for the Leben in Deutschland test and are not owned by
this project; the translations are generated and are not covered by the MIT grant
either. Treat the contents of `scripts/content` as tooling, not as licensed content.

## Security and Content Notice

Administrative credentials, service-account files, private keys, and environment variables are never stored in this repository.

Cloud Firestore serves the content catalogue only to an authenticated caller. The
application signs in anonymously before its first content sync, which keeps the
question catalogue and its translations from being downloaded by anyone who knows
the project ID, without asking users to create an account. Writes are refused for
all clients; the content pipeline publishes through a service account.

Anonymous sign-in is invisible: there is no sign-in screen, no credentials, and no
prompt. If it fails — offline, without Play Services, or during an outage — a device
that already holds the content it needs keeps working on what is installed, and only
a first run reports the failure.

The German questions and answers are treated as the authoritative source content. Translations were generated with AI and may contain errors.