#!/usr/bin/env sh
set -eu

COMPOSE_FILE="infra/docker-compose/docker-compose.two-node.yml"

cleanup() {
  docker compose -f "$COMPOSE_FILE" down -v >/dev/null 2>&1 || true
}

trap cleanup EXIT

docker compose -f "$COMPOSE_FILE" config >/dev/null
docker compose -f "$COMPOSE_FILE" up -d --build

wait_for() {
  url="$1"
  i=0
  while [ "$i" -lt 60 ]; do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    i=$((i + 1))
    sleep 1
  done
  echo "Timed out waiting for $url" >&2
  return 1
}

wait_for "http://localhost:8080/actuator/health/readiness"
wait_for "http://localhost:8081/actuator/health/readiness"
curl -fsS "http://localhost:8080/actuator/health" >/dev/null
curl -fsS "http://localhost:8081/actuator/health" >/dev/null
curl -fsS "http://localhost:8080/api/v1/system/ping" >/dev/null
curl -fsS "http://localhost:8081/api/v1/system/ping" >/dev/null
curl -fsS "http://localhost:8080/api/v1/system/node" | grep -q '"nodeId":"local-node-1"'
curl -fsS "http://localhost:8081/api/v1/system/node" | grep -q '"nodeId":"local-node-2"'
