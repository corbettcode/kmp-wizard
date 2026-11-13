#!/usr/bin/env bash
# Builds the Docker image, runs it, and checks the service actually works before you push
# (master deploys straight to App Platform).
#
#   scripts/smoke-test.sh              # build + test
#   SKIP_BUILD=1 scripts/smoke-test.sh # test an already-built kmp-wizard image
#
# Env: IMAGE (default kmp-wizard), PORT (host port, default 18080), TIMEOUT (seconds to wait for startup, default 90).
set -euo pipefail

IMAGE=${IMAGE:-kmp-wizard}
PORT=${PORT:-18080}
TIMEOUT=${TIMEOUT:-90}
BASE="http://localhost:$PORT"
NAME="kmp-wizard-smoke-$$"
TMP=$(mktemp -d)

cd "$(dirname "$0")/.."

cleanup() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    rm -rf "$TMP"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $*" >&2
    echo "--- container logs ---" >&2
    docker logs "$NAME" 2>&1 | tail -50 >&2 || true
    exit 1
}

if [[ -z "${SKIP_BUILD:-}" ]]; then
    echo "==> Building $IMAGE"
    docker build -t "$IMAGE" .
fi

echo "==> Starting $IMAGE on port $PORT"
docker run -d --name "$NAME" -p "$PORT:8080" "$IMAGE" >/dev/null

echo "==> Waiting up to ${TIMEOUT}s for /api/health"
deadline=$((SECONDS + TIMEOUT))
until [[ "$(curl -fsS "$BASE/api/health" 2>/dev/null)" == "ok" ]]; do
    [[ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" == "true" ]] || fail "container exited"
    (( SECONDS < deadline )) || fail "/api/health did not answer within ${TIMEOUT}s"
    sleep 1
done
echo "    up after ~$((SECONDS - (deadline - TIMEOUT)))s"

echo "==> GET / serves the site"
curl -fsS "$BASE/" -o "$TMP/index.html" || fail "GET / failed"
grep -q "KMP Wizard" "$TMP/index.html" || fail "GET / did not return the wizard page"

echo "==> POST /api/generate returns a project ZIP"
status=$(curl -sS -o "$TMP/project.zip" -w '%{http_code}' -X POST "$BASE/api/generate" \
    -H 'Content-Type: application/json' \
    --data '{"name":"SmokeTest","packageName":"com.example.smoketest","targets":["DESKTOP","SERVER"],"slices":[],"libraries":[],"sampleTodo":true}')
[[ "$status" == "200" ]] || fail "/api/generate returned HTTP $status: $(head -c 500 "$TMP/project.zip")"
unzip -l "$TMP/project.zip" > "$TMP/listing.txt" 2>&1 || fail "/api/generate did not return a valid ZIP"
grep -q "settings.gradle.kts" "$TMP/listing.txt" || fail "generated ZIP has no settings.gradle.kts"

echo "==> POST /api/generate rejects a malformed spec"
status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/api/generate" --data 'not json')
[[ "$status" == "400" ]] || fail "malformed spec returned HTTP $status, expected 400"

echo "PASS"
