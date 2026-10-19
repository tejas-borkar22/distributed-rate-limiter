# ---- Stage 1: build the jar (Maven + JDK; discarded afterwards) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Poms first: this layer stays cached until a pom changes
COPY pom.xml .
COPY rate-limiter-core/pom.xml rate-limiter-core/
COPY rate-limiter-api/pom.xml rate-limiter-api/

# Download dependencies (slow, so we want it cached)
RUN mvn -B -q -pl rate-limiter-api -am dependency:go-offline

# Source code second: changes often, so it comes after the slow layer
COPY rate-limiter-core/src rate-limiter-core/src
COPY rate-limiter-api/src rate-limiter-api/src

# Build the jar; skip tests (core's tests need Docker, which isn't available here)
RUN mvn -B -q -pl rate-limiter-api -am package -DskipTests

# ---- Stage 2: runtime (small JRE image only) ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy only the finished jar from stage 1
COPY --from=build /build/rate-limiter-api/target/rate-limiter-api-*.jar app.jar

# Run as a normal user, not root
RUN groupadd --system app && useradd --system --gid app app
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]