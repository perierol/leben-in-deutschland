# PR body template

Fill in every section. If a section doesn't apply, write one line saying why, rather than dropping it. Remove these instructions and the `<…>` placeholders.

```markdown
## <Why | Problem (audit findings F-xx) | Summary>

<Two to five sentences: what was wrong or missing, who it affects, and what this PR does about it.>

## Changes

<Grouped by file or area. Use a table for many similar items. End with what is deliberately unchanged, when a reader would wonder.>

## Test-first

<Fix PRs: a RED → GREEN table and the regression test names, with evidence paths. Other PRs: "Not applicable: <reason>.">

## Verification

### Verified (local)
- `<exact command>`: <result, counts>. <Source: android-verify run `<run>`, fingerprint `<sha12:tree>`, <fresh | executed | partly up to date>.>

### Verified (GitHub CI)
- <check name>: <conclusion> on `<sha>` (<run link>). <Or, before CI has run: "Pending: recorded with `/android-pr status` after the PR is opened."> <Or: "Android CI: not triggered (path filter; no files in android/ or its workflow).">

### Not verified
- <each missing, stale or legacy item, each Unverified gate, and each manual check not done, with its reason>

## Known limitations and follow-ups

- <limitation or follow-up, with an F-xx ID or "new">

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```
