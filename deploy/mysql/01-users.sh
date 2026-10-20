#!/bin/sh
# Creates the schema owner (used only by the one-off migration container) and the restricted application user;
# table grants for the latter come from Flyway V2.
#  - Docker: runs once when the database volume is created, on the local socket.
#  - Google Cloud: runs as the plinko-db-setup job against Cloud SQL (DB_HOST set), over an encrypted connection.
#    Users made with gcloud or the console get near-admin rights there, so they are made here, with SQL.
# Passwords are read from secret files: /run/secrets/NAME (Docker) or /secrets/NAME/NAME (Cloud Run).
set -eu
secret() {
    file=/run/secrets/$1
    [ -r "$file" ] || file=/secrets/$1/$1
    cat "$file"
}
owner_password=$(secret DB_OWNER_PASSWORD)
app_password=$(secret DB_PASSWORD)
if [ ${#owner_password} -lt 16 ] || [ ${#app_password} -lt 16 ]; then
    echo "DB_OWNER_PASSWORD and DB_PASSWORD must be set (16+ characters); run scripts/generate-secrets.sh" >&2
    exit 1
fi
# the root password goes in the environment of the client only, not on its command line
MYSQL_PWD=$(secret MYSQL_ROOT_PASSWORD)
export MYSQL_PWD
if [ -n "${DB_HOST:-}" ]; then
    connect="--protocol=tcp -h $DB_HOST --ssl-mode=REQUIRED"
else
    connect="--protocol=socket"
fi
# shellcheck disable=SC2086
mysql $connect -uroot <<SQL
CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${DB_OWNER_USERNAME}'@'%' IDENTIFIED BY '${owner_password}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${DB_OWNER_USERNAME}'@'%' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS '${DB_USERNAME}'@'%' IDENTIFIED BY '${app_password}';
FLUSH PRIVILEGES;
SQL
if [ -n "${DB_HOST:-}" ]; then
    # Cloud SQL: show what each user may do, to check against the README (run again after the migrations)
    # shellcheck disable=SC2086
    mysql $connect -uroot -e "SHOW GRANTS FOR '${DB_OWNER_USERNAME}'@'%'; SHOW GRANTS FOR '${DB_USERNAME}'@'%';"
fi
