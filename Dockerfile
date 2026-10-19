# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY --chmod=0755 gradlew ./gradlew
COPY gradle ./gradle
COPY settings.gradle build.gradle ./
COPY src ./src

RUN ./gradlew bootJar --no-daemon

FROM alpine:3.24 AS runtime
WORKDIR /app

RUN apk add --no-cache openjdk17-jre-headless tzdata ca-certificates curl \
    && apk upgrade --no-cache
COPY --from=build --chown=10001:10001 /workspace/build/libs/*.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx256m -Dfile.encoding=UTF-8"

USER 10001:10001
EXPOSE 8088

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
