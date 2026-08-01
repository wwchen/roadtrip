-- sandbox_scrub.sql
--
-- Post-restore PII scrub for sandbox environments.
--
-- The availability_watch subtree is now INCLUDED in snapshots (rows are no
-- longer excluded via --exclude-table-data) so that reviewers see real
-- availability data.  The single PII column — trigger_config (JSONB holding
-- email recipients and Slack notification channels) — is blanked here while
-- preserving every watch row and all FK integrity.
--
-- Applied in deploy.sh immediately after pg_restore succeeds, inside the
-- fresh-restore branch (not the re-up skip path — already scrubbed on first up).

UPDATE availability_watch
   SET trigger_config = '{}'::jsonb
 WHERE trigger_config <> '{}'::jsonb;
