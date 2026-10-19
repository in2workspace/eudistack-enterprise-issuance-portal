-- Journey and its session (design.md AD-2, §5). Runs inside each <tenant>_issuance_portal schema.

-- Permanent record of the holder's journey. Never deleted and never holds personal data.
CREATE TABLE journey (
    id                    UUID         PRIMARY KEY,
    state                 VARCHAR(20)  NOT NULL,
    step                  VARCHAR(30)  NOT NULL,
    withdrawal_reason     VARCHAR(20),
    identification_method VARCHAR(30),
    issuance_process_ids  TEXT[]       NOT NULL DEFAULT '{}',
    created_at            TIMESTAMPTZ  NOT NULL,
    last_activity         TIMESTAMPTZ  NOT NULL,
    ended_at              TIMESTAMPTZ,
    CONSTRAINT journey_state_valid
        CHECK (state IN ('in_progress', 'completed', 'withdrawn')),
    CONSTRAINT journey_step_valid
        CHECK (step IN ('started', 'identified', 'entitlement_confirmed', 'offer_obtained')),
    CONSTRAINT journey_withdrawal_reason_valid
        CHECK (withdrawal_reason IN ('cancelled', 'not_entitled', 'expired')),
    -- A reason exists exactly when the journey was withdrawn.
    CONSTRAINT journey_withdrawal_reason_only_when_withdrawn
        CHECK ((state = 'withdrawn') = (withdrawal_reason IS NOT NULL)),
    -- Only a journey that left in_progress has an end.
    CONSTRAINT journey_ended_only_when_closed
        CHECK ((state = 'in_progress') = (ended_at IS NULL))
);

-- The expiry job looks for journeys in progress by inactivity.
CREATE INDEX journey_state_last_activity_idx ON journey (state, last_activity);

-- Temporary session of a journey in progress. Personal data, encrypted by the application.
-- The row is physically deleted when the journey leaves in_progress or the session closes.
CREATE TABLE journey_session (
    session_id_hash  CHAR(64)  PRIMARY KEY,
    journey_id       UUID      NOT NULL UNIQUE REFERENCES journey (id),
    identity         BYTEA,
    entitlement      BYTEA,
    issuance         BYTEA,
    email            BYTEA
);

-- ShedLock: the expiry job runs on a single instance per tenant (design.md AD-4).
CREATE TABLE shedlock (
    name        VARCHAR(64)   PRIMARY KEY,
    lock_until  TIMESTAMPTZ   NOT NULL,
    locked_at   TIMESTAMPTZ   NOT NULL,
    locked_by   VARCHAR(255)  NOT NULL
);
