# Build context is the repository root (docker build -f market-data-service/Dockerfile .)
# so the Maven reactor can resolve the full multi-module project.

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY market-data-service/pom.xml market-data-service/pom.xml
COPY matching-engine/pom.xml matching-engine/pom.xml
COPY portfolio-service/pom.xml portfolio-service/pom.xml
COPY gateway-service/pom.xml gateway-service/pom.xml

COPY market-data-service/src market-data-service/src

RUN mvn -pl market-data-service -am package -DskipTests -B

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/market-data-service/target/market-data-service.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]
