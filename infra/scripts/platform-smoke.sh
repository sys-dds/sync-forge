#!/usr/bin/env sh
set -eu

API_BASE_URL="${PLATFORM_API_BASE_URL:-http://localhost:8080}"
WEBHOOK_SINK_URL="${PLATFORM_WEBHOOK_SINK_URL:-http://localhost:8090}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/../docker-compose/docker-compose.platform.yml"

# Standalone mode only: validate and run the self-contained platform compose file.
docker compose -f "$COMPOSE_FILE" config >/dev/null
docker compose -f "$COMPOSE_FILE" up -d --build

"$SCRIPT_DIR/wait-for-http.sh" "$API_BASE_URL/actuator/health/readiness"
curl -fsS "$API_BASE_URL/actuator/health" >/dev/null
"$SCRIPT_DIR/wait-for-http.sh" "$WEBHOOK_SINK_URL"

if [ -n "${PLATFORM_API_KEY:-}" ]; then
  if [ -n "${PLATFORM_WORKSPACE_SLUG:-}" ]; then
    curl -fsS \
      -H "X-API-Key: ${PLATFORM_API_KEY}" \
      -H "X-Workspace-Slug: ${PLATFORM_WORKSPACE_SLUG}" \
      "$API_BASE_URL/api/v1/workspaces/current" >/dev/null
  else
    curl -fsS \
      -H "X-API-Key: ${PLATFORM_API_KEY}" \
      "$API_BASE_URL/api/v1/workspaces/current" >/dev/null
  fi
fi
