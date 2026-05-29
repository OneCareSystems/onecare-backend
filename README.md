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

---

## CI Pipeline

CI workflow location:

```text
.github/workflows/ci.yml
```

### Pipeline performs:

* Setup isolated MySQL 8.4 service container
* Setup Java 21 environment with Maven caching
* Run build and tests:

```bash
./mvnw clean verify
```

