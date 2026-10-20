# syntax=docker/dockerfile:1.7
# Base images pinned by digest (update deliberately, e.g. with Renovate/Dependabot, then rescan).
FROM maven:3.10.0-eclipse-temurin-21@sha256:9b4877723dadf350b452dd97d9a6401e7b56f98fa9dfe420c32ad909989c7e4c AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package && cp target/plinko-backend-*.jar /build/app.jar
COPY healthcheck/HealthCheck.java /build/health/
RUN javac -d /build/health /build/health/HealthCheck.java

# Distroless: no shell, no package manager, runs as uid 65532.
FROM gcr.io/distroless/java21-debian13:nonroot@sha256:f3ac871fb5802c2739bb23b67212e67f341cbe754131528d2e9849072f9efb9a
WORKDIR /app
COPY --from=build --chown=65532:65532 /build/app.jar /app/app.jar
COPY --from=build /build/health/HealthCheck.class /app/health/
USER 65532:65532
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 CMD ["java", "-Xmx16m", "-cp", "/app/health", "HealthCheck"]
# Secrets mounted as files in /run/secrets (Docker secrets) are read as configuration with the same names.
ENV SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
