# ----------------------------------------------
# Stage 1: Build
# ----------------------------------------------
FROM docker.io/library/gradle:8.14-jdk21 AS builder

WORKDIR /build

# Копіюємо файли конфігурації Gradle з кореня проєкту
COPY build.gradle settings.gradle ./
COPY gradle ./gradle

# Прогріваємо кеш залежностей
RUN gradle dependencies --no-daemon || return 0

# Копіюємо весь вихідний код та збираємо Fat JAR
COPY . .
RUN gradle clean shadowJar --no-daemon -x test

# ----------------------------------------------
# Stage 2: Runtime
# ----------------------------------------------
FROM docker.io/library/eclipse-temurin:21-jre

WORKDIR /app

# Створюємо непривілейованого користувача
RUN groupadd -r auditgroup && \
    useradd -r -g auditgroup -d /app -s /bin/false audituser && \
    chown -R audituser:auditgroup /app

# Копіюємо згенерований Fat JAR та одразу виставляємо права
COPY --chown=audituser:auditgroup --from=builder /build/build/libs/*-all.jar /app/audit-archiver.jar

USER audituser

ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+ExitOnOutOfMemoryError \
    -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/audit-archiver.jar"]
