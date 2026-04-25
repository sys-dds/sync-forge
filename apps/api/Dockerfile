FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY apps/api/.mvn .mvn
COPY apps/api/mvnw apps/api/mvnw.cmd apps/api/pom.xml ./
RUN ./mvnw -B -DskipTests dependency:go-offline

COPY apps/api/src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/sync-forge-api-*.jar /app/sync-forge-api.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/sync-forge-api.jar"]
