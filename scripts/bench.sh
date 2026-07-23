#!/usr/bin/env bash
# Resets the DB, boots the app, runs the k6 spike, prints the audit result.
#
# Prerequisites on PATH: docker (with compose v2), mvn, java, k6, curl.
# These match the project's fixed stack -- nothing here is optional tooling.
set -euo pipefail

cd "$(dirname "$0")/.."

APP_JAR="target/ticket-booking-system-0.1.0.jar"
APP_URL="http://localhost:8080"
LOG_FILE="$(mktemp -t ticket-booking-app.XXXXXX.log)"

echo "==> Resetting database and cache (docker compose down -v)"
docker compose down -v

echo "==> Starting MySQL and Redis"
docker compose up -d --wait

echo "==> Building the application"
mvn -q -DskipTests package

echo "==> Starting the application (log: $LOG_FILE)"
java -jar "$APP_JAR" > "$LOG_FILE" 2>&1 &
APP_PID=$!
trap 'kill "$APP_PID" 2>/dev/null || true' EXIT

echo "==> Waiting for the application to become available"
for _ in $(seq 1 60); do
  if curl -sf "$APP_URL/api/events/1/availability" > /dev/null; then
    break
  fi
  sleep 1
done

if ! curl -sf "$APP_URL/api/events/1/availability" > /dev/null; then
  echo "Application did not become available in time. Log follows:" >&2
  cat "$LOG_FILE" >&2
  exit 1
fi

echo "==> Running k6 spike test"
k6 run load-tests/booking.js

echo "==> Audit result"
curl -s "$APP_URL/api/events/1/audit"
echo

echo "==> Stopping the application"
kill "$APP_PID"
wait "$APP_PID" 2>/dev/null || true
