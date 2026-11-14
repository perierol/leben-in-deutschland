# Android ads: AdMob and Play Console release checklist

Covers the practice-question banner (ADR 0014). Statements marked *Google* come
from Google's documentation as read on 2026-11-13. Statements marked *to confirm*
were not verified against the Play Console or by legal review and must be checked
before release.

## 1. Values to provide (never commit them)

| Value | Where it is used |
|---|---|
| AdMob app ID (`ca-app-pub-…~…`) | Gradle property `lid.admob.appId`, merged into the manifest |
| `question_detail_banner` ad unit ID (`ca-app-pub-…/…`) | Gradle property `lid.admob.questionDetailBannerId`, read from `BuildConfig` |
| AdMob publisher ID (`pub-…`) | `app-ads.txt` on the developer website |

Set the two properties in `~/.gradle/gradle.properties` on the release machine,
or as `ORG_GRADLE_PROJECT_lid.admob.appId` and
`ORG_GRADLE_PROJECT_lid.admob.questionDetailBannerId` in CI. A release build
without them, or with Google's test IDs, fails in `validateReleaseAdMobIds`.
Debug builds ignore them and always use Google's test IDs:

- app ID `ca-app-pub-3940256099942544~3347511713`
- adaptive banner `ca-app-pub-3940256099942544/9214589741`

## 2. AdMob Console

- [ ] The app is registered in AdMob and linked to the Play listing.
- [ ] `question_detail_banner` is a banner ad unit. Choose the automatic refresh
      interval in the ad unit settings; the app relies on it and never requests a
      new ad per question (*Google*: refresh runs only while the banner is visible).
- [ ] Privacy & messaging: publish a European regulations (GDPR) message for the
      EEA, the UK and Switzerland. *Google*: the UMP SDK is IAB-certified for TCF
      v2.3, and limited ads apply without consent for TCF Purpose 1.
- [ ] Decide whether to publish a US state regulations message (*to confirm*).
- [ ] Payments and tax details are complete.
- [ ] `app-ads.txt` is published at the root of the developer website listed in
      the Play store listing. Copy the snippet AdMob shows for the account
      (*Google*'s example: `google.com, pub-0000000000000000, DIRECT`). Verification can
      take up to 24 hours. The file is not in `website/` yet because the
      publisher ID is not known.

## 3. Play Console: App content

- [ ] **Ads:** declare **Yes**. *Google*: apps with ads show a "Contains ads"
      label, and misrepresenting ads can lead to suspension.
- [ ] **Advertising ID:** declare that the app uses the advertising ID, for
      advertising and analytics. The Mobile Ads SDK adds the
      `com.google.android.gms.permission.AD_ID` permission (*Google*: declared by
      the SDK from version 20.4.0). The exact form wording is *to confirm*.
- [ ] **Target audience:** confirm adults, not children, consistent with the
      privacy policy. Families program requirements do not apply only if
      children are not a target audience (*to confirm*).
- [ ] **Privacy policy URL:** points to the updated policy.

## 4. Play Console: Data safety

Add the Google Mobile Ads SDK data, as Google's disclosure page lists it for SDK
version 25.5.0 (*Google*: "collects and shares … automatically for advertising,
analytics, and fraud prevention"). Map each row to the closest Play category and
confirm the mapping in the form (*to confirm*):

| Google's data type | Collected | Shared | Purposes |
|---|---|---|---|
| IP address (may estimate general location) | Yes | Yes | Advertising, analytics, fraud prevention |
| User product interactions (app launches, taps, video views) | Yes | Yes | Advertising, analytics, fraud prevention |
| Diagnostic information (launch time, hang rate, energy use) | Yes | Yes | Advertising, analytics, fraud prevention |
| Device or other IDs (Android advertising ID, app set ID) | Yes | Yes | Advertising, analytics, fraud prevention |

- [ ] Data is encrypted in transit (*Google*: TLS).
- [ ] Whether collection is optional, and whether processing is ephemeral, is
      *to confirm*: Google's page does not state ephemeral processing.
- [ ] Re-check the existing entries for Firebase Authentication and Firestore.
      The audit register already notes that the policy and form did not cover
      anonymous Firebase sign-in (`docs/android-audit/findings.md`).

## 5. Privacy policy

`website/privacy/en/index.html` and `website/privacy/index.html` now describe the
banner, the Mobile Ads SDK data, consent and privacy options. Before release:

- [ ] Legal review of the wording. The policy names no legal basis on purpose,
      because none has been verified.
- [ ] Publish the policy no later than the app release that contains ads.
- [ ] Separate open item: the policy still says the app "requires no user
      account" and does not mention anonymous Firebase sign-in.

## 6. Manual checks before release

- [ ] EEA consent form, with *Consent*, *Do not consent* and *Manage options*;
      Settings → Privacy options appears and changes take effect.
- [ ] Outside the EEA, no form is shown when none is required.
- [ ] Airplane mode on first launch and for a returning learner: onboarding,
      download (first launch needs network) and studying behave as before, and
      no banner band appears.
- [ ] Practice and Wrong Answers show the banner under the top bar. The timed
      mock exam, results, exam history, Home and Settings show none.
- [ ] Check, Next and the loading step keep the same ad; AdMob refresh replaces it
      without a layout jump.
- [ ] Font size up to 200 %, Arabic and Persian (right to left), dark theme, a
      small phone and landscape: no overlap, and the banner is hidden where
      there is no room.
- [ ] TalkBack reads the question and answers before the banner.
- [ ] Release build with the real IDs on an internal test track. Never tap live
      ads; register test devices in AdMob if real ad units must be checked.
