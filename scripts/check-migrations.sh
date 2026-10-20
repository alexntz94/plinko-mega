#!/bin/sh
# Rolling releases (review 7.6): during a deploy the old and the new backend run side by side on the same database,
# so every new migration must work with both ("expand, then contract"). This refuses, in migrations after V15:
#   DROP COLUMN / DROP TABLE / RENAME / CHANGE COLUMN / MODIFY  (the old version still uses what changes)
#   ADD COLUMN ... NOT NULL without a DEFAULT                  (the old version inserts rows without it)
# A step that really contracts (removes what no running version uses any more, at least one release after the code
# stopped using it) says so on the same line:  ... -- contract: <why it is safe now>
#
# Review N11: data backfills run as a separate, batched job, not inside a migration (README, Releases). A migration
# runs while players are playing; one statement over a whole large table locks its rows for minutes. This refuses, in
# migrations after V14 except the two that predate the rule (ALLOWED_BACKFILLS):
#   INSERT ... SELECT that reads or writes a large table
#   UPDATE / DELETE of a large table without a LIMIT
# A statement that is safe anyway (e.g. it touches a handful of rows) says so inside it:  -- small: <why>
#
# Review R3: Java migrations too (backend/src/main/java/db/migration/V*.java, and the batched backfills that run as
# V15/V16, backend/src/main/java/com/plinko/migration/*Backfill.java): the same contract rule on their SQL (a line that
# drops, renames or modifies says  // contract: <why>), and one that writes a large table must say how it is batched
# (// batched: <how>) and must not run in one transaction (canExecuteInTransaction() returns false).
set -eu
cd "$(dirname "$0")/../backend/src/main/resources/db/migration"
# tables that grow with play: one row per round, transaction, player, session, launch or audit entry
LARGE_TABLES="game_round|wallet_transaction|player|game_session|used_launch_token|audit_log|round_daily_totals|round_daily_players"
# no SQL migration is allowed a whole-table backfill (V15 and V16 are batched Java migrations now, review N11)
ALLOWED_BACKFILLS=" "
bad=0
for f in V*__*.sql; do
    version=${f#V}; version=${version%%__*}
    if [ "$version" -gt 14 ] && [ "${ALLOWED_BACKFILLS#* $version }" = "$ALLOWED_BACKFILLS" ]; then
        # one statement at a time (split at ";"), comments and line breaks removed, lowercase
        if awk -v RS=';' -v large="$LARGE_TABLES" -v file="$f" '
            BEGIN { word = "(^|[^a-z0-9_])(" large ")([^a-z0-9_]|$)"; found = 0 }
            {
                if (tolower($0) ~ /--[[:space:]]*small:/) next
                s = tolower($0)
                gsub(/--[^\n]*/, "", s); gsub(/[[:space:]]+/, " ", s); sub(/^ /, "", s)
                why = ""
                if (s ~ /^insert / && s ~ / select / && s ~ word) why = "INSERT ... SELECT over a large table"
                else if (s ~ /^(update|delete) / && s ~ word && s !~ / limit [0-9]+ ?$/) why = "UPDATE / DELETE of a large table without LIMIT"
                if (why != "") { printf "%s: %s: %.100s\n", file, why, s; found = 1 }
            }
            END { exit found ? 0 : 1 }' "$f" >&2; then
            echo "$f: a data backfill in a migration; run it as a separate, batched job (see scripts/check-migrations.sh)" >&2; bad=1
        fi
    fi
    [ "$version" -gt 15 ] || continue
    if grep -n -i -E 'drop[[:space:]]+column|drop[[:space:]]+table|[[:space:]]rename[[:space:]]|change[[:space:]]+column|[[:space:]]modify[[:space:]]' "$f" \
            | grep -v -i -e '-- contract:'; then
        echo "$f: a change the running version may still depend on (see scripts/check-migrations.sh)" >&2; bad=1
    fi
    if grep -n -i -E 'add[[:space:]]+(column[[:space:]]+)?[a-z_]+[[:space:]].*not[[:space:]]+null' "$f" | grep -v -i -e 'default' -e '-- contract:'; then
        echo "$f: a NOT NULL column without a DEFAULT breaks inserts from the running version" >&2; bad=1
    fi
done
cd ../../../java
for f in db/migration/V*__*.java com/plinko/migration/*Backfill.java; do
    [ -e "$f" ] || continue
    if grep -n -i -E 'drop[[:space:]]+column|drop[[:space:]]+table|[[:space:]]rename[[:space:]]|change[[:space:]]+column|[[:space:]]modify[[:space:]]' "$f" \
            | grep -v -i -e '// contract:' -e '^[0-9]*:[[:space:]]*\*' -e '^[0-9]*:[[:space:]]*//'; then
        echo "$f: a change the running version may still depend on (see scripts/check-migrations.sh)" >&2; bad=1
    fi
    if grep -i -q -E "(update|delete[[:space:]]+from|insert[[:space:]]+(ignore[[:space:]]+)?into)[[:space:]]+($LARGE_TABLES)([^a-z0-9_]|$)" "$f"; then
        if ! grep -q '// batched:' "$f"; then
            echo "$f: writes a large table without saying how it is batched (// batched: <how>; see scripts/check-migrations.sh)" >&2; bad=1
        fi
        if grep -q 'BaseJavaMigration' "$f" && ! grep -A2 'canExecuteInTransaction' "$f" | grep -q 'return false'; then
            echo "$f: a backfill in one transaction; canExecuteInTransaction() must return false (commit per batch)" >&2; bad=1
        fi
    fi
done
[ $bad -eq 0 ] && echo "migrations: safe for a rolling release"
exit $bad
