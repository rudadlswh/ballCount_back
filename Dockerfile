FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew

COPY src ./src
RUN ./gradlew clean bootJar -x test --no-daemon

FROM alpine:3.24
WORKDIR /app

RUN apk add --no-cache openjdk17-jre-headless tzdata ca-certificates \
    && apk upgrade --no-cache
COPY --from=build --chown=10001:10001 /app/build/libs/*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8088
USER 10001:10001

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8088} -jar app.jar"]
