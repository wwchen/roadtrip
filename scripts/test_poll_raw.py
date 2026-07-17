import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import poll_raw


def source(slug: str, depends_on: list[str] | None = None) -> poll_raw.Source:
    return poll_raw.Source(
        slug=slug,
        enabled=True,
        fetcher_enabled=True,
        executor="python3",
        filename=f"scripts/{slug}.py",
        output_dir_prefix=Path("data/raw") / slug,
        depends_on=depends_on or [],
    )


class PollRawTest(unittest.TestCase):
    def test_dependency_chain_runs_dependencies_before_target(self) -> None:
        index = source("tesla-index")
        locations = source("tesla-locations", ["tesla-index"])

        chain = poll_raw.dependency_chain(locations, [locations, index])

        self.assertEqual(["tesla-index", "tesla-locations"], [s.slug for s in chain])

    def test_dependency_chain_deduplicates_shared_dependencies(self) -> None:
        base = source("base")
        mid = source("mid", ["base"])
        target = source("target", ["base", "mid"])

        chain = poll_raw.dependency_chain(target, [target, mid, base])

        self.assertEqual(["base", "mid", "target"], [s.slug for s in chain])

    def test_dependency_chain_rejects_missing_dependencies(self) -> None:
        target = source("target", ["missing"])

        with self.assertRaisesRegex(RuntimeError, "target depends on unknown source missing"):
            poll_raw.dependency_chain(target, [target])


if __name__ == "__main__":
    unittest.main()
