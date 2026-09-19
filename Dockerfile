# =========================
# Stage 1: Build
# =========================
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Copy Maven wrapper and project configuration first
# This improves Docker layer caching
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Make Maven Wrapper executable
RUN chmod +x mvnw

# Download dependencies
RUN ./mvnw dependency:go-offline -B

# Copy application source
COPY src ./src

# Build application
RUN ./mvnw clean package -DskipTests


# =========================
# Stage 2: Runtime
# =========================
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy generated Spring Boot JAR
COPY --from=build /app/target/*.jar app.jar

# Spring Boot application port
EXPOSE 8080

# Start application
ENTRYPOINT ["java", "-jar", "app.jar"]