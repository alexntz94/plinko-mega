#!/bin/sh
# Load test (review 5.6): k6 in Docker against a running test stack (scripts/test-env.sh, docker compose -p plinkotest).
#   scripts/loadtest.sh <test.env> [players] [duration]          e.g. scripts/loadtest.sh /tmp/plinko-test/test.env 200 3m
# Settings (environment): PROJECT (compose project, default plinkotest), SLOW_PLAYERS (default 5), ADMINS (default 2),
# RAMP (default 30s), PLAY_P99_MS (default 1500; the real target is decision D7), ADMIN_P95_MS, THINK_MIN/THINK_MAX,
# SLOW_FAULTS, GAME_URL (default the backend directly, see README "Load test"), OUT (default ./loadtest-out).
# Writes $OUT/summary.json and exits non-zero when a target is missed. Only for a test stack with the mock wallet:
# the real capacity test runs on a staging copy of production (README).
# This is the fixed load; the ramp to the breaking point, the wallet outage, the database restart and the large seeded
# history are tests/load/scenarios.py, and nginx's limits tests/load/nginx_limits.py (README "Load test").
set -eu
env_file=${1:?usage: scripts/loadtest.sh <test.env> [players] [duration]}
players=${2:-50}
duration=${3:-3m}
project=${PROJECT:-plinkotest}
slow=${SLOW_PLAYERS:-5}
admins=${ADMINS:-2}
out=${OUT:-$PWD/loadtest-out}
# grafana/k6 v1.3.0, pinned by digest (review 6)
k6_image=${K6_IMAGE:-grafana/k6:1.3.0@sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d}
here=$(cd "$(dirname "$0")/.." && pwd)

mkdir -p "$out"
out=$(cd "$out" && pwd)
python3 "$here/tests/load/prepare.py" "$env_file" $((players + slow + admins)) "$out" "$project"

# the mock wallet's key and secret, for the slow-wallet players' faults; passed by name, never on the command line
PAM_API_KEY=$(sed -n 's/^PAM_API_KEY=//p' "$env_file")
PAM_SECRET=$(sed -n 's/^PAM_SECRET=//p' "$env_file")
export PAM_API_KEY PAM_SECRET

status=0
docker run --rm --network "${project}_edge" --user "$(id -u):$(id -g)" \
    -v "$here/tests/load:/scripts:ro" -v "$out:/out" \
    -e PAM_API_KEY -e PAM_SECRET -e WALLETS=/out/wallets.json -e OUT=/out \
    -e PLAYERS="$players" -e DURATION="$duration" -e SLOW_PLAYERS="$slow" -e ADMINS="$admins" \
    -e RAMP="${RAMP:-30s}" -e PLAY_P99_MS="${PLAY_P99_MS:-1500}" -e ADMIN_P95_MS="${ADMIN_P95_MS:-5000}" \
    -e THINK_MIN="${THINK_MIN:-1}" -e THINK_MAX="${THINK_MAX:-4}" -e SLOW_FAULTS="${SLOW_FAULTS:-}" \
    -e GAME_URL="${GAME_URL:-http://backend:8080}" -e ADMIN_URL="${ADMIN_URL:-http://backend:8081}" \
    "$k6_image" run --quiet --no-color /scripts/play.js || status=$?

# what the run left in the game: every round must end COMPLETED or CANCELLED once the background retry has paid the
# credits the slow wallet held back (they show as CREDIT_PENDING for a minute or two; SETTLE_MINUTES, default 10),
# nothing paid twice, and the daily totals equal to the rounds (review R2): $out/after-run.json
OUT="$out" PROJECT="$project" python3 "$here/tests/load/scenarios.py" "$env_file" after-run || status=1
echo "summary: $out/summary.json, $out/after-run.json"
exit "$status"
