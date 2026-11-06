# Merge rules

Apply these in order. The aim is one list without duplicates, in which every rating can be traced back to the reviewer that gave it.

## 1. Normalize

Convert every item from every reviewer into this record. Keep the reviewer's original ID (for example `AR-2`).

| Field | Source |
|---|---|
| Original ID | `CR-n`, `AR-n`, `TR-n`, `SR-n` (defects), or `KM-n`, `TI-n`, `SH-n`, `C-n` (non-defects) |
| Reviewer | compose, architecture, testing, security |
| Section | Defect (section A of each reviewer) or non-defect (KM, TI, SH, privacy C, optional C) |
| Title, Category, Type | as given |
| Severity, Confidence | as given, **verbatim** |
| Location | `path:lines` as given |
| Evidence, Impact, Fix, Regression test, Verification method | as given, shortened only for length |
| Related audit ID | as given (`F-xx` or `new`) |
| Reviewer NEW/EXISTING label | diff scopes only |
| Reviewer review status | Complete review / Partial review, with the reason |

Items a reviewer lists only as "Additional lower-priority items: N" are counted, not expanded. Record the count per reviewer.

## 2. Match against `docs/android-audit/findings.md`

Read the register in full. For each item:

| Condition | Register status |
|---|---|
| The reviewer cites `F-xx`, the cited code still matches the register entry, and there is no new evidence | **Existing (F-xx)** |
| The reviewer cites `F-xx` and gives new evidence (a new location, a regression, a wider trigger, a changed status) | **Existing, new evidence (F-xx)**. Say what is new in one line. |
| The item matches a register entry's location and mechanism, but the reviewer did not cite it | **Existing (F-xx)**, with a note "matched by coordinator" |
| The item is already described in a register entry's dated **Status**, **Remaining** or **Remaining limitation** note | **Documented limitation (F-xx)** |
| No register entry matches | **Not in register** (classified further in step 4) |

Also check the opposite direction for every register entry whose cited files are in scope. If the cited code has visibly changed, list the entry as **Possibly resolved: Needs verification**. Never mark a register entry Fixed.

## 3. Merge duplicates across reviewers

Two items are duplicates when they cite the same file with overlapping lines **and** describe the same mechanism. Merge them into one record:

- **Headline rating:** the rating from the reviewer that owns the category.

  | Category | Owner |
  |---|---|
  | UI state, lifecycle in UI, accessibility, RTL and localization, Compose performance | compose |
  | Architecture, DI and scoping, Room, sync, data integrity, concurrency, ADR drift | architecture |
  | Test gaps, test quality, fakes, CI test gating | testing |
  | Rules, auth, secrets, platform exposure, backup, supply chain, privacy | security |

- **Other ratings:** every other reviewer's rating is listed verbatim under *Also raised by*, for example `AR-1 Medium/Probable`.
- **Disagreements:** if the ratings differ by more than one level, or the confidence differs, add the item to *Reviewer disagreements*, with both ratings and one line on why each reviewer rated it so.
- **Different mechanisms:** an item about a missing test and an item about the production bug that test would catch are **not** duplicates. Link them with "see AUD-n" instead.

## 4. Classify

Give every merged defect item exactly one class. Apply the rows in this order and stop at the first that matches.

| Class | Rule |
|---|---|
| **Documented limitation** | Documented limitation (F-xx) from step 2. |
| **Existing** | Existing (F-xx), or Existing with new evidence, from step 2. |
| **Regression** | Diff scopes only. *Not in register*, and **all** of the following hold: (1) the item is about **production behaviour**: a Defect or Risk in production code or configuration, not a test or documentation item; (2) the **change introduced it**: the problematic production lines fall inside changed hunks of the diff, or the reviewer traced the cause to code the change added or altered; (3) the behaviour did **not exist before** the change. |
| **New** | Everything else that is *Not in register*. This includes: a pre-existing issue discovered now; an issue the change merely **exposed** or made more important without introducing it; and every **test coverage or test quality gap**. In `full`, area, feature and path scopes, write "New (origin unknown)", because without a diff the cause cannot be dated. |

Clarifications:
- **Test gaps are never Regressions.** A missing, weak or incorrect test is *New*, unless the register already documents it, in which case it is *Existing* or *Documented limitation*. If the change deleted or weakened an existing test, keep the class *New*, and add "test removed or weakened by this change" to the item.
- **"Exposed" is not "introduced".** A reviewer's NEW label means "introduced or exposed". Read the reviewer's evidence to decide which. If the cited production lines are unchanged by the diff and the reviewer does not show that the change created the behaviour, the item is *New*.
- **When unsure,** choose *New* and say why in the item's classification note. Do not choose *Regression* without evidence for all three conditions.

**Ratings are untouched by classification.** The class never changes a reviewer's Severity, Confidence or Type. They stay verbatim, and only step 6 may lower confidence, under its rules.

**Record every classification disagreement** in the report's *Reviewer disagreements* section, one line each, with:
- the item ID;
- the reviewer's label (NEW or EXISTING, or the reviewer's own wording such as "exposed by this change");
- the coordinator's class;
- the rule that decided it (for example "register wins", "test gap → New", "exposed, not introduced").

When the register and a reviewer's label disagree, the register wins. Also add a short classification note to the item itself.

## 5. Non-defect items

Do not rank `KM`, `TI`, `SH`, privacy `C` or optional-improvement items against defects. Group them by kind in the report's *Non-defect items* section, with duplicates removed using the same rule as step 3. Each privacy item keeps its severity and "needs product/legal decision".

## 6. Verification marks (coordinator)

Re-read the cited lines yourself for every Critical and High defect and for every item in the top N.

| Mark | When |
|---|---|
| **Verified** | The code at the cited lines shows what the item claims. |
| **Unverified** | It was not re-read (below the top N), or the claim depends on runtime behaviour, console settings or a device. |
| **Rejected** | The re-read shows the claim is wrong. Give a one-line reason, move the item to the appendix, and keep its original rating. |

- You may **lower** confidence only when the re-read shows the claim is narrower, and you must write `Confidence: Probable (reviewer: Confirmed)`.
- Never raise severity or confidence.
- Never change a reviewer's Type.

## Ranking

Sort the merged defect items by these keys, in order:

1. Severity: Critical > High > Medium > Low.
2. Class: Regression > New > Existing > Documented limitation.
3. Confidence: Confirmed > Probable > Needs verification.
4. Verification: Verified > Unverified.

Number them `AUD-1`, `AUD-2` and so on, in that order, with no gaps. The prioritized table shows the top N (default 10). Every remaining item is listed after it in the same order, one line each.
