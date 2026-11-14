# Product Specification

**Product:** Leben in Deutschland
**Document Status:** Draft
**Version:** 0.1
**Last Updated:** 2026-10-10

## 1. Purpose

Leben in Deutschland is a multilingual, offline-first study application for Germany’s Leben in Deutschland and naturalization tests.

The application helps users study the German source questions, understand them through translated content and AI-generated explanations, review incorrectly answered questions, and complete timed mock examinations.

Android is the primary implementation platform. The iOS application will follow the same product rules and content model.

## 2. Product Principles

- The German question and answer wording is the authoritative source content.
- Translations and explanations are supporting learning content.
- AI-generated translations and explanations may contain errors.
- Users must be able to study offline after the initial content download.
- Local storage is the immediate source of truth for content and progress.
- Important business rules must behave consistently on Android and iOS.
- Published content releases must be immutable and versioned.
- Incomplete or invalid content must never replace the last valid local release.

## 3. Supported Languages

The first release supports the following 21 languages:

| Language | Code | Direction |
|---|---|---|
| German | `de` | LTR |
| English | `en` | LTR |
| Turkish | `tr` | LTR |
| Ukrainian | `uk` | LTR |
| Arabic | `ar` | RTL |
| Romanian | `ro` | LTR |
| Polish | `pl` | LTR |
| Russian | `ru` | LTR |
| Bulgarian | `bg` | LTR |
| Croatian | `hr` | LTR |
| Bosnian | `bs` | LTR |
| Serbian, Latin alphabet | `sr-Latn` | LTR |
| Greek | `el` | LTR |
| Albanian | `sq` | LTR |
| Persian/Dari | `fa` | RTL |
| Pashto | `ps` | RTL |
| Kurdish, Kurmanji | `ku` | LTR |
| Italian | `it` | LTR |
| Spanish | `es` | LTR |
| Hungarian | `hu` | LTR |
| French | `fr` | LTR |

Changing the selected language must update the entire application interface, question translations, answers, explanations, notices, and error messages.

## 4. Content Scope

The content catalog contains 460 German source questions:

- 300 general questions
- 160 state-specific questions
- 10 questions for each of Germany’s 16 federal states

After a state is selected, the active study pool contains:

- 300 general questions
- 10 questions belonging to the selected state
- 310 active questions in total

The complete language package may contain all 460 questions so that users can change their selected state without downloading the content again.

Every question must have a stable identifier. User progress must be connected to the stable question identifier and must not depend on the question’s position in an array.

## 5. Question Content

Each question must contain:

- Stable question identifier
- Official question number, when available
- General or state-specific scope
- State code, when applicable
- Original German question
- Original German answer options
- Correct answer identifier
- Required question or answer images
- Translation in the selected language
- Translated answer options
- AI-generated explanation in the selected language
- Content and translation review metadata

The application must clearly distinguish between:

- Original German question and answer content
- AI-generated translations
- AI-generated explanations

AI-generated German explanations are not part of the authoritative source content.

## 6. Content Disclosure

The application must display a clear notice during onboarding and make it accessible later from Settings or About.

The notice must explain that:

- German questions and answers are treated as the original source content.
- Translations were generated with AI and may contain errors.
- Explanations were generated with AI and may contain errors.
- Users should refer to official sources when authoritative information is required.

## 7. First Launch Flow

The first launch requires an internet connection.

The user flow is:

1. Select an application language.
2. Select a German federal state.
3. Review the content and AI disclosure.
4. Create or restore an anonymous Firebase Authentication identity.
5. Fetch the active content manifest.
6. Download the package for the selected language.
7. Validate the package version, schema, hashes, question count, and required fields.
8. Import the validated package into the local database atomically.
9. Save the selected language and state.
10. Open the Home screen.

The selected language package must include:

- Original German content
- Translation in the selected language
- AI-generated explanation in the selected language
- Question metadata
- Correct answer identifiers
- Required offline image content

The user must not enter the main study experience until a complete and valid package has been stored locally.

## 8. Language Changes

Users may change the application language from Settings.

If the new language package is not available on the device:

- An internet connection is required.
- The application must download and validate the new package.
- The current language and content must remain active during the download.
- The application must switch languages only after the new package has been validated and saved successfully.
- A failed download must not leave the application in a mixed-language or incomplete state.

Offline switching to a language that has not been downloaded is not required in the first release.

## 9. State Selection

The state selection screen must display all 16 German federal states.

The selected state determines which 10 state-specific questions are included in:

- Practice Mode
- Wrong Answers Mode
- Mock Exams

Users may change their selected state later from Settings.

Changing the state must not delete general-question progress. State-specific progress may remain stored and become available again if the user selects the previous state later.

## 10. Home Screen

The Home screen must provide access to:

- Practice Mode
- Wrong Answers
- Mock Exam
- Settings
- Content and AI information

The Home screen should also display useful progress information, such as:

