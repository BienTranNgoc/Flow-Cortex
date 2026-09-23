# syntax=docker/dockerfile:1

# --- build stage: compile + package the Spring Boot executable jar ----------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# settings.gradle.kts include()s every subproject, so all directories must
# exist before Gradle can evaluate the build.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc
COPY boot ./boot
COPY modules ./modules
COPY shared ./shared
# bootJar only -> :boot:app's executable jar, no -plain.jar.
RUN chmod +x gradlew \
 && ./gradlew --no-daemon clean :boot:app:bootJar

# --- runtime stage: JRE only, unprivileged user ----------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# curl backs the container HEALTHCHECK.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home spring
USER spring

COPY --from=build /workspace/boot/app/build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
