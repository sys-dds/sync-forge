#!/usr/bin/env sh
set -eu

URL="${1:?url required}"
TIMEOUT_SECONDS="${2:-120}"
DEADLINE=$(( $(date +%s) + TIMEOUT_SECONDS ))

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if curl -fsS "$URL" >/dev/null 2>&1; then
    exit 0
  fi
  sleep 2
done

echo "Timed out waiting for $URL" >&2
exit 1
