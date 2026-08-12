import unittest

from check_migration_immutability import MIGRATION_DIR, violations

V51 = f"{MIGRATION_DIR}/V51__user_theme.sql"


class MigrationImmutabilityTest(unittest.TestCase):
    """Editing an applied migration changes its Flyway checksum, which strands
    every database that recorded the old one. Adding is the only safe move."""

    def test_editing_an_existing_versioned_migration_is_a_violation(self):
        self.assertEqual([("modified", V51)], violations(f"M\t{V51}"))

    def test_adding_a_new_versioned_migration_is_allowed(self):
        found = violations(f"A\t{MIGRATION_DIR}/V52__campsite_notes.sql")

        self.assertEqual([], found)

    def test_deleting_a_versioned_migration_is_a_violation(self):
        self.assertEqual([("deleted", V51)], violations(f"D\t{V51}"))

    def test_renaming_reports_the_path_whose_checksum_was_recorded(self):
        renamed = f"R100\t{V51}\t{MIGRATION_DIR}/V51__user_theme_pref.sql"

        self.assertEqual([("renamed", V51)], violations(renamed))

    def test_repeatable_migrations_may_change(self):
        # Flyway re-runs an R__ migration when its checksum moves; that is what
        # repeatable means, so a comment pass over one is harmless.
        found = violations(f"M\t{MIGRATION_DIR}/R__grafana_reader_grants.sql")

        self.assertEqual([], found)

    def test_files_outside_the_migration_directory_are_ignored(self):
        found = violations("M\tbackend/src/main/kotlin/ca/floo/roadtrip/db/Db.kt")

        self.assertEqual([], found)

    def test_a_clean_diff_yields_nothing(self):
        self.assertEqual([], violations(""))

    def test_every_edited_migration_is_reported_not_just_the_first(self):
        diff = "\n".join(
            [
                f"M\t{V51}",
                f"A\t{MIGRATION_DIR}/V52__notes.sql",
                f"D\t{MIGRATION_DIR}/V50__x.sql",
            ]
        )

        self.assertEqual(
            [("modified", V51), ("deleted", f"{MIGRATION_DIR}/V50__x.sql")],
            violations(diff),
        )


if __name__ == "__main__":
    unittest.main()
