#!/bin/sh
# Creates .env (settings) and ./secrets/ (one file per secret, strong random values). Never commit either.
# On an existing install (.env or secrets/ already there) it never overwrites anything: it only adds the secret files a
# newer release needs and that can be made safely (review R17.7): the separate key lists ROUND_KEYS, RECORD_KEYS and
# ENCRYPTION_KEYS (one fresh key each; data signed before keeps verifying with ROUND_SIGNING_KEY) and an empty
# ADMIN_PASSWORD. Any other missing secret (a database password, the PAM's key) is named, never invented. Run it before
# every `docker compose up` after an update (README, Run it).
set -eu
cd "$(dirname "$0")/.."
rand() { openssl rand -base64 48 | tr -d '\n/+=' | cut -c1-"$1"; }
umask 077
write() { printf '%s' "$2" > "secrets/$1"; chmod 644 "secrets/$1"; }   # folder is 700: only you can reach the files
if [ -e .env ] || [ -e secrets ]; then
    if [ ! -d secrets ]; then
        echo ".env exists but secrets/ doesn't: move the secrets out of .env first (scripts/secrets-from-env.sh .env)" >&2
        exit 1
    fi
    added=""
    for list in ROUND_KEYS:r1 RECORD_KEYS:d1 ENCRYPTION_KEYS:e1; do
        name=${list%%:*}
        [ -e "secrets/$name" ] || { write "$name" "${list#*:}=$(rand 64)"; added="$added $name"; }
    done
    [ -e secrets/ADMIN_PASSWORD ] || { write ADMIN_PASSWORD ""; added="$added ADMIN_PASSWORD"; }
    missing=""
    for name in MYSQL_ROOT_PASSWORD DB_OWNER_PASSWORD DB_PASSWORD ROUND_SIGNING_KEY PAM_API_KEY PAM_SECRET; do
        [ -e "secrets/$name" ] || missing="$missing $name"
    done
    if [ -n "$added" ]; then
        echo "Existing install: nothing overwritten. Added:$added."
    else
        echo "Existing install: nothing overwritten, nothing to add."
    fi
    if [ -n "$missing" ]; then
        echo "Missing, and not made up (they must be the values this install already uses):$missing." >&2
        echo "If they are still in .env: scripts/secrets-from-env.sh .env" >&2
        exit 1
    fi
    exit 0
fi
mkdir secrets
write MYSQL_ROOT_PASSWORD "$(rand 40)"
write DB_OWNER_PASSWORD "$(rand 40)"
write DB_PASSWORD "$(rand 40)"
# empty: the first admin signs in with admin / admin, then must set up 2FA and choose a new password (24 hours)
write ADMIN_PASSWORD ""
write ROUND_SIGNING_KEY "$(rand 64)"
# separate keys per use (README, Keys): the first of each list signs or encrypts new data. A new install has nothing
# signed with ROUND_SIGNING_KEY alone, so it starts with all three.
write ROUND_KEYS "r1=$(rand 64)"
write RECORD_KEYS "d1=$(rand 64)"
write ENCRYPTION_KEYS "e1=$(rand 64)"
write PAM_API_KEY "$(rand 32)"
write PAM_SECRET "$(rand 64)"
cat > .env <<ENV
ADMIN_USERNAME=admin

# Wallet connection: replace with the real PAM address, and put its key and secret in secrets/PAM_API_KEY and secrets/PAM_SECRET.
PAM_BASE_URL=https://pam.example.com/wallet
# Only for a PAM on the same private network; otherwise PAM_BASE_URL must be https://
PAM_ALLOW_HTTP=false

# Local testing only: set MOCK_PAM_ENABLED=true and PAM_BASE_URL=http://localhost:8080/mock-pam
MOCK_PAM_ENABLED=false
REQUIRE_SECOND_APPROVER=true
# Keep true behind HTTPS. Set false only to use the admin panel over plain http://127.0.0.1.
COOKIE_SECURE=true
GAME_URL=http://localhost:8080/
ENV
chmod 600 .env
echo "Created .env and secrets/. First admin sign-in: admin / admin (within 24 hours; then 2FA and a new password)."
