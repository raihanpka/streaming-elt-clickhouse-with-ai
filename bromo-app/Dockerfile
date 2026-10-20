# Bromo: Multi-stage Dockerfile for bromo-app. Build from the repository root:
#   docker build -f bromo-app/Dockerfile -t bromo-app .
# The native stage is experimental and not part of the plan. The runtime stage uses Eclipse Temurin JRE 21.

# =============================================================================
# Stage 1: build
# =============================================================================
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

# Copy Gradle wrapper and build descriptors first to leverage Docker layer cache
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY bromo-core/ bromo-core/
COPY bromo-app/ bromo-app/

# Build the executable jar (skip tests for image; run tests in CI separately)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :bromo-app:bootJar -x test --no-daemon

# =============================================================================
# Stage 2: extract layers (Spring Boot layered jars)
# =============================================================================
FROM eclipse-temurin:21-jre AS layers

WORKDIR /workspace
COPY --from=build /workspace/bromo-app/build/libs/bromo-app-*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# =============================================================================
# Stage 3: JVM runtime
# =============================================================================
FROM eclipse-temurin:21-jre AS jvm

# Run as non-root
RUN groupadd --system bromo && useradd --system --gid bromo bromo

WORKDIR /app

COPY --from=layers --chown=bromo:bromo /workspace/dependencies/ ./
COPY --from=layers --chown=bromo:bromo /workspace/spring-boot-loader/ ./
COPY --from=layers --chown=bromo:bromo /workspace/snapshot-dependencies/ ./
COPY --from=layers --chown=bromo:bromo /workspace/application/ ./

USER bromo

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="--enable-preview -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENV SPRING_PROFILES_ACTIVE=prod

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -q --spider http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

# =============================================================================
# Stage 4: native (GraalVM). Build with:
#   docker build --target native -t bromo:native .
# Requires a host JVM to produce the image. Use `docker buildx` with
# `aptosid/graalvm-ce:java21-21.0.2` or `ghcr.io/graalvm/graalvm-ce:java21-21.0.2`
# as the buildkit builder for true native builds.
# =============================================================================
FROM ghcr.io/graalvm/graalvm-ce:jdk-25.0.2 AS native

WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY bromo-core/ bromo-core/
COPY bromo-app/ bromo-app/

RUN --mount=type=cache,target=/root/.gradle \
    gu install native-image && \
    ./gradlew :bromo-app:nativeCompile -Pnative -x test --no-daemon

FROM gcr.io/distroless/base-debian12 AS native-runtime

WORKDIR /app
COPY --from=native /workspace/bromo-app/build/native/nativeCompile/bromo-app /app/bromo-app

EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["/app/bromo-app"]
