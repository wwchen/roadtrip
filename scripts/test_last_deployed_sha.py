#!/usr/bin/env python3
"""The deploy baseline must be a commit that shipped, not a run that was green.

Deploy diffs the pushed commit against the last deployed commit so a runtime
change whose CI failed can't be stranded by a later doc-only push. Every test
here pins the distinction the old lookup got wrong: a Deploy *workflow run* can
conclude `success` while its `deploy` *job* never ran.
"""

from __future__ import annotations

import unittest

from last_deployed_sha import deploy_job_succeeded, select_deployed_sha


def runs(*pairs: tuple[int, str]) -> list[dict]:
    """Newest-first run list, the order the runs API returns."""
    return [{"id": run_id, "head_sha": sha} for run_id, sha in pairs]


def jobs(**conclusion_by_run: str | None):
    """Job lists keyed by run id, as `select_deployed_sha` asks for them.

    A `None` conclusion means the run has no `deploy` job at all.
    """
    table = {
        int(run_id.lstrip("r")): (
            [] if conclusion is None else [{"name": "deploy", "conclusion": conclusion}]
        )
        for run_id, conclusion in conclusion_by_run.items()
    }
    return lambda run_id: table[run_id]


class DeployJobEvidenceTest(unittest.TestCase):
    def test_a_succeeded_deploy_job_is_evidence_of_a_deploy(self) -> None:
        self.assertTrue(deploy_job_succeeded([{"name": "deploy", "conclusion": "success"}]))

    def test_a_skipped_deploy_job_is_not(self) -> None:
        # The path gate's own doing: the workflow is green, nothing shipped.
        self.assertFalse(deploy_job_succeeded([{"name": "deploy", "conclusion": "skipped"}]))

    def test_a_failed_or_cancelled_deploy_job_is_not(self) -> None:
        for conclusion in ("failure", "cancelled", "timed_out", None):
            with self.subTest(conclusion=conclusion):
                self.assertFalse(
                    deploy_job_succeeded([{"name": "deploy", "conclusion": conclusion}])
                )

    def test_a_run_without_a_deploy_job_is_not(self) -> None:
        # Covers a renamed job too: no evidence means no baseline, which deploys.
        self.assertFalse(deploy_job_succeeded([{"name": "changes", "conclusion": "success"}]))


class BaselineSelectionTest(unittest.TestCase):
    def test_skips_the_newest_green_run_whose_deploy_job_was_skipped(self) -> None:
        """The regression this whole module exists for.

        Sequence: a runtime commit `aaa` deploys; a doc-only commit `bbb` passes
        CI and Deploy runs green with the deploy job skipped. Trusting the run
        conclusion would baseline at `bbb` and the next push would diff from a
        commit that was never deployed.
        """
        sha = select_deployed_sha(
            runs((2, "bbb"), (1, "aaa")),
            jobs(r2="skipped", r1="success"),
        )

        self.assertEqual("aaa", sha)

    def test_walks_past_a_whole_streak_of_undeployed_runs(self) -> None:
        sha = select_deployed_sha(
            runs((5, "eee"), (4, "ddd"), (3, "ccc"), (2, "bbb"), (1, "aaa")),
            jobs(r5="skipped", r4="skipped", r3=None, r2="skipped", r1="success"),
        )

        self.assertEqual("aaa", sha)

    def test_takes_the_newest_run_that_did_deploy(self) -> None:
        sha = select_deployed_sha(
            runs((3, "ccc"), (2, "bbb"), (1, "aaa")),
            jobs(r3="success", r2="success", r1="success"),
        )

        self.assertEqual("ccc", sha)

    def test_no_deployed_run_in_the_window_yields_no_baseline(self) -> None:
        # Empty means "unknown", and the workflow deploys on unknown.
        self.assertEqual("", select_deployed_sha(runs((2, "bbb"), (1, "aaa")), jobs(r2="skipped", r1="failure")))

    def test_empty_history_yields_no_baseline(self) -> None:
        self.assertEqual("", select_deployed_sha([], jobs()))

    def test_stops_asking_about_jobs_once_it_has_an_answer(self) -> None:
        """One API call per candidate, so the walk must not run past its answer."""
        asked: list[int] = []
        table = jobs(r3="skipped", r2="success", r1="success")

        def jobs_for_run(run_id: int):
            asked.append(run_id)
            return table(run_id)

        select_deployed_sha(runs((3, "ccc"), (2, "bbb"), (1, "aaa")), jobs_for_run)

        self.assertEqual([3, 2], asked)


if __name__ == "__main__":
    unittest.main()
