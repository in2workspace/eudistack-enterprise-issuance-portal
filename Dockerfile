# ---------------------------------------------------------------------------
# Multi-stage build for the issuance portal service
# ---------------------------------------------------------------------------

# --- Stage 1: build --------------------------------------------------------
FROM docker.io/gradle:9.7.1-jdk25 AS build
ARG SKIP_TESTS=false
# Credentials to download the audit starter from GitHub Packages (read by build.gradle). Build stage only.
ARG GITHUB_ACTOR
ARG GITHUB_TOKEN
WORKDIR /workspace
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY config ./config
COPY src ./src
RUN if [ "$SKIP_TESTS" = "true" ]; then \
      gradle build --no-daemon -x test -x checkstyleMain -x checkstyleTest; \
    else \
      gradle build --no-daemon; \
    fi

# --- Stage 2: runtime ------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S nonroot && adduser -S nonroot -G nonroot
USER nonroot
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/issuance-portal.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/issuance-portal.jar"]
