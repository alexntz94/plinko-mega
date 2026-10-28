# Official MySQL 8.4 LTS, hardened:
#  - runs as the mysql user from the start, so gosu (only used to drop root) is removed
#  - MySQL Shell removed (unused; it bundles its own Python runtime)
#  - OS security updates applied
FROM mysql:26.7.0@sha256:625f74ddf93652d9d0f8d4ea7d741b9189554a5e96d27bb770abd0902d89c6e3
RUN microdnf remove -y mysql-shell \
 && microdnf upgrade -y --refresh \
 && microdnf clean all \
 && rm -rf /var/cache/yum /var/cache/dnf /usr/local/bin/gosu
COPY --chmod=755 deploy/mysql/01-users.sh /docker-entrypoint-initdb.d/01-users.sh
USER 999:999
HEALTHCHECK --interval=5s --timeout=5s --retries=30 CMD mysqladmin ping --protocol=tcp -h 127.0.0.1 --silent
