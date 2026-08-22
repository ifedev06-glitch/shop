# syntax=docker/dockerfile:1.7

FROM --platform=$BUILDPLATFORM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.wagon.http.retryHandler.count=5 -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-jammy

RUN groupadd --system shopsecure \
    && useradd --system --gid shopsecure --home-dir /app shopsecure

WORKDIR /app

COPY --from=build --chown=shopsecure:shopsecure \
    /workspace/target/shopsecure-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod

USER shopsecure

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Xss512k", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2", "-jar", "app.jar"]
