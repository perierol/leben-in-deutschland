#!/usr/bin/env python3
"""Write the canonical JSON fixtures that the iOS app's tests compare against.

Every expected value comes from the publisher's own `build_schema3.canonical_bytes`
and `build_schema3.sha256_json`, the functions that produce the chunk, package and
catalog hashes in a schema-3 release. The iOS encoder must reproduce them byte for
byte. The output is deterministic, so rerunning the script must not change the file.

Usage (from the repository root):

    python3 scripts/content/generate_ios_canonical_json_fixtures.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

CONTENT_DIR = Path(__file__).resolve().parent
if str(CONTENT_DIR) not in sys.path:
    sys.path.insert(0, str(CONTENT_DIR))

from build_schema3 import canonical_bytes, sha256_json  # noqa: E402

REPOSITORY_ROOT = CONTENT_DIR.parents[1]
FIXTURE_PATH = (
    REPOSITORY_ROOT
    / "ios"
    / "LebenInDeutschlandTests"
    / "Fixtures"
    / "canonical-json-fixtures.json"
)

CASES: list[tuple[str, str, Any]] = [
    (
        "booleans",
        "true and false stay booleans and never become 1 or 0.",
        {"enabled": True, "disabled": False, "flags": [True, False, True]},
    ),
    (
        "integral-floats",
        "Whole-number floats keep their decimal point; integers do not gain one.",
        {
            "one": 1.0,
            "zero": 0.0,
            "negativeZero": -0.0,
            "hundred": 100.0,
            "integerOne": 1,
            "integerZero": 0,
        },
    ),
    (
        "fractional-floats",
        "Shortest round-trip digits, as in real translation match scores.",
        {"scores": [0.907657, 0.9842, 0.1 + 0.2, 123456.789, -2.5]},
    ),
    (
        "float-exponents",
        "Python switches to exponent notation below 1e-4 and from 1e16.",
        {
            "small": 0.0001,
            "tiny": 1e-05,
            "belowLarge": 9.5e15,
            "large": 1e16,
            "big": 1e22,
            "largest": 1.7976931348623157e308,
            "smallestSubnormal": 5e-324,
        },
    ),
    (
        "integers",
        "64-bit integer range and ordinary values.",
        {
            "officialNumber": 301,
            "negative": -42,
            "int64Max": 9223372036854775807,
            "int64Min": -9223372036854775808,
        },
    ),
    (
        "nested-objects",
        "Keys are sorted at every level; array order is preserved.",
        {
            "z": {"b": [{"y": 1, "x": 2}, [], {}], "a": None},
            "a": {"c": {"e": "f", "d": "g"}},
            "M": "uppercase sorts before lowercase",
        },
    ),
    (
        "key-ordering",
        "Keys sort by Unicode code point, not by locale or case-insensitively.",
        {
            "b": 1,
            "a": 2,
            "B": 3,
            "A": 4,
            "_": 5,
            "1": 6,
            "10": 7,
            "2": 8,
            "aa": 9,
            "z": 10,
            "ä": 11,
            "é": 12,
            "Ω": 13,
        },
    ),
    (
        "arabic",
        "Arabic and mixed right-to-left text is written as raw UTF-8.",
        {
            "questionText": "هل أنت مستعد للتعلّم؟",
            "answers": ["نعم", "لا", "ربما"],
            "mixed": "Bundesland: برلين (Berlin) 2026",
        },
    ),
    (
        "multilingual-text",
        "Non-ASCII text is never escaped and never Unicode-normalized.",
        {
            "german": "„Die Würde des Menschen ist unantastbar.“ – Straße",
            "turkish": "Çalışmaya hazır mısınız? İğdır",
            "ukrainian": "Конституція",
            "greek": "Σύνταγμα",
            "emoji": "📘✅",
            "precomposed": "é",
            "decomposed": "e\u0301",
        },
    ),
    (
        "quotes-and-escapes",
        "Only quotes, backslashes and control characters are escaped.",
        {
            "doubleQuote": 'Er sagte "Ja"',
            "singleQuote": "it's",
            "backslash": "C:\\path\\file",
            "slash": "a/b </script>",
            "newline": "line1\nline2",
            "tab": "a\tb",
            "carriageReturn": "a\r\nb",
            "backspaceAndFormFeed": "\b\f",
            "otherControl": "\u0000\u0001\u001f",
            "delete": "\u007f",
            "lineSeparators": "\u2028\u2029",
            "typographicQuotes": "„…“ «…» ‚…‘",
        },
    ),
    (
        "empty-values",
        "Empty containers and strings.",
        {"emptyObject": {}, "emptyArray": [], "emptyString": "", "nested": [[], {}, [[]]]},
    ),
    (
        "top-level-array",
        "A top-level array of mixed values, like a chunk's questions list.",
        [3, "two", 1.0, True, None, {"b": 2, "a": 1}],
    ),
    ("top-level-string", "A bare string.", "Leben in Deutschland"),
    ("top-level-null", "A bare null.", None),
    (
        "question-shaped",
        "A synthetic question with the schema-3 structure.",
        [
            {
                "id": "q-0021",
                "correctAnswerId": "c",
                "metadata": {
                    "sourceId": "21",
                    "officialNumber": 21,
                    "scope": "GENERAL",
                    "stateCode": None,
                    "categoryStatus": "UNCATEGORIZED",
                    "source": {"page": 7, "bamfInternalId": "x-21"},
                },
                "german": {
                    "questionText": "Was steht nicht im Grundgesetz?",
                    "answers": [
                        {"id": "a", "text": "Die Würde des Menschen ist unantastbar."},
                        {"id": "b", "text": "Alle sollen gleich viel Geld haben."},
                        {"id": "c", "text": "Jeder Mensch darf seine Meinung sagen."},
                        {"id": "d", "text": "Alle sind vor dem Gesetz gleich."},
                    ],
                    "questionImage": {
                        "storagePath": "images/q-0021.png",
                        "sha256": "0" * 64,
                        "mimeType": "image/png",
                        "width": 640,
                        "height": 480,
                    },
                },
                "study": {
                    "languageCode": "ar",
                    "questionText": "ما الذي لا يرد في القانون الأساسي؟",
                    "answers": [
                        {"id": "a", "text": "كرامة الإنسان مصونة."},
                        {"id": "b", "text": "يجب أن يملك الجميع المال نفسه."},
                        {"id": "c", "text": "يحق لكل إنسان أن يقول رأيه."},
                        {"id": "d", "text": "الجميع متساوون أمام القانون."},
                    ],
                    "explanation": "الشرح \"المساعد\" ليس نصًا رسميًا.",
                    "explanationStatus": "AI_GENERATED_NEEDS_REVIEW",
                    "reviewed": False,
                    "audit": {
                        "questionMatchScore": 1.0,
                        "answerSetMatchScore": 0.0,
                        "answerMatchScores": [1.0, 0.907657, 0.9842, 1.0],
                        "fallbackAnswerIds": [],
                    },
                },
            }
        ],
    ),
    (
        "package-hashes",
        "The packageHashes map whose hash is a release's catalogSha256.",
        {"tr": "1" * 64, "de": "2" * 64, "ar": "3" * 64},
    ),
]


def build_fixtures() -> dict[str, Any]:
    return {
        "generatedBy": "scripts/content/generate_ios_canonical_json_fixtures.py",
        "canonicalFunction": "scripts/content/build_schema3.py canonical_bytes, sha256_json",
        "cases": [
            {
                "name": name,
                "description": description,
                "input": value,
                "canonical": canonical_bytes(value).decode("utf-8"),
                "utf8ByteCount": len(canonical_bytes(value)),
                "sha256": sha256_json(value),
            }
            for name, description, value in CASES
        ],
    }


def render_fixtures() -> str:
    return json.dumps(build_fixtures(), ensure_ascii=False, indent=2) + "\n"


def main() -> int:
    FIXTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    FIXTURE_PATH.write_text(render_fixtures(), encoding="utf-8")
    print(f"Wrote {len(CASES)} cases to {FIXTURE_PATH.relative_to(REPOSITORY_ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
