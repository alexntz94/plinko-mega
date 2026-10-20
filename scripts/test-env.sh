#!/bin/sh
# Makes a throwaway environment for the end-to-end tests (CI, or a local run): <dir>/secrets/ with fresh random
# secrets and <dir>/test.env with the same values plus the test settings (mock wallet on, plain http).
#   scripts/test-env.sh /tmp/plinko-test
#   docker compose -p plinkotest --env-file /tmp/plinko-test/test.env up -d --build
#   python3 -m pytest tests/e2e --env-file /tmp/plinko-test/test.env     (pytest from tests/requirements.txt)
# Never use these settings for a real installation.
set -eu
dir=${1:?usage: scripts/test-env.sh <dir>}
rand() { openssl rand -base64 48 | tr -d '\n/+=' | cut -c1-"$1"; }
umask 077
mkdir -p "$dir/secrets"
dir=$(cd "$dir" && pwd)
env="$dir/test.env"
: > "$env"
for name in MYSQL_ROOT_PASSWORD DB_OWNER_PASSWORD DB_PASSWORD ADMIN_PASSWORD ROUND_SIGNING_KEY PAM_API_KEY PAM_SECRET; do
    case $name in ROUND_SIGNING_KEY|PAM_SECRET) value="$(rand 64)";; ADMIN_PASSWORD) value="Test-$(rand 24)-9a";; *) value="$(rand 40)";; esac
    printf '%s' "$value" > "$dir/secrets/$name"
    chmod 644 "$dir/secrets/$name"
    echo "$name=$value" >> "$env"
done
# the keys per use start empty (only the original ROUND_SIGNING_KEY, like an install from before them); the e2e
# suite rotates to new keys by writing these files (7.2)
for name in ROUND_KEYS RECORD_KEYS ENCRYPTION_KEYS; do
    : > "$dir/secrets/$name"
    chmod 644 "$dir/secrets/$name"
done
cat >> "$env" <<ENV
ADMIN_USERNAME=admin
PAM_BASE_URL=http://localhost:8080/mock-pam
MOCK_PAM_ENABLED=true
REQUIRE_SECOND_APPROVER=true
COOKIE_SECURE=false
GAME_URL=http://localhost:8080/
SECRETS_DIR=$dir/secrets
ENV
echo "$env"
