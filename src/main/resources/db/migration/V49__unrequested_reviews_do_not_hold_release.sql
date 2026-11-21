-- A prescription or investigation the doctor never issued no longer holds a
-- release bundle (ReleaseBundleComponent.isSettled). Bundles already waiting
-- only on such components become READY now instead of on their next change.
UPDATE release_bundles b
SET b.status = 'READY'
WHERE b.status = 'INCOMPLETE'
  AND NOT EXISTS (
      SELECT 1
      FROM release_bundle_components c
      WHERE c.bundle_id = b.id
        AND c.is_complete = FALSE
        AND c.not_required = FALSE
        AND NOT (c.is_required = FALSE
                 AND c.component_type IN ('PRESCRIPTION', 'INVESTIGATION'))
  );
