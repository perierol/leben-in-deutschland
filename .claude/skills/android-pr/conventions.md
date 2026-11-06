# PR conventions

Taken from merged PRs #27–#34 (checked 2026-11-06). The repository has no PR template or CONTRIBUTING file. PRs are squash-merged into `main` by the owner on GitHub, so the PR title becomes the commit subject on `main` as `<title> (#<n>)`.

## Branch names
`<type>/<area>-<short-kebab-description>`, matching `^(feat|fix|test|docs|chore|refactor|ci|build)/[a-z0-9][a-z0-9-]*$`.

Examples: `fix/android-mock-exam-submit-integrity`, `test/android-coroutine-test-toolchain`, `docs/android-audit-post-merge-findings`, `chore/android-ai-workflow`.

## Title
Conventional Commits: `<type>(<scope>): <lower-case imperative summary>`, without a final full stop, at most about 72 characters.
- Types: `feat`, `fix`, `test`, `docs`, `chore`, `refactor`, `ci`, `build`.
- Scopes: `android` for the Android app and its tooling, `web` for `website/`, `content` for `scripts/content/`.
- Examples: `fix(android): keep mock-exam submissions consistent at the deadline`, `docs(android): register post-merge audit findings for #33`.

The branch type and the title type match.

## Commit message (`commit-msg.txt`)
- **Subject:** the PR title.
- **Body:** wrapped at 72 columns. State what changed and why, in short paragraphs or `-` bullets. Name the `F-xx` findings it addresses. End with one line on verification (counts and commands) and any known limitation.
- **Trailer:** end with a blank line, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`, as the attribution instructions require.

## Body
Use [pr-template.md](pr-template.md). The sections and their order follow #32–#34:
1. **Why** (or **Problem** for fixes, with the `F-xx` IDs) or **Summary** for docs.
2. **Changes**: grouped by file or area, with tables for many small items. Say what is deliberately **unchanged** (UI, Room schema, DataStore format, DI) when a reader would wonder.
3. **Test-first**: for fixes only, the RED → GREEN table and the regression test names.
4. **Verification**: split into *Verified* (with sources) and **Not verified**.
5. **Known limitations and follow-ups**.
6. The last line: `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

Style: plain sentences, exact commands in backticks, file references as `path:line`, counts as `n/n`. Never write "should pass", "probably" or "CI is green" without a source.
