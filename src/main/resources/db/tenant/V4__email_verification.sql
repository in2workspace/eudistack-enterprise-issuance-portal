-- The holder's email is verified with a code before the confirmation (EUD-358, design.md §3.6, AD-13 to AD-15).
-- Runs inside each <tenant>_issuance_portal schema.

-- The code sent to the holder (a hash, never the code), its expiry, attempts and sends. Personal data, encrypted
-- by the application like the other sections.
ALTER TABLE journey_session ADD COLUMN email_verification BYTEA;

-- A journey reaches email_verified between entitlement_confirmed and offer_obtained.
ALTER TABLE journey DROP CONSTRAINT journey_step_valid;
ALTER TABLE journey ADD CONSTRAINT journey_step_valid
    CHECK (step IN ('started', 'identified', 'entitlement_confirmed', 'email_verified', 'offer_obtained'));

-- Until now the email section held the address typed by the holder, which nobody had verified. Only a verified
-- email may reach the confirmation, so none of those survives. Safe: before the email_verification column no
-- verified email can exist.
UPDATE journey_session SET email = NULL;