- Number of studied questions
- Number of correct answers
- Number of questions in Wrong Answers
- Most recent mock exam result

Exact visual design will be defined separately.

## 11. Practice Mode

Practice Mode uses the active pool of 310 questions.

### 11.1 Default Order

Questions are presented sequentially by default.

The user may enable shuffle mode before starting a new practice session.

A shuffled session must keep a stable question order until the session is completed or explicitly restarted.

### 11.2 Question Navigation

- Each question is displayed on its own page.
- Users can move forward and backward.
- The application preserves the selected answer and question state during the session.
- Previously answered questions remain visible when navigating backward.
- A practice session can resume after the application is closed.

### 11.3 Answer Feedback

After selecting an answer:

- The selected answer is locked for the current attempt.
- The application immediately shows whether the answer is correct or incorrect.
- The correct answer is displayed.
- The AI-generated explanation is displayed.
- The German original remains available alongside the selected translation.

### 11.4 Wrong Answer Behaviour

If a question is answered incorrectly in Practice Mode:

- It is added to the Wrong Answers list.
- Its incorrect-attempt count is updated.
- The local progress is saved immediately.
- Cloud synchronization is attempted in the background.

Answering the same question correctly in normal Practice Mode does not remove it from the Wrong Answers list.

A question is removed only when it is answered correctly in Wrong Answers Mode.

## 12. Wrong Answers Mode

Wrong Answers Mode contains questions currently marked for review.

The user can study only these questions.

The mode follows these rules:

- Each question is displayed on its own page.
- Forward and backward navigation is supported.
- Feedback is shown immediately.
- One correct answer in Wrong Answers Mode removes the question from the list.
- Another incorrect answer keeps the question in the list.
- Progress is saved locally immediately.
- Cloud synchronization is attempted in the background.

If there are no questions in the list, the application displays a clear empty state.

## 13. Mock Exam

A mock exam contains 33 questions:

- 30 randomly selected general questions
- 3 randomly selected questions from the user’s selected state

The time limit is 60 minutes.

### 13.1 Exam Behaviour

- Questions are presented without immediate feedback.
- Users can move forward and backward.
- Users may change answers before submission.
- Unanswered questions are clearly identifiable.
- The same generated question set is preserved if the application is closed and reopened.
- The timer is based on a persisted deadline and continues while the application is in the background.
- If the deadline has passed, the exam is automatically submitted.

### 13.2 Submission

The exam is submitted when:

- The user confirms manual submission, or
- The 60-minute timer expires

After submission, answers cannot be changed.

### 13.3 Result Screen

The result screen displays:

- Total correct answers
- Total incorrect answers
- Total unanswered questions
- Leben in Deutschland result
- Naturalization test result
- Review of each question
- Correct answer for each incorrect or unanswered question

The two result thresholds are:

- 15 or more correct answers: Leben in Deutschland threshold reached
- 17 or more correct answers: Naturalization test threshold reached

These thresholds must be verified against current official guidance before production release.

Incorrect and unanswered mock-exam questions are added to the Wrong Answers list.

## 14. User Identity

Visible registration or login is not required in the first release.

The application creates an anonymous Firebase Authentication identity automatically.

The anonymous identity is used for:

- Associating cloud progress with a user identifier
- Synchronizing progress
- Restoring the anonymous session when Firebase supports it
- Securing access to user-specific Firestore documents

The application must not display a login screen during normal onboarding.

Anonymous authentication does not guarantee progress recovery after uninstalling the application, clearing application data, losing the device, or losing the anonymous Firebase identity. This limitation must be documented.

## 15. Progress Storage

Progress is stored both locally and in Firestore.

### 15.1 Local Storage

Local storage is the immediate source of truth.

The application must save locally:

- Selected language
- Selected state
- Installed content release and language package
- Practice progress
- Selected answers
- Wrong Answers membership
- Incorrect-attempt counts
- Active mock exam
- Mock exam deadline
- Mock exam answers
- Previous mock exam results

User actions must not wait for a Firestore response before appearing successful in the interface.

### 15.2 Cloud Synchronization

Cloud progress synchronization happens asynchronously.

The synchronization process must:

- Be retryable
- Avoid blocking normal study
- Restrict users to their own progress documents
- Preserve local progress during network failures
- Use stable question identifiers
- Handle repeated writes safely

Detailed conflict-resolution rules will be defined in an architecture decision record.

## 16. Offline Behaviour

After the first successful content download, the following must work without an internet connection:

- Opening the application
- Practice Mode
- Wrong Answers Mode
- Mock Exam
- Viewing explanations
- Viewing required question images
- Saving local progress
- Changing the selected state
- Resuming an active session or mock exam

The following may require an internet connection:

- Initial setup
- Downloading a language that is not installed
- Downloading a new content release
- Restoring cloud progress
- Synchronizing local progress with Firestore

If synchronization fails, the user must still be able to continue studying.

## 17. Content Updates

The application reads a public content manifest to determine the active release.

