# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY security/TrustStorePolicy.java /opt/java-security/TrustStorePolicy.java
RUN javac --release 17 /opt/java-security/TrustStorePolicy.java \
    && java -cp /opt/java-security TrustStorePolicy sanitize "$JAVA_HOME/lib/security/cacerts" \
    && java -cp /opt/java-security TrustStorePolicy

COPY --chmod=0755 gradlew ./gradlew
COPY gradle ./gradle
COPY settings.gradle build.gradle ./
COPY src ./src

RUN ./gradlew bootJar --no-daemon

# Corretto 17.0.21.1.2 includes the fix for CVE-2026-114938.
FROM amazoncorretto:17-alpine-jdk@sha256:45f029c659c0205534dee537231ba394daaea0b14ecbad59b55af9135a2c5211 AS runtime
WORKDIR /app

RUN apk add --no-cache tzdata 'ca-certificates>=20261020-r0' curl \
    && apk upgrade --no-cache
COPY --from=build /opt/java-security/TrustStorePolicy.class /opt/java-security/
RUN java -cp /opt/java-security TrustStorePolicy sanitize "$JAVA_HOME/lib/security/cacerts" \
    && java -cp /opt/java-security TrustStorePolicy
COPY --chmod=0755 security/docker-entrypoint.sh /usr/local/bin/kbo-entrypoint
COPY --from=build --chown=10001:10001 /workspace/build/libs/*.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx256m -Dfile.encoding=UTF-8"

USER 10001:10001
EXPOSE 8088

ENTRYPOINT ["/usr/local/bin/kbo-entrypoint"]
