-- A journey has one issuance process per request, and stays active after its first offer (EUD-359, design.md of the
-- service AD-16 and AD-18, section 5). Runs inside each <tenant>_issuance_portal schema. It only adds: no column or
-- table is dropped. journey.issuance_process_id stops being written and is dropped by a later migration.

-- The processes of a journey, one row per request that has one. Permanent and without personal data: it relates the
-- journey to the processes it created. request_id is the opaque id the Authentic source module gave the request.
CREATE TABLE journey_issuance (
    journey_id          UUID         NOT NULL REFERENCES journey (id),
    request_id          VARCHAR(64)  NOT NULL,
    issuance_process_id UUID         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    saved_at            TIMESTAMPTZ,
    PRIMARY KEY (journey_id, request_id),
    CONSTRAINT journey_issuance_process_key UNIQUE (issuance_process_id)
);

-- The journey is completed when its first offer is delivered; ended_at is now only the end of the journey (withdrawn,
-- ended by the holder or expired), so a completed journey is active while it has none.
ALTER TABLE journey ADD COLUMN completed_at TIMESTAMPTZ;

-- The processes the single-process model already recorded. Their request has no id of the new kind: they all get
-- the fixed id 'r1', which is also the id the Authentic source module gives the first request of a journey. Nothing
-- reads these journeys' sections again (they were completed before this migration).
INSERT INTO journey_issuance (journey_id, request_id, issuance_process_id, created_at)
SELECT id, 'r1', issuance_process_id, created_at FROM journey WHERE issuance_process_id IS NOT NULL;

-- The old rule tied ended_at to leaving in_progress: it goes first, because a completed journey may now have no end.
ALTER TABLE journey DROP CONSTRAINT journey_ended_only_when_closed;

-- Until now a journey completed with its offer and had its end at that moment: that moment is its completion. If its
-- session is still there it is an active completed journey, so it has no end yet.
UPDATE journey SET completed_at = ended_at WHERE state = 'completed';
UPDATE journey SET ended_at = NULL
 WHERE state = 'completed' AND EXISTS (SELECT 1 FROM journey_session s WHERE s.journey_id = journey.id);

-- Only a withdrawn journey must have ended; a journey in progress never has. A completed one has ended or not.
ALTER TABLE journey ADD CONSTRAINT journey_withdrawn_has_end
    CHECK (state <> 'withdrawn' OR ended_at IS NOT NULL);
ALTER TABLE journey ADD CONSTRAINT journey_in_progress_has_no_end
    CHECK (state <> 'in_progress' OR ended_at IS NULL);
ALTER TABLE journey ADD CONSTRAINT journey_completed_at_when_completed
    CHECK ((state = 'completed') = (completed_at IS NOT NULL));

-- The holder's unique index (journey_session_holder_ref_key) stays as it is: holder_ref lives in the temporary
-- session, which is deleted when the journey ends, so it already allows one active journey per holder.