When a new release is available:

1. Download it into temporary local storage.
2. Validate its schema version, hashes, package metadata, and required content.
3. Import it in a database transaction.
4. Activate it only after successful validation.
5. Keep the previous valid release available until the new release is active.

A failed or interrupted update must not remove the last valid local content.

Published releases are immutable. Corrections require a new release identifier.

## 18. Schema 3 Requirement

The active `schema-2` release stores the German source and all 20 translations inside the same question objects.

Firestore returns complete documents and cannot download only one language field from these chunk documents. Therefore, `schema-2` does not support the selective-download requirement.

Before production client integration, a new immutable `schema-3` release must be prepared.

`schema-3` must provide independent language packages. Each package should include the German source content and one selected translation.

Example structure:

`contentReleases/{releaseId}/languagePacks/{languageCode}/chunks/{chunkId}`

A Turkish package contains:

- German source questions and answers
- Turkish questions and answers
- Turkish AI-generated explanations
- Correct answer identifiers
- Question metadata
- Required image content

The existing `schema-2` release must not be deleted. It remains available for rollback and historical traceability.

The content must be transformed into `schema-3`; existing translations do not need to be generated again solely because of the schema migration.

## 19. Explanation Readiness

The current catalog does not yet satisfy the final explanation requirement.

The current inspection found:

- All 460 German explanation fields are empty.
- 229 English explanations contain a generic placeholder.
- 229 Turkish explanations contain a generic placeholder.
- Similar placeholder content may exist in other languages.

Before the catalog is marked production-ready:

- Every question must have a meaningful explanation.
- Placeholder explanations must be rejected by validation.
- Explanations must help the user understand why the correct answer is correct.
- Explanations must not claim to be official source content.
- Explanations must be checked for factual consistency.
- Generated explanations must retain AI-generated review metadata.

A dedicated content-validation rule must prevent incomplete explanations from being published accidentally.

## 20. Error Handling

The application must provide understandable and recoverable error states for:

- No internet connection during initial setup
- Anonymous authentication failure
- Manifest download failure
- Language-package download failure
- Invalid package hash
- Unsupported schema version
- Insufficient device storage
- Local database import failure
- Cloud synchronization failure

Errors must provide a retry action when recovery is possible.

Technical exception messages must not be shown directly to users.

## 21. Accessibility and Layout

The application must support:

- Dynamic text sizing
- Screen readers
- Sufficient color contrast
- Minimum touch-target sizes
- Clear selected, correct, and incorrect answer states
- Reduced reliance on color alone
- Right-to-left layouts for Arabic, Persian/Dari, and Pashto

When the application interface uses a right-to-left language, the original German question block must remain readable in left-to-right direction.

## 22. Security and Privacy

- Administrative Firebase credentials must never be included in either mobile application.
- Service-account files must never be committed to the repository.
- OpenAI API keys must never be included in either mobile application.
- Mobile clients must not be able to create, change, or delete published content releases.
- Users may read public published content.
- Users may access only their own progress data.
- Firestore Security Rules must be tested before production release.
- Only the minimum user data required for application functionality should be stored.

## 23. First-Release Non-Goals

The following are outside the first-release scope:

- Mandatory visible account registration
- Email/password authentication
- Google or Apple login
- Payments or subscriptions
- Advertisements other than the single practice-question banner described in ADR 0014
- Social features
- Leaderboards
- Administrator web console
- Human translation-review interface
- Guaranteed cross-device recovery for anonymous users
- Downloading all 21 question-language packages during first launch

## 24. Acceptance Criteria

The first release is considered product-complete when:

- Users can select one of 21 languages.
- Users can select one of 16 federal states.
- The application downloads only the selected language package containing German and translated content.
- All required content works offline after the initial download.
- Practice Mode supports sequential and shuffled sessions.
- Users can navigate forward and backward between questions.
- Incorrect answers are added to Wrong Answers.
- A correct answer in Wrong Answers Mode removes the question.
- A 33-question, 60-minute mock exam can be completed and resumed.
- Result thresholds of 15 and 17 are displayed separately.
- Incorrect and unanswered mock questions are added to Wrong Answers.
- Local progress works without network access.
- Anonymous cloud synchronization works without visible registration.
- RTL languages display correctly.
- German source content is clearly distinguished from AI-generated content.
- Invalid or incomplete content releases cannot replace the last valid local release.
- No administrative credential is included in the repository or mobile applications.

## 25. Risks and Dependencies

- Official-source licensing and attribution must be verified before public release.
- Question-image licensing and offline distribution rights must be verified.
- AI-generated translations require quality review.
- AI-generated explanations require factual review.
- Placeholder explanations must be completed before production release.
- Mock-exam thresholds must be checked against current official requirements.
- Anonymous accounts provide limited recovery after application deletion or device loss.
- Firestore Security Rules and progress synchronization require dedicated testing.
- Both Android and iOS must implement the same business rules.