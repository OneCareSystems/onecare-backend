# Use the bootable JAR produced by the Maven build (locally or in CI).
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Create a non-privileged system user and group to run the application securely
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

ARG JAR_FILE=target/*.jar
COPY --chown=spring:spring ${JAR_FILE} app.jar

# Document the port that the Spring Boot application listens on
EXPOSE 8080

# Execute the application jar using the lightweight JRE runtime
ENTRYPOINT ["java", "-jar", "app.jar"]
