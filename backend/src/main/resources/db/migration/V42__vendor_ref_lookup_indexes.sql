-- V39 intentionally removed the one-vendor-ref-per-row uniqueness constraint
-- from the catalog link tables, but catalog matching still joins from
-- vendor_ref_id back to linked rows. Keep that lookup indexed without
-- restoring uniqueness.
CREATE INDEX campground_vendor_refs_vendor_ref_idx
  ON campground_vendor_refs (vendor_ref_id, campground_id);

CREATE INDEX campsite_vendor_refs_vendor_ref_idx
  ON campsite_vendor_refs (vendor_ref_id, campsite_id);
