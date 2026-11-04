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
