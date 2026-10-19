# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml ./
COPY web/package.json web/package-lock.json ./web/
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
COPY web ./web
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system erroragent \
    && useradd --system --gid erroragent --home-dir /app --create-home erroragent

WORKDIR /app
COPY --from=build --chown=erroragent:erroragent /workspace/target/erroragent-*.jar /app/erroragent.jar
RUN mkdir -p /app/data/repos && chown -R erroragent:erroragent /app/data

USER erroragent
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl --fail --silent http://127.0.0.1:8080/ >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/erroragent.jar"]
