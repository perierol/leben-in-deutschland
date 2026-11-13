# ADR 0014: Show one AdMob banner on practice question screens

## Status

Proposed

## Context

The product specification listed advertisements as a first-release non-goal.
The product owner has decided to show advertising through the existing AdMob ad
unit `question_detail_banner`, limited to practice questions.

Google's banner policy warns against banners "immediately next to" navigation
buttons such as a "next" button, and against screens where users interact
continuously. Practice questions are such a screen, so the placement needs
explicit separation. The timed mock exam keeps running while the app is in the
background (product specification §13.1), so an accidental ad tap there would
cost the learner exam time.

The app is offline-first. Startup, studying and content download must not depend
on the network, on consent, or on an ad being available.

## Decision

- **Placement.** One small adaptive banner on the practice question screen,
  which also serves Wrong Answers review. It sits in a fixed band under the top
  bar, separated by a divider, and is never next to the answers or the
  Check/Next button. The timed mock exam, all result screens, exam history,
  Home and Settings show no ads.
- **Stability.** The route creates and loads one banner per screen visit, outside
  the question content. Check, Next and the loading step between questions keep
  the same banner. AdMob's own refresh replaces the ad; the app never requests a
  new ad when the question changes. A failed request keeps its space until the
  next question, and a banner never appears in the middle of a question.
- **Size.** An inline adaptive size capped at 60 dp keeps the band small and
  fixed. The banner is omitted when the screen height, scaled by the font size,
  is too small (`QuestionBannerLayout.fitsScreen`), which includes phones in
  landscape.
- **SDKs.** Google Mobile Ads SDK `com.google.android.gms:play-services-ads`
  25.5.0, which Google's documentation currently labels "Legacy", and the User
  Messaging Platform (UMP) `com.google.android.ump:user-messaging-platform`
  4.0.0. The GMA Next-Gen SDK was not chosen for this first integration: Google
  publishes its Play Data safety disclosure for the Legacy SDK, and no
  deprecation date for it was found. Revisit when Google recommends a migration.
- **Consent.** `ConsentAwareAdsController` follows Google's UMP flow: update the
  consent information, check `canRequestAds()` at once, show the consent form if
  required, and initialize the Mobile Ads SDK off the main thread once ads are
  allowed. It runs when Home is shown, so the form never covers onboarding, the
  content download or a question. Settings shows "Privacy options" when UMP
  requires it. Every failure leaves ads unavailable; nothing else waits for it.
- **IDs.** Debug builds always use Google's published test IDs. Release builds
  read the real app ID and ad unit ID from the Gradle properties
  `lid.admob.appId` and `lid.admob.questionDetailBannerId`, which are never
  committed. `validateReleaseAdMobIds` fails a release build when they are
  missing, malformed or Google test IDs, and `AdMobIds.usableAdUnitId` turns ads
  off at runtime if a test ID reaches a release build.
- **Tests.** Instrumented tests run in `AdsDisabledTestApplication`, and banner
  tests use a fake `BannerAdFactory`, so no automated test requests consent or
  ads.

## Consequences

- The store listing shows "Contains ads", and the privacy policy and Data safety
  form must describe the Mobile Ads SDK (see
  `docs/android-ads-release-checklist.md`).
- `run_gradle.sh release` and any release build need the two Gradle properties.
- The practice screen loses about 77 dp of height to the banner band when ads are
  available.
- Ad policy compliance depends on the separation being kept. Changes to the
  practice screen's top bar or action area must keep the banner away from
  interactive controls.
