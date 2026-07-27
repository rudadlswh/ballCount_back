#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${project_dir}"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

env_value() {
  local key="$1"
  awk -v key="${key}" '
    index($0, key "=") == 1 {
      value = substr($0, length(key) + 2)
    }
    END { print value }
  ' .env
}

require_env() {
  local key="$1"
  local value
  value="$(env_value "${key}")"
  [[ -n "${value}" ]] || fail "${key} is missing or empty in .env"
  [[ "${value}" != *'<'* ]] || fail "${key} still contains a placeholder"
}

[[ -f .env ]] || fail "Create .env from .env.example first"
[[ "$(env_value SPRING_PROFILES_ACTIVE)" == "production" ]] || fail "SPRING_PROFILES_ACTIVE must be production"
[[ "$(env_value SERVER_PORT)" == "8088" ]] || fail "SERVER_PORT must be 8088"
[[ "$(env_value APP_DB_SCHEMA)" == "kbo_crawler_api" ]] || fail "APP_DB_SCHEMA must be kbo_crawler_api"
[[ "$(env_value APP_SYNC_ENABLED)" == "false" ]] || fail "First boot requires APP_SYNC_ENABLED=false"
[[ "$(env_value KBO_PUSH_ENABLED)" == "false" ]] || fail "First boot requires KBO_PUSH_ENABLED=false"

for key in \
  SPRING_DATASOURCE_URL \
  SPRING_DATASOURCE_USERNAME \
  SPRING_DATASOURCE_PASSWORD \
  KBO_ADMIN_API_KEY \
  ADMIN_PASSWORD \
  APNS_TEAM_ID \
  APNS_KEY_ID \
  APNS_BUNDLE_ID \
  APNS_PRIVATE_KEY_PATH; do
  require_env "${key}"
done

apns_container_path="$(env_value APNS_PRIVATE_KEY_PATH)"
case "${apns_container_path}" in
  /run/secrets/*)
    apns_host_path="secrets/${apns_container_path#/run/secrets/}"
    ;;
  *)
    fail "APNS_PRIVATE_KEY_PATH must point inside /run/secrets"
    ;;
esac
[[ -r "${apns_host_path}" ]] || fail "APNs key is not readable at ${apns_host_path}"

docker compose config --quiet
docker compose build
docker compose up -d

for attempt in $(seq 1 60); do
  if curl --fail --silent --show-error http://127.0.0.1:8088/healthz >/dev/null 2>&1; then
    docker compose ps
    curl -i http://127.0.0.1:8088/healthz
    echo "First boot succeeded with synchronization and push disabled."
    exit 0
  fi
  sleep 2
done

docker compose ps
docker compose logs --tail=300
fail "Health check did not return HTTP 200 within 120 seconds"
