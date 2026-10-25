# ==============================================================================
# Stage 1: Build Stage (Compiles the source code and packages the JAR)
# ==============================================================================
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Copy Maven wrapper and configuration files first to leverage Docker layer caching.
# This ensures dependencies aren't re-downloaded if pom.xml hasn't changed.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Grant execution permissions to the Maven wrapper script
RUN chmod +x mvnw

# Download all project dependencies offline for faster and cached builds
RUN ./mvnw dependency:go-offline -B

# Copy the actual application source code
COPY src ./src

# Build the Spring Boot application, skipping unit tests to speed up container creation
RUN ./mvnw clean package -DskipTests


# ==============================================================================
# Stage 2: Production Runtime Stage (Lightweight image for running the app)
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Create a non-privileged system user and group to run the application securely
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy only the compiled production JAR file from the build stage
COPY --from=build /app/target/*.jar app.jar

# Document the port that the Spring Boot application listens on
EXPOSE 8080

# Execute the application jar using the lightweight JRE Alpine runtime
ENTRYPOINT ["java", "-jar", "app.jar"]
