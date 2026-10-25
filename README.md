# OneCare Backend

## Overview

This repository contains the backend application for the OneCare system.

Built using:
- Java 21 (LTS)
- Spring Boot 3.5.14
- Spring Data JPA
- Spring Security
- Maven
- MySQL 8.4
- GitHub Actions

---

## Prerequisites

Install:
- JDK 21 (LTS)
- MySQL Server
- Maven (optional, Maven wrapper included)

---

## Installation

### Clone repository

```bash
git clone https://github.com/OneCareSystems/onecare-backend
cd onecare-backend
````

---

## Database Setup

```sql
CREATE DATABASE onecare;
CREATE USER 'test'@'localhost' IDENTIFIED BY 'test';
GRANT ALL PRIVILEGES ON onecare.* TO 'test'@'localhost';
FLUSH PRIVILEGES;
```

---

## Configuration

File location:

```text
src/main/resources/application.properties
```

### Example configuration:

```properties
spring.application.name=backend

# Database Config
spring.datasource.url=jdbc:mysql://localhost:3306/onecare?allowPublicKeyRetrieval=true&useSSL=false
spring.datasource.username=test
spring.datasource.password=test
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

# JPA Config
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect
```

### Rate-limit proxy trust

The rate limiter uses the direct remote address unless the request comes from
a configured trusted proxy. Set `API_RATE_LIMIT_TRUSTED_PROXIES` to a
comma-separated list of proxy IP addresses or CIDR ranges to allow those
proxies' `X-Forwarded-For` values. Leave it unset when the application is
directly exposed, and configure trusted proxies to replace caller-supplied
forwarded addresses with a verified client IP.

---

## Project Structure

src/main/java/com/onecare
```
├── controller      # REST API endpoints
├── service         # Business logic layer
├── repository      # Database access layer
├── entity          # JPA entities
├── dto             # Request/response models
├── config          # Application configurations
├── security        # Security configurations
├── exception       # Global exception 

```
---



## Running Development Server

### Ensure execution permission

```bash
chmod +x mvnw
```

### Run application

```bash
./mvnw spring-boot:run
```

---

## Project Compilation & Verification

```bash
./mvnw clean verify
```

---

## Swagger/OpenAPI Documentation

After application startup:

Swagger UI:

```text
http://localhost:8080/swagger-ui/index.html 
```

OpenAPI Docs:

```text
 http://localhost:8080/v3/api-docs
 ```

---

## CI Pipeline

CI workflow location:

```text
.github/workflows/ci.yml
```

Full documentation (stage map, secrets inventory, branch-protection setup, known
deviations): [docs/CI_CD_PIPELINE.md](docs/CI_CD_PIPELINE.md).

### Pipeline stages (each one its own check)

* `lint` - Checkstyle (`config/checkstyle/checkstyle.xml`)
* `static-analysis` - SpotBugs
* `security-scan` - gitleaks secret scan, GitHub dependency review, OWASP Dependency-Check (fails on CVSS >= 7)
* `build` - `./mvnw -DskipTests package` (bootable jar artifact)
* `test` - setup of an isolated MySQL 8.4 service container, Java 21 with Maven caching, unit + integration tests, OpenAPI document artifact
* `coverage-gate` - JaCoCo check enforcing overall coverage >= 70% (NFRM-003); reported separately from `test`
* `docker-build` - backend container image; publishes a `sha-<commit>` image to GHCR on pushes to `develop` and `main` (SDS 2.6.5 stage 4)
* `ci-gate` - aggregate check that names the failing stage and blocks the merge

Run the same gates locally:

```bash
./mvnw -B checkstyle:check
./mvnw -B compile spotbugs:check
./mvnw -B clean verify
./mvnw -B jacoco:report jacoco:check
```

All credentials are provided through GitHub Secrets (see the documentation link above);
staging deployment and monitoring are deferred.
