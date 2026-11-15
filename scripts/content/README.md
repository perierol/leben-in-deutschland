# Schema 3 Content Pipeline

This directory contains dependency-free Python tooling for converting the
existing schema-2 catalog into self-contained schema-3 language packages.

## Safety

- Source catalogs and generated releases belong under `.local/`.
- `.local/` must remain ignored by Git.
- Production builds reject missing explanations, repeated placeholders, and
  explanations without verified provenance metadata.
- `--allow-incomplete` and `--allow-draft` are for local inspection only.
- These scripts do not connect to Firebase and cannot activate a release.

## Run the Tests

From the repository root:

```bash
python3 -m unittest discover \
  -s scripts/content/tests \
  -p "test_*.py" \
  -v
```

## Regenerate the iOS Canonical JSON Fixtures

The iOS app recomputes release hashes and must match `canonical_bytes` and
`sha256_json` byte for byte. Its expected values come from this script:

```bash
python3 scripts/content/generate_ios_canonical_json_fixtures.py
```

It writes `ios/LebenInDeutschlandTests/Fixtures/canonical-json-fixtures.json`.
`test_ios_canonical_json_fixtures.py` fails when the committed file no longer
matches the publisher's encoding.

## Build the Current Catalog as a Draft

The current schema-2 catalog contains incomplete explanations, so the first
build must be explicitly marked as incomplete:

```bash
python3 scripts/content/build_schema3.py \
  --input .local/content/schema-2.json \
  --output .local/generated/schema-3 \
  --allow-incomplete
```

The command writes:

```text
.local/generated/schema-3/
├── release.json
├── build-report.json
└── language-packs/
    └── {languageCode}/
        ├── package.json
        └── chunks/
            ├── 0000.json
            └── ...
```

## Validate a Draft Build

```bash
python3 scripts/content/validate_schema3.py \
  --input .local/generated/schema-3 \
  --allow-draft
```

Draft validation confirms structural integrity but returns
`readyForUpload: false` while quality issues remain.

## Production Validation

Do not use draft flags for production:

```bash
python3 scripts/content/build_schema3.py \
  --input .local/content/schema-2.json \
  --output .local/generated/schema-3

python3 scripts/content/validate_schema3.py \
  --input .local/generated/schema-3
```

A production build succeeds only when every package is structurally valid and
all required explanations pass the readiness checks.

## Complete AI Explanations Safely

`explanation_pipeline.py` prepares and applies OpenAI Batch API jobs without
editing the source catalog in place. Request files, manifests, batch state, and
generated catalogs must remain under `.local/`.

The workflow deliberately has two stages:

1. Generate missing German explanations from the authoritative German question
   and correct answer.
2. Translate those German explanations into localization fields that are
   missing, contain a repeated placeholder, or lack verified explanation
   provenance. This prevents legacy text from passing readiness checks merely
   because it is non-empty and unique.

The prepare and apply commands are local and make no API calls. Submission
requires both `OPENAI_API_KEY` and the explicit confirmation value `SUBMIT`.

Create and activate an ignored local virtual environment:

```bash
python3 -m venv .local/venv
source .local/venv/bin/activate
python -m pip install -r scripts/content/requirements-openai.txt
```

Prepare the German request file:

```bash
python scripts/content/explanation_pipeline.py prepare-german \
  --input .local/content/schema-2.json \
  --output .local/explanations/german-requests.jsonl
```

Review the summary before submitting. `readyForSubmit` must be `true` and
`blockedTargetCount` must be `0`. Image-only correct answers without textual
descriptions are blocked instead of allowing the model to invent visual facts.

Submit only after review:

```bash
python scripts/content/explanation_pipeline.py submit \
  --input .local/explanations/german-requests.jsonl \
  --manifest .local/explanations/german-requests.manifest.json \
  --state .local/explanations/german-batch.json \
  --confirm-submit SUBMIT
```

Check status and download a completed batch:

```bash
python scripts/content/explanation_pipeline.py status \
  --state .local/explanations/german-batch.json

python scripts/content/explanation_pipeline.py download \
  --state .local/explanations/german-batch.json \
  --output .local/explanations/german-output.jsonl
```

Apply results to a new catalog after the batch completes:

```bash
python scripts/content/explanation_pipeline.py apply \
  --input .local/content/schema-2.json \
  --manifest .local/explanations/german-requests.manifest.json \
  --batch-output .local/explanations/german-output.jsonl \
  --output .local/content/schema-2-with-german-explanations.json
```

Prepare translations from the completed German explanations:

```bash
python scripts/content/explanation_pipeline.py prepare-translations \
  --input .local/content/schema-2-with-german-explanations.json \
  --output .local/explanations/translation-requests.jsonl
```

Use the same submit, status, download, and apply commands for the translation
manifest. After applying translations, rebuild schema 3 without
`--allow-incomplete` and run production validation. AI-generated explanations
remain marked as needing human review even when structural validation passes.

## Generate German Memory Keys Safely

