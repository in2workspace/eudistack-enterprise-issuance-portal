-- Stand-in for the platform-owned tenant registry (eudistack-platform-dev/local-env/postgres/init-databases.sh).
CREATE TABLE IF NOT EXISTS public.tenant_registry (
    schema_name   VARCHAR(64)  PRIMARY KEY,
    display_name  VARCHAR(255) NOT NULL,
    tenant_type   VARCHAR(20)  NOT NULL DEFAULT 'simple',
    status        VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO public.tenant_registry (schema_name, display_name, tenant_type, status) VALUES
    ('sandbox', 'EUDIStack Sandbox', 'multi_org', 'active'),
    ('kpmg',    'KPMG',              'simple',    'active'),
    ('retired', 'Retired tenant',    'simple',    'inactive')
ON CONFLICT (schema_name) DO NOTHING;
