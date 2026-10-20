#!/bin/bash
# Review N6: the game on Mobile Safari (iOS Simulator, Xcode) asks for the WebP or AVIF pictures, not the PNGs (WebP
# and AVIF are found by decoding a tiny image; Safari can show both but can't make them, so a canvas test said no).
# Starts the test stack as its own Compose project on its own ports, makes a mock-wallet launch token, opens the game
# in Safari on a booted iPhone simulator, takes a screenshot and reads nginx's access log for the iPhone's requests:
#   - fails if the iPhone asked for a PNG of a picture the build made a WebP or AVIF of (background, logo, item_plinko,
#     item_exit, item_result), or for no WebP / AVIF at all;
#   - also reports whether a background video was asked for before any round was played (it must not be: review 4.x).
# The stack is taken down (down -v) and the simulator made for the run deleted at the end.
#   tests/ios_safari_check.sh [theme] [output folder]      (default: rat-race, a new temporary folder)
# Environment: DEVICE (simulator device type, e.g. "iPhone 17 Pro"; default the first available iPhone's), PROJECT (plinkoios), WEB_PORT (18080),
# ADMIN_PORT (18081), WAIT (seconds the page gets to load, 40), KEEP=1 (leave the stack running), BACKEND_IMAGE (an
# already built backend image to use instead of building one, e.g. plinko-backend:local: only the pictures are checked).
# Needs macOS with Xcode's iOS Simulator, Docker and python3. If an image pull hangs: DOCKER_CONFIG=$(mktemp -d).
set -euo pipefail
cd "$(dirname "$0")/.."
theme=${1:-rat-race}
out=${2:-$(mktemp -d)}
project=${PROJECT:-plinkoios}
web_port=${WEB_PORT:-18080}
admin_port=${ADMIN_PORT:-18081}
backend_image=${BACKEND_IMAGE:-plinko-backend:$project}
mkdir -p "$out"
command -v xcrun >/dev/null && xcrun simctl help >/dev/null 2>&1 || { echo "No iOS Simulator (install Xcode)." >&2; exit 2; }
[ -d "game/public/themes/$theme" ] || { echo "No theme $theme" >&2; exit 2; }

# the simulator: a new device of the named type (else the first available iPhone's), made for this run and deleted
# after it, so Safari's cache is empty (a device used before answers the pictures from its cache, unseen by nginx)
if [ -n "${DEVICE:-}" ]; then
    device=$DEVICE
else
    device=$(xcrun simctl list devices available | grep -E '^ +iPhone' | head -1 | sed -E 's/^ +//; s/ \(.*//')
fi
[ -n "$device" ] || { echo "No iPhone simulator available." >&2; exit 2; }
udid=$(xcrun simctl create "plinko-ios-check" "$device") || { echo "Can't make a simulator of type $device." >&2; exit 2; }

work=$(mktemp -d)
compose() { docker compose -p "$project" --env-file "$work/test.env" -f docker-compose.yml -f "$work/ports.yml" "$@"; }
cleanup() {
    if [ "${KEEP:-0}" != 1 ]; then compose down -v >/dev/null 2>&1 || true; fi
    xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
    xcrun simctl delete "$udid" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

echo "== stack $project on http://localhost:$web_port (admin 127.0.0.1:$admin_port)"
scripts/test-env.sh "$work" >/dev/null
# its own ports and image names, so it runs next to the usual test stack (plinkotest, 8080 / 8081)
cat > "$work/ports.yml" <<YML
services:
  db: {image: "plinko-db:$project"}
  migrate: {image: "$backend_image"}
  backend:
    image: "$backend_image"
    ports: !override
      - "127.0.0.1:$admin_port:8081"
  web:
    image: "plinko-web:$project"
    ports: !override
      - "127.0.0.1:$web_port:8080"
YML
# built first: migrate runs the backend's image, which up would otherwise try to pull before building it
if [ -n "${BACKEND_IMAGE:-}" ]; then compose build --quiet db web; else compose build --quiet; fi
compose up -d --no-build --quiet-pull >/dev/null
for _ in $(seq 1 90); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$project-web-1" 2>/dev/null)" = healthy ] && break; sleep 5
done
[ "$(docker inspect -f '{{.State.Health.Status}}' "$project-web-1")" = healthy ] || { compose ps; exit 1; }

token=$(PROJECT=$project python3 tests/launch_tokens.py "$work/test.env" | sed -n 's/^TOKENS=//p' | tr ',' '\n' | sed -n "s/^$theme=//p")
[ -n "$token" ] || { echo "No launch token for $theme" >&2; exit 1; }

echo "== $device, a new simulator ($udid)"
xcrun simctl boot "$udid"
xcrun simctl bootstatus "$udid" -b >/dev/null
# the simulator shares the Mac's network: localhost is the Mac (and a secure context, like https)
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
# a freshly booted device can refuse to open a link for a while (its first boot sets itself up): Safari is woken
# with a harmless page first, retried, so the single-use launch link is opened only once
opened=0
for _ in 1 2 3 4 5 6 7 8 9 10; do
    if xcrun simctl openurl "$udid" "http://localhost:$web_port/healthz" 2>/dev/null; then opened=1; break; fi
    sleep 15
done
[ "$opened" = 1 ] || { echo "Safari on $device does not open links" >&2; exit 1; }
sleep 3
xcrun simctl openurl "$udid" "http://localhost:$web_port/?token=$token&theme=$theme"
sleep "${WAIT:-40}"
shot="$out/ios-safari-$theme.png"
xcrun simctl io "$udid" screenshot "$shot" >/dev/null
docker logs --since "$since" "$project-web-1" 2>/dev/null | grep -E 'iPhone|iPad' > "$out/ios-safari-access.log" || true

log="$out/ios-safari-access.log"
pictures='/themes/[^ ]*/(background|logo|item_plinko|item_exit|item_result)[^ /]*'
png=$(grep -cE "GET $pictures\.png\"" "$log" || true)
modern=$(grep -cE "GET /themes/[^ ]*\.(webp|avif)\"" "$log" || true)
video=$(grep -cE "GET /themes/[^ ]*\.(mp4|webm)\"" "$log" || true)
echo "== requests from $device's Safari: $(wc -l < "$log" | tr -d ' ') (log: $log)"
echo "   WebP / AVIF pictures: $modern"
grep -oE "GET /themes/[^ ]*\.(webp|avif)\"" "$log" | sed 's/^GET /     /; s/"$//' | sort -u || true
echo "   PNGs of pictures that have a WebP / AVIF copy: $png"
grep -oE "GET $pictures\.png\"" "$log" | sed 's/^GET /     /; s/"$//' | sort -u || true
echo "   background videos before the first round: $video"
echo "   screenshot: $shot"
if [ "$modern" -gt 0 ] && [ "$png" -eq 0 ] && [ "$video" -eq 0 ]; then
    echo "PASS: Mobile Safari got WebP / AVIF pictures, no PNG copies and no video before the first round"
else
    echo "FAIL: see the requests above" >&2
    exit 1
fi
