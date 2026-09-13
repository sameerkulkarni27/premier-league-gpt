# --- Build stage ---
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build

# Cache dependencies separately from source for faster rebuilds.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src src
RUN mvn -B -q clean package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
COPY --from=build /build/target/api-gateway.jar app.jar
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
