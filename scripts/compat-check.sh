#!/bin/bash
# Review 7.6: a release must work side by side with the one before it (a rolling release runs both for a while, and a
# rollback runs the old one on the new database). This starts the stack with the NEW migrations applied, then swaps
# in the PREVIOUS release's backend and checks that it still plays and pays rounds there, and that the new backend
# accepts the rounds it wrote. Before that, the previous release's own migration step runs on the new database and
# must succeed (review R3): on Compose a rollback starts the backend only after it, so if it refuses the database the
# rollback can't start. The new backend must also accept every request the previous release's game client
# sends (its tests/client_requests.json, or for a release from before that file tests/compat/<tag>-client_requests.json,
# replayed by tests/compat_smoke.py; review N9): players keep that client in
# their browsers during and after the release.
# The previous release is the newest tag v* before HEAD. Without one the check FAILS (review N9: a check that passes
# with nothing to compare checks nothing); the only exception is the first release itself, a v* tag on HEAD with none
# before it. Tag every release: README, Releases.
#   scripts/compat-check.sh <test.env>
set -euo pipefail
env_file=${1:?usage: scripts/compat-check.sh <test.env>}
prev=$(git describe --tags --abbrev=0 --match 'v*' HEAD^ 2>/dev/null || true)
if [ -z "$prev" ]; then
    if git describe --tags --exact-match --match 'v*' HEAD >/dev/null 2>&1; then
        echo "$(git describe --tags --exact-match --match 'v*' HEAD) is the first release: nothing earlier to check against."
        exit 0
    fi
    echo "No release tag (v*) before this commit, so there is no previous release to check against." >&2
    echo "Tag the release that is live now (git tag v1.0.0 <its commit> && git push origin v1.0.0; README, Releases)." >&2
    exit 1
fi
echo "== previous release: $prev"
work=$(mktemp -d)
trap 'git worktree remove --force "$work/prev" >/dev/null 2>&1 || true; rm -rf "$work"' EXIT
git worktree add --detach "$work/prev" "$prev" >/dev/null
docker build -q -t plinko-backend:previous "$work/prev/backend" >/dev/null
# the previous release's game client requests: its own tests/client_requests.json (recorded since review N9), else a
# copy recorded afterwards from that release's game/src/api.ts, tests/compat/<tag>-client_requests.json (v1.0.0)
requests=()
if [ -r "$work/prev/tests/client_requests.json" ]; then
    requests=("$work/prev/tests/client_requests.json")
elif [ -r "tests/compat/$prev-client_requests.json" ]; then
    echo "== $prev has no tests/client_requests.json: replaying tests/compat/$prev-client_requests.json"
    requests=("tests/compat/$prev-client_requests.json")
else
    echo "No recorded requests for $prev's game client: neither its tests/client_requests.json nor" >&2
    echo "tests/compat/$prev-client_requests.json. Record them from its game/src/api.ts (README, Releases)." >&2
    exit 1
fi
compose() { docker compose -p plinkotest --env-file "$env_file" "$@"; }
wait_healthy() {
    for _ in $(seq 1 60); do
        [ "$(docker inspect -f '{{.State.Health.Status}}' "plinkotest-$1-1" 2>/dev/null)" = healthy ] && return 0; sleep 5
    done
    compose ps; return 1
}
echo "== the new release, migrations applied"
compose up -d --build
wait_healthy web
python3 tests/compat_smoke.py "$env_file" new-before ${requests[@]+"${requests[@]}"}
# a Compose rollback runs the previous release's whole stack: its migration step first, then its backend
printf 'services:\n  migrate:\n    image: plinko-backend:previous\n  backend:\n    image: plinko-backend:previous\n' > "$work/previous.yml"
echo "== the previous release's migration step on the new database"
if ! compose -f docker-compose.yml -f "$work/previous.yml" run --rm --no-deps migrate; then
    echo "The previous release's ($prev) migration step refuses the database this release migrated, so a rollback can't start" >&2
    echo "(Compose starts the backend only after it). Keep every version the previous release knows recorded as it expects" >&2
    echo "(README, Releases: rolling back)." >&2
    exit 1
fi
echo "== the previous backend on the new database"
compose -f docker-compose.yml -f "$work/previous.yml" up -d --no-build --force-recreate backend
wait_healthy backend
docker restart plinkotest-web-1 >/dev/null; wait_healthy web
python3 tests/compat_smoke.py "$env_file" previous
# N9: a rollback to the previous release while players still have THIS release's client open (and, during a Cloud Run
# traffic split, a new page reaching an old instance). It fails, unless the release declares that its game needs this
# backend: tests/client-needs-new-backend names the previous release and says why (README, Releases).
if ! python3 tests/compat_smoke.py "$env_file" previous-backend-new-client tests/client_requests.json; then
    if [ -r tests/client-needs-new-backend ] && grep -q "^$prev:" tests/client-needs-new-backend; then
        echo "::warning::This release's game needs this backend ($(grep "^$prev:" tests/client-needs-new-backend)): deploy with traffic moved at once, and a rollback needs players to reload."
    else
        echo "This release's game client sends requests the previous backend ($prev) refuses: a rollback, or a traffic split during the" >&2
        echo "release, would break open game tabs. Make the new field optional, or declare it in tests/client-needs-new-backend as" >&2
        echo "'$prev: <why>' (README, Releases)." >&2
        exit 1
    fi
fi
echo "== back to the new backend: it accepts what the previous one wrote"
compose up -d --no-build --force-recreate backend
wait_healthy backend
docker restart plinkotest-web-1 >/dev/null; wait_healthy web
python3 tests/compat_smoke.py "$env_file" new-after ${requests[@]+"${requests[@]}"}
echo "== compatible with $prev"
