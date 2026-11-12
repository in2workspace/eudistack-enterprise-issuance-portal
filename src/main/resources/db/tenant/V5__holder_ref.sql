-- The journey is found by its holder, not by a cookie (EUD-314, design.md §3.2, AD-9). Runs inside each
-- <tenant>_issuance_portal schema.

-- Hex of the HMAC-SHA256 of the token's iss and sub under the environment's key: the holder, never the sub. It lives
-- in the temporary session, not in the permanent journey: the journey is reachable by its holder exactly while its
-- session exists (in progress, or completed and not yet closed), and deleting the session releases the holder. The
-- unique index guarantees one reachable journey per holder. Sessions opened with the cookie before this migration keep
-- it empty: nobody finds them, and the expiry job closes them (EC-04).
ALTER TABLE journey_session ADD COLUMN holder_ref CHAR(64);
CREATE UNIQUE INDEX journey_session_holder_ref_key ON journey_session (holder_ref);

-- The session is addressed by its journey, no longer by the hash of a cookie.
ALTER TABLE journey_session DROP CONSTRAINT journey_session_pkey;
ALTER TABLE journey_session DROP COLUMN session_id_hash;
ALTER TABLE journey_session DROP CONSTRAINT journey_session_journey_id_key;
ALTER TABLE journey_session ADD PRIMARY KEY (journey_id);
