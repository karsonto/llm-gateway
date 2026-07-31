# syntax=docker/dockerfile:1
# Build for Linux amd64 (e.g. docker buildx build --platform linux/amd64 -t llm-gateway .)

# -----------------------------------------------------------------------------
# Stage 1: Admin UI (Vite)
# -----------------------------------------------------------------------------
FROM --platform=linux/amd64 node:20-bookworm-slim AS admin-builder

WORKDIR /build/admin-web

COPY admin-web/package.json admin-web/package-lock.json ./
RUN npm ci --legacy-peer-deps

COPY admin-web/ ./
RUN npm run build
# vite outDir -> ../src/main/resources/static/admin

# -----------------------------------------------------------------------------
# Stage 2: Java fat jar (Maven + shade)
# -----------------------------------------------------------------------------
FROM --platform=linux/amd64 maven:3.9-eclipse-temurin-8 AS java-builder

WORKDIR /build

COPY pom.xml ./
RUN mvn -q -B dependency:go-offline

COPY src ./src
COPY --from=admin-builder /build/src/main/resources/static/admin ./src/main/resources/static/admin

RUN mvn -q -B package -DskipTests

# -----------------------------------------------------------------------------
# Stage 3: Runtime
# -----------------------------------------------------------------------------
FROM --platform=linux/amd64 eclipse-temurin:8-jre-jammy

LABEL org.opencontainers.image.title="llm-gateway"
LABEL org.opencontainers.image.description="Netty LLM gateway with API key auth and admin console"

WORKDIR /app

RUN groupadd --system gateway \
    && useradd --system --gid gateway --home-dir /app --no-create-home gateway \
    && mkdir -p /app/data /app/config /app/logs \
    && chown -R gateway:gateway /app

COPY --from=java-builder --chown=gateway:gateway /build/target/llm-gateway-1.0-SNAPSHOT.jar /app/app.jar
COPY --chown=gateway:gateway docker/gateway.properties.example /app/config/gateway.properties.example

USER gateway

EXPOSE 8088

# Mount host config: -v /host/config/gateway.properties:/app/config/gateway.properties
ENV GATEWAY_CONFIG=/app/config/gateway.properties
VOLUME ["/app/data", "/app/config", "/app/logs"]

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
