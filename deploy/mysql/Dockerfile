# Official MySQL 8.4 LTS, hardened:
#  - runs as the mysql user from the start, so gosu (only used to drop root) is removed
#  - MySQL Shell removed (unused; it bundles its own Python runtime)
#  - OS security updates applied
FROM mysql:9.7.1@sha256:cbade841779f1661300e705721f9d2ff159865cc7a8a291affbff43ac6ec7f1d
RUN microdnf remove -y mysql-shell \
 && microdnf upgrade -y --refresh \
 && microdnf clean all \
 && rm -rf /var/cache/yum /var/cache/dnf /usr/local/bin/gosu
COPY --chmod=755 deploy/mysql/01-users.sh /docker-entrypoint-initdb.d/01-users.sh
USER 999:999
HEALTHCHECK --interval=5s --timeout=5s --retries=30 CMD mysqladmin ping --protocol=tcp -h 127.0.0.1 --silent
