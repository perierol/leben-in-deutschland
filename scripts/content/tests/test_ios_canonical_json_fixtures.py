from __future__ import annotations

import sys
import unittest
from pathlib import Path

CONTENT_DIR = Path(__file__).resolve().parents[1]
if str(CONTENT_DIR) not in sys.path:
    sys.path.insert(0, str(CONTENT_DIR))

from generate_ios_canonical_json_fixtures import FIXTURE_PATH, render_fixtures


class IosCanonicalJsonFixturesTest(unittest.TestCase):
    def test_committed_fixtures_match_the_publisher_encoding(self) -> None:
        # Fails when build_schema3's canonical encoding changes without regenerating
        # the iOS fixtures, which would let the two implementations drift apart.
        self.assertEqual(
            FIXTURE_PATH.read_text(encoding="utf-8"),
            render_fixtures(),
            "Run scripts/content/generate_ios_canonical_json_fixtures.py",
        )


if __name__ == "__main__":
    unittest.main()
