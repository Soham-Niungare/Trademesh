# Build context is the repository root (docker build -f auth-service/Dockerfile .)
# so the Maven reactor can resolve the shared "common" module.

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

COPY common/src common/src
COPY auth-service/src auth-service/src

RUN mvn -pl auth-service -am package -DskipTests -B

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/auth-service/target/auth-service.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
