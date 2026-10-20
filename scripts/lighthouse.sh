#!/bin/sh
# Page weight and load time on a phone (review 5.6, 4.x): Lighthouse CI (mobile profile) on the game as players get it,
# through nginx, for a few themes, against the budget in tests/lighthouse/lighthouserc.json.
#   scripts/lighthouse.sh <test.env> [theme ...]         default: drop-riot rat-race fatal-attraction
# Needs the test stack running as project plinkotest (tests/launch_tokens.py makes the launch tokens), Node and Chrome.
# Reports go to $OUT (default ./lighthouse-out); exits non-zero when an "error" line of the budget is missed (the
# timings are warnings until the targets are agreed, decision D7).
set -eu
env_file=${1:?usage: scripts/lighthouse.sh <test.env> [theme ...]}
shift
[ $# -gt 0 ] || set -- drop-riot rat-race fatal-attraction
here=$(cd "$(dirname "$0")/.." && pwd)
game=${GAME:-http://localhost:8080}
out=${OUT:-lighthouse-out}
mkdir -p "$out"
out=$(cd "$out" && pwd)
lhci="npx --yes @lhci/cli@0.15.1"

# one fresh launch token per theme (a token opens one session, so each Lighthouse run needs its own)
tokens=$(python3 "$here/tests/launch_tokens.py" "$env_file" | sed -n 's/^TOKENS=//p')
urls=""
for theme in "$@"; do
    token=$(printf '%s\n' "$tokens" | tr ',' '\n' | sed -n "s/^$theme=//p")
    [ -n "$token" ] || { echo "no theme $theme" >&2; exit 1; }
    urls="$urls --url=$game/?token=$token&theme=$theme"
done

work=$(mktemp -d)
cd "$work"
# shellcheck disable=SC2086 # one --url argument per theme
$lhci collect --config="$here/tests/lighthouse/lighthouserc.json" $urls
status=0
$lhci assert --config="$here/tests/lighthouse/lighthouserc.json" || status=$?
$lhci upload --target=filesystem --outputDir="$out"
echo "reports: $out"
exit "$status"
