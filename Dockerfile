# ----------------------------------------------
# Stage 1: Build (Додано docker.io для сумісності з Podman)
# ----------------------------------------------
FROM docker.io/library/gradle:8.14-jdk21 AS builder

WORKDIR /build

# Копіюємо файли конфігурації з підпапки app/
COPY app/build.gradle app/settings.gradle ./
COPY app/gradle ./gradle

RUN gradle dependencies --no-daemon || return 0

# Копіюємо весь код додатка та запускаємо збірку Fat JAR
COPY app/ ./
RUN gradle clean shadowJar --no-daemon -x test

# ----------------------------------------------
# Stage 2: Runtime (Виправлено шлях копіювання JAR-файлу)
# ----------------------------------------------
FROM docker.io/library/eclipse-temurin:21-jre

WORKDIR /app

# ВИПРАВЛЕНО: додано префікс 'app/' та зірочку, оскільки Gradle збирає файл з версією
COPY --from=builder /build/build/libs/audit-archiver-1.0.0.jar /app/audit-archiver.jar

# Залишаємо решту налаштувань рантайму без змін
RUN groupadd -r auditgroup && \
    useradd -r -g auditgroup -d /app -s /bin/false audituser && \
    chown -R audituser:auditgroup /app

USER audituser

ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+ExitOnOutOfMemoryError \
    -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/audit-archiver.jar"]
