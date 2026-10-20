#!/bin/sh
# Moves the secret values of an existing env file (older layout) into one file each under ./secrets
# (or $SECRETS_DIR). The env file is left unchanged; remove the secret lines from it afterwards.
# Usage: scripts/secrets-from-env.sh .env
set -eu
env_file=${1:?usage: scripts/secrets-from-env.sh ENV_FILE}
dir=${SECRETS_DIR:-$(dirname "$0")/../secrets}
umask 077
mkdir -p "$dir"
chmod 700 "$dir"
for name in MYSQL_ROOT_PASSWORD DB_OWNER_PASSWORD DB_PASSWORD ADMIN_PASSWORD ROUND_SIGNING_KEY PAM_API_KEY PAM_SECRET; do
    value=$(grep "^${name}=" "$env_file" | head -n 1 | cut -d= -f2-)
    if [ -z "$value" ]; then
        echo "missing $name in $env_file" >&2
        exit 1
    fi
    printf '%s' "$value" > "$dir/$name"
    chmod 644 "$dir/$name"
done
# The keys per use (README, Keys) are optional: from the env file if it has them; otherwise an existing file is kept,
# or an empty one made (= the original ROUND_SIGNING_KEY for that use, exactly as before the lists existed).
for name in ROUND_KEYS RECORD_KEYS ENCRYPTION_KEYS; do
    value=$(grep "^${name}=" "$env_file" | head -n 1 | cut -d= -f2- || true)
    if [ -n "$value" ] || [ ! -e "$dir/$name" ]; then
        printf '%s' "$value" > "$dir/$name"
        chmod 644 "$dir/$name"
    fi
done
echo "Wrote $(ls "$dir" | wc -l | tr -d ' ') secret files to $dir"
