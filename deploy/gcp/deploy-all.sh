#!/bin/bash
# Updates every casino's deployment, one after the other: for each deploy/gcp/casinos/<casino>.env it runs
# build, migrate, deploy and check in that casino's own Google Cloud project. Stops at the first failure, so a
# broken update never reaches the next casino.
#
#   deploy/gcp/deploy-all.sh                 all casinos
#   deploy/gcp/deploy-all.sh casino-a ...    only these
#   --skip-ci-check (anywhere)               passed on to deploy.sh: deploy without a successful CI run (review R1.2)
set -euo pipefail
cd "$(dirname "$0")/../.."

skip=()
names=()
for arg in "$@"; do
    case "$arg" in --skip-ci-check) skip=(--skip-ci-check);; *) names+=("$arg");; esac
done
set -- "${names[@]+"${names[@]}"}"

if [ $# -gt 0 ]; then
    casinos=("$@")
else
    casinos=()
    for file in deploy/gcp/casinos/*.env; do
        case "$file" in *.images.env) continue;; esac
        [ -e "$file" ] && casinos+=("$(basename "$file" .env)")
    done
fi
[ ${#casinos[@]} -gt 0 ] || { echo "No casinos yet: add deploy/gcp/casinos/<casino>.env (see the README)." >&2; exit 1; }

for casino in "${casinos[@]}"; do
    printf '\n######## %s\n' "$casino"
    for step in build migrate deploy check; do
        deploy/gcp/deploy.sh "$step" "$casino" "${skip[@]+"${skip[@]}"}"
    done
done
printf '\nUpdated: %s\n' "${casinos[*]}"
