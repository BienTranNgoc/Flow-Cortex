# syntax=docker/dockerfile:1

# --- build stage: compile + package the Spring Boot executable jar ----------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# settings.gradle.kts declares every subproject via include(...), so Gradle
# needs each subproject directory to exist before it can even evaluate the
# build — the wrapper/build-file-only caching trick the flat single-module
# build used does not carry over cleanly to this layout.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc
COPY boot ./boot
COPY modules ./modules
COPY shared ./shared
RUN chmod +x gradlew
# bootJar alone -> only :boot:app's executable jar (no -plain.jar from `jar`).
RUN ./gradlew --no-daemon clean :boot:app:bootJar

# --- runtime stage: JRE only, unprivileged user ----------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# curl backs the container HEALTHCHECK against the actuator endpoint.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 --no-create-home spring
USER spring

COPY --from=build /workspace/boot/app/build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
