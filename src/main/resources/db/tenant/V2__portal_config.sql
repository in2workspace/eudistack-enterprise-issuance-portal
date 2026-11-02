-- Portal configuration of the tenant (design.md AD-7, §5). Runs inside each <tenant>_issuance_portal schema.
-- Only the table: the row of each tenant is loaded at tenant creation, never by a migration.
CREATE TABLE portal_config (
    tenant      VARCHAR(63)  PRIMARY KEY,
    config      JSONB        NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