`memory_key_pipeline.py` creates short German question-to-answer mnemonic pairs.
Questions whose correct answer is an image are deliberately skipped. Generated
keys are marked as needing human review and the apply step rejects missing or
duplicate pairs.

Prepare 441 text-answer requests from the production source catalog:

```bash
python scripts/content/memory_key_pipeline.py prepare \
  --input .local/content/schema-2-complete-explanations-v2.json \
  --output .local/memory-keys/requests.jsonl
```

Review the manifest before submission. For the current 460-question catalog it
must report `requestCount: 441`, `skippedCount: 19`, and
`readyForSubmit: true`.

```bash
python scripts/content/memory_key_pipeline.py submit \
  --input .local/memory-keys/requests.jsonl \
  --manifest .local/memory-keys/requests.manifest.json \
  --state .local/memory-keys/batch.json \
  --confirm-submit SUBMIT

python scripts/content/memory_key_pipeline.py status \
  --state .local/memory-keys/batch.json

python scripts/content/memory_key_pipeline.py download \
  --state .local/memory-keys/batch.json \
  --output .local/memory-keys/output.jsonl
```

If a validated batch remains queued and direct processing is explicitly
preferred, cancel it first and wait until its status is `cancelled`:

```bash
python scripts/content/memory_key_pipeline.py cancel \
  --state .local/memory-keys/batch.json \
  --confirm-cancel CANCEL

python scripts/content/memory_key_pipeline.py status \
  --state .local/memory-keys/batch.json
```

Then run the same requests through the standard Responses API. Successful
responses are appended and synced to disk immediately. Re-running the command
resumes only missing requests; `--batch-state` prevents duplicate processing
while the old batch is still active.

```bash
python scripts/content/memory_key_pipeline.py run-direct \
  --input .local/memory-keys/requests.jsonl \
  --manifest .local/memory-keys/requests.manifest.json \
  --output .local/memory-keys/output.jsonl \
  --batch-state .local/memory-keys/batch.json \
  --workers 5 \
  --confirm-run RUN_DIRECT
```

Apply the completed results to a new catalog and validate all invariants:

```bash
python scripts/content/memory_key_pipeline.py apply \
  --input .local/content/schema-2-complete-explanations-v2.json \
  --manifest .local/memory-keys/requests.manifest.json \
  --batch-output .local/memory-keys/output.jsonl \
  --output .local/content/schema-2-with-memory-keys.json

python scripts/content/memory_key_pipeline.py validate \
  --input .local/content/schema-2-with-memory-keys.json
```

Build and validate a new immutable schema-3 release. Memory keys are an
additive optional schema-3 field, so older clients can still synchronize the
release and ignore the new object. When at least one memory key is present, the
builder requires complete coverage of textual correct answers and no keys for
image answers.

```bash
python scripts/content/build_schema3.py \
  --input .local/content/schema-2-with-memory-keys.json \
  --output .local/generated/schema-3-memory-keys \
  --dataset-version 2026-10-27.memory-keys

python scripts/content/validate_schema3.py \
  --input .local/generated/schema-3-memory-keys
```

## Publish Schema 3 to Firestore

`firestore_upload_schema3.py` keeps validation, upload, verification, and
activation separate. It never deletes an older release. The active manifest is
changed only by the explicit `activate` command after a complete remote
comparison succeeds.

Install the pinned Firebase Admin dependency inside the ignored local virtual
environment:

```bash
source .local/venv/bin/activate
python -m pip install -r scripts/content/requirements-firebase.txt
```

First run the local-only plan. This command does not require credentials and
performs no network calls or writes:

```bash
python scripts/content/firestore_upload_schema3.py plan \
  --input .local/generated/schema-3-production
```

For remote commands, point Application Default Credentials to a Firebase
service-account file outside the repository:

```bash
export GOOGLE_APPLICATION_CREDENTIALS="/absolute/path/to/service-account.json"
```

Upload the immutable release without activating it. Both confirmation values
must exactly match the intended target:

```bash
python scripts/content/firestore_upload_schema3.py upload \
  --input .local/generated/schema-3-production \
  --project-id leben-in-deutschland-38a07 \
  --confirm-project leben-in-deutschland-38a07 \
  --confirm-release lid-2026-10-05-103327-schema-3
```

Read every remote release document back and compare it with the local bundle:

```bash
python scripts/content/firestore_upload_schema3.py verify \
  --input .local/generated/schema-3-production \
  --project-id leben-in-deutschland-38a07
```

Activation is a separate final operation. It atomically updates only
`contentManifest/current` and records the previous release ID for rollback:

```bash
python scripts/content/firestore_upload_schema3.py activate \
  --input .local/generated/schema-3-production \
  --project-id leben-in-deutschland-38a07 \
  --confirm-project leben-in-deutschland-38a07 \
  --confirm-release lid-2026-10-05-103327-schema-3 \
  --confirm-activate ACTIVATE
```

The service-account file, `.local/` content, generated releases, and batch
outputs must never be committed to Git.
