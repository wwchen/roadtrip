-- RFC 0007 / PR 3.5: dimension tables are now seeded from
-- config/poi-registry.yaml at backend boot (PoiRegistrySync). This
-- migration intentionally does nothing — kept as a placeholder so
-- existing flyway_schema_history rows on deployed environments stay
-- consistent across the rename.
--
-- To add a governing_body or booking_provider, edit config/poi-registry.yaml
-- and restart the backend. Boot UPSERTs; orphaned references (POI rows
-- pointing at slugs that the YAML has dropped) refuse to apply.
SELECT 1;
