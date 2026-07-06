# ----------------------------------------------
# Stage 1: Build
# ----------------------------------------------
FROM gradle:8.14-jdk21 AS builder

WORKDIR /build

# Копіюємо файли, потрібні для завантаження залежностей
COPY app/build.gradle app/settings.gradle ./
COPY app/gradle ./gradle

RUN gradle dependencies --no-daemon || return 0

# Копіюємо весь код і збираємо Fat JAR
COPY app/ ./
RUN gradle clean shadowJar --no-daemon -x test

# ----------------------------------------------
# Stage 2: Runtime
# ----------------------------------------------
FROM eclipse-temurin:21-jre

WORKDIR /app

# Копіюємо зібраний JAR
COPY --from=builder /build/build/libs/audit-archiver.jar /app/audit-archiver.jar

RUN groupadd -r auditgroup && \
    useradd -r -g auditgroup -d /app -s /bin/false audituser && \
    chown -R audituser:auditgroup /app

USER audituser

# JVM налаштування для контейнера
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.io.tmpdir=/tmp \
    -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/audit-archiver.jar"]
