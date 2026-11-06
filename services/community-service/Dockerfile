# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY libs/ libs/
COPY services/pom.xml services/
COPY services/identity-service/pom.xml services/identity-service/
COPY services/community-service/pom.xml services/community-service/
COPY gateways/pom.xml gateways/
COPY gateways/api-gateway/pom.xml gateways/api-gateway/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -pl services/community-service -am dependency:go-offline
COPY services/community-service/ services/community-service/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -pl services/community-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS extract
WORKDIR /x
COPY --from=build /src/services/community-service/target/*-exec.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination out

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -u 10001 -G app app
WORKDIR /app
COPY --from=extract /x/out/dependencies/ ./
COPY --from=extract /x/out/spring-boot-loader/ ./
COPY --from=extract /x/out/snapshot-dependencies/ ./
COPY --from=extract /x/out/application/ ./
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8082
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8182/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
