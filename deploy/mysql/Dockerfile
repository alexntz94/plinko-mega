# Official MySQL 8.4 LTS, hardened:
#  - runs as the mysql user from the start, so gosu (only used to drop root) is removed
#  - MySQL Shell removed (unused; it bundles its own Python runtime)
#  - OS security updates applied
FROM mysql:8.4.11@sha256:24989179f58856db763f418010c1169fecc944e3ec1ea7e921572d83eb070f70
RUN microdnf remove -y mysql-shell \
 && microdnf upgrade -y --refresh \
 && microdnf clean all \
 && rm -rf /var/cache/yum /var/cache/dnf /usr/local/bin/gosu
COPY --chmod=755 deploy/mysql/01-users.sh /docker-entrypoint-initdb.d/01-users.sh
USER 999:999
HEALTHCHECK --interval=5s --timeout=5s --retries=30 CMD mysqladmin ping --protocol=tcp -h 127.0.0.1 --silent
