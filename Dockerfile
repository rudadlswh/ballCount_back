# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY --chmod=0755 gradlew ./gradlew
COPY gradle ./gradle
COPY settings.gradle build.gradle ./
COPY src ./src

RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app

COPY --from=build --chown=app:app /workspace/build/libs/*.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx256m -Dfile.encoding=UTF-8"

USER app
EXPOSE 8088

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
