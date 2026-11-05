#!/usr/bin/env bash
set -euo pipefail

for variable in CONTAINER_IMAGE CONTAINER_NAME DAST_DB_HOST DAST_DB_PORT \
  DAST_DB_NAME DAST_DB_USERNAME DAST_DB_PASSWORD; do
  if [[ -z "${!variable:-}" ]]; then
    echo "::error::Missing required DAST variable: ${variable}"
    exit 1
  fi
done

# Same settings as local-env/compose.yaml in eudistack-platform-dev: local crypto and mail, one proxy in front.
docker run -d --name "${CONTAINER_NAME}" \
  --network host \
  -e SERVER_PORT=8080 \
  -e DB_URL="jdbc:postgresql://${DAST_DB_HOST}:${DAST_DB_PORT}/${DAST_DB_NAME}" \
  -e DB_USERNAME="${DAST_DB_USERNAME}" \
  -e DB_PASSWORD="${DAST_DB_PASSWORD}" \
  -e PORTAL_ENV=test \
  -e PORTAL_CRYPTO_PROVIDER=local \
  -e PORTAL_LOCAL_DATA_KEY="$(openssl rand -base64 32)" \
  -e PORTAL_MAIL_PROVIDER=local \
  -e PORTAL_MAIL_FROM=noreply@mail.local.eudistack.net \
  -e PORTAL_TRUSTED_PROXIES=1 \
  "${CONTAINER_IMAGE}"

# The service creates the sandbox schema at startup; its portal configuration is loaded at tenant creation,
# never by a migration, so it is loaded here once the table exists.
config_file="$(dirname "${BASH_SOURCE[0]}")/portal-config-sandbox.json"
export PGPASSWORD="${DAST_DB_PASSWORD}"
psql_dast() {
  psql -h "${DAST_DB_HOST}" -p "${DAST_DB_PORT}" -U "${DAST_DB_USERNAME}" -d "${DAST_DB_NAME}" -v ON_ERROR_STOP=1 "$@"
}
for _ in $(seq 1 60); do
  if [[ "$(psql_dast -tAc "SELECT to_regclass('sandbox_issuance_portal.portal_config') IS NOT NULL")" == "t" ]]; then
    psql_dast -v config="$(cat "${config_file}")" <<'SQL'
INSERT INTO sandbox_issuance_portal.portal_config (tenant, config)
VALUES ('sandbox', :'config'::jsonb)
ON CONFLICT (tenant) DO NOTHING;
SQL
    exit 0
  fi
  sleep 2
done
echo "::error::sandbox_issuance_portal.portal_config was not created within 120 s"
docker logs "${CONTAINER_NAME}"
exit 1
