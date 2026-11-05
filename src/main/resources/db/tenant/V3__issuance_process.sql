-- A journey has a single issuance process (EUD-277, design.md AD-12, §5). Runs inside each
-- <tenant>_issuance_portal schema. The list column was never written or read.
ALTER TABLE journey ADD COLUMN issuance_process_id UUID;
ALTER TABLE journey DROP COLUMN issuance_process_ids;
