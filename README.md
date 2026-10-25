# OneCare Backend

[![Backend CI](https://github.com/OneCareSystems/onecare-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/OneCareSystems/onecare-backend/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Spring Boot 3.5.16](https://img.shields.io/badge/Spring_Boot-3.5.16-green)
![Maven 3.9.15](https://img.shields.io/badge/Maven-3.9.15-red)
![MySQL 8.4 in CI](https://img.shields.io/badge/MySQL-8.4_in_CI-blue)

OneCare Backend is the REST service for the OneCare Clinic Management System. It supports patient registration, appointments, consultations, prescribing, pharmacy inventory and cash billing. Public APIs support appointment booking and queries forwarded to a separate chatbot service.

## Technology stack

- Java 21, Spring Boot 3.5.16 and the Maven wrapper (Maven 3.9.15).
- Spring MVC, Jakarta Validation, Spring Data JPA/Hibernate and MySQL; CI uses MySQL 8.4.
- Spring Security, Argon2 password hashing and JJWT access/refresh tokens.
- Spring Data Redis for token revocation; Spring Mail for password-reset email.
- Spring AOP auditing, scheduled jobs, Actuator health and springdoc OpenAPI/Swagger UI.
- OpenPDF for PDF report export; Lombok for model boilerplate.
- JUnit Jupiter, Mockito, Spring Boot/Security Test, MockMvc, H2 and JaCoCo.
- Docker with Eclipse Temurin Java 21 build and runtime images.

## Implemented modules

| Module | Backend capabilities |
| --- | --- |
| Authentication and staff | Login, refresh, logout/revocation, password reset, account management and configurable role permissions |
| Patients | Registration, updates, search and clinical history |
| Appointments and schedules | Staff/public booking, available slots, weekly doctor schedules, queue status, clinical notes and no-show scheduling |
| Prescriptions | Issue, view, edit, cancel, dispense preview and partial/follow-up in-house dispensing |
| Pharmacy and inventory | Medicine catalog, stock adjustments, quarantine, low-stock/expiry views and external dispensing events |
| Billing | Consultation and dispensing invoices, invoice line corrections and cash payments |
| Reporting | Clinic reports and pharmacy stock usage, expiry and dispensing history; JSON, CSV and PDF output |
| Administration | Audit log queries, consultation fee settings, backup schedule settings and encrypted backup/restore |
| Chatbot integration | Public query proxy and protected PDF/link knowledge-base management through a separate Python/Django service |

These describe backend implementations, not external-service or frontend acceptance results. Appointment confirmations currently use a logging stub; they do not send SMS/email. External-purchase prescription items are free-text recommendations and do not consume clinic stock or enter clinic dispensing/billing.

## Architecture

```text
REST controller -> service / transaction -> JPA repository -> entity / MySQL
```

Request/response DTOs define API payloads. Security filters apply rate limits and JWT validation; URL role rules and method permissions control protected operations. Audit aspects record annotated actions. Schedulers handle appointment no-shows and monthly backups. Backup export/restore also uses JDBC and encrypted filesystem storage. Redis, SMTP and HTTP clients for the separate chatbot are integration dependencies.

## Project structure

```text
src/main/java/com/onecare/backend/
  controller/                 REST endpoints
  service/                    Domain workflows and transactions
  repository/                 JPA repositories and queries
  entity/                     Database mappings
  dto/                        Request/response payloads
  security/ and config/       JWT, permissions and application configuration
  audit/ and scheduler/       Auditing and scheduled jobs
  chatbot/                    Query and knowledge-base HTTP integration
  exception/, enums/, util/, health/
src/main/resources/           Profile configuration, schema.sql and data.sql
src/test/                     Unit/integration tests and test configuration
migrations/                   Manually applied database upgrade SQL
.mvn/                         Maven wrapper configuration
.github/workflows/ci.yml      Build/test workflow
start-dev.ps1                 Local .env loader and safe dev startup
```

## Prerequisites

- JDK 21. A separate Maven installation is optional; use the included wrapper.
- MySQL (8.4 matches CI) and a dedicated local database with the current schema.
- Redis for token revocation and Redis health checks (default `localhost:6379`).
- A configured separate chatbot service for query/knowledge-base operations (default `http://localhost:8000`). Its implementation is outside this repository.
- SMTP for working password-reset email. Unavailable SMTP reports `DEGRADED` health rather than blocking startup.
- Docker is optional for building/running the container.

## Local development setup

```powershell
git clone https://github.com/OneCareSystems/onecare-backend.git
cd onecare-backend
```

For an existing checkout, run commands from its root. Provision a dedicated MySQL database and local account with permissions for that database. Initialize the schema as described below before starting the application.

Create a local `.env` with your own values. Replace all angle-bracket values in this template before use:

```dotenv
DB_URL=jdbc:mysql://localhost:3306/onecare
DB_USERNAME=<local-database-user>
DB_PASSWORD=<local-database-password>
JWT_SECRET=<base64-encoded-random-signing-key>
FRONTEND_URL=http://localhost:5173
FRONTEND_ALLOWED_ORIGINS=http://localhost:5173
CHATBOT_BASE_URL=http://localhost:8000
CHATBOT_SERVICE_TOKEN=<chatbot-service-token>
REDIS_HOST=localhost
REDIS_PORT=6379
```

Use a Base64-encoded random JWT key of at least 32 bytes. Keep local secrets out of source control. The existing `.env-example` is incomplete: it lacks required keys, contains an inline comment on `DB_URL`, and uses `CHATBOT_URL`; the active profiles use `CHATBOT_BASE_URL` and `CHATBOT_SERVICE_TOKEN`.

### Run on Windows

```powershell
.\start-dev.ps1 -CheckOnly
.\start-dev.ps1
```

The script loads `.env` into the process environment for Maven, selects `dev`, and overrides `spring.sql.init.mode=never` to preserve the existing database. Its check validates the file format and five core variables; it does not verify connectivity or every application requirement. Use literal `KEY=value` entries: full-line comments and outer quotes are supported; inline comments, interpolation and multiline values are not.

### Run directly with Maven

If configuration variables are already exported in your shell:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev' '-Dspring-boot.run.arguments=--spring.sql.init.mode=never'
```

On Linux/macOS, use `chmod +x mvnw` once and replace `.\mvnw.cmd` with `./mvnw`. Direct Maven/JAR startup does not automatically load the repository `.env`.

## Database setup and upgrades

The application uses MySQL and Hibernate schema validation (`ddl-auto=validate`) in `dev`, `staging` and `prod`. It expects tables to exist; Hibernate does not create/update them in these profiles.

**`src/main/resources/schema.sql` drops and recreates application tables. Run it only when deliberately initializing/resetting a disposable database. Never use it as an upgrade script for an existing database.**

For a new local database, connect using a MySQL client, select the dedicated database and explicitly import that file. Optional `src/main/resources/data.sql` supplies local scenario fixtures, including staff accounts; review it before importing, and do not treat it as production account provisioning. No seed passwords are documented here.

The raw `dev` profile enables SQL initialization and defers it until after JPA initialization. It is therefore unsuitable as a first-run schema bootstrap and can reset an existing schema. Both startup commands above disable SQL initialization; import any intended local fixtures explicitly.

Staging and production disable SQL initialization. There is no Flyway/Liquibase runner configured. Apply reviewed upgrade SQL separately through your deployment process. For existing databases, [migration 001](migrations/001_grant_doctor_medicine_read.sql) idempotently adds `DOCTOR`'s `MEDICINE_READ` grant without replacing other role customizations. The permission seeder preserves roles that already have persisted grants.

## Configuration

Shared settings are in [application.yml](src/main/resources/application.yml). Profiles are [dev](src/main/resources/application-dev.properties), [staging](src/main/resources/application-staging.properties) and [prod](src/main/resources/application-prod.properties). Select one with `SPRING_PROFILES_ACTIVE` or the Maven profile argument above.

| Environment variable / property | Purpose and repository defaults |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Required MySQL connection values in all three profiles |
| `JWT_SECRET` | Required Base64 signing key |
| `JWT_ACCESS_TOKEN_EXPIRY`, `JWT_REFRESH_TOKEN_EXPIRY` | Staging/prod token lifetimes in milliseconds; defaults 1800000 / 604800000. Dev fixes these values in its profile |
| `FRONTEND_URL` | Password-reset link base URL |
| `FRONTEND_ALLOWED_ORIGINS` | Comma-separated CORS origins; dev defaults to `http://localhost:5173`; staging/prod require a value |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` | SMTP connection/authentication; host/port default to `localhost` / `587` |
| `REDIS_HOST`, `REDIS_PORT` | Redis endpoint; defaults `localhost` / `6379` |
| `CHATBOT_BASE_URL`, `CHATBOT_SERVICE_TOKEN` | Separate chatbot endpoint and service credential |
| `CHATBOT_MAX_QUERY_CHARS`, `CHATBOT_MAX_BODY_BYTES` | Profile query/body limits; defaults 500 / 8192 |
| `CHATBOT_RATE_LIMIT_PER_MINUTE`, `CHATBOT_MAX_TRACKED_CLIENTS` | Profile chatbot request limits; defaults 20 / 10000 |
| `API_RATE_LIMIT_TRUSTED_PROXIES`, `CHATBOT_TRUSTED_PROXIES` | Trusted proxy IPs/CIDRs; empty by default |
| `CHATBOT_URL_ALLOWLIST` | Profile knowledge-base approved URL domains; empty by default |
| `KB_MAX_UPLOAD_BYTES`, `KB_MAX_REQUEST_BYTES` | Multipart upload/request limits; defaults 10485760 / 11534336 bytes |
| `BACKUP_STORAGE_DIR` | Backup directory; default `./backups`; must be writable by the runtime user |
| `BACKUP_ENCRYPTION_KEY` | Base64-encoded 32-byte AES-256-GCM key; required at startup in staging/prod and for backup operations in dev |
| `billing.consultation-fee` | Initial consultation fee fallback; dev sets 500.00; clinic settings provide the configurable fee |
| `clinic.noshow.grace-period-minutes`, `clinic.noshow.cron` | No-show grace period (15 minutes) and check schedule (every minute) |
| `backup.monthly.cron` | Default `0 59 23 * * ?`; backup runs on the configured day of month |

The shared YAML also accepts `TRUSTED_PROXIES` and `KB_URL_ALLOWLIST`; the profile files override those properties with the `CHATBOT_*` variants above. Leave trusted proxy lists empty for direct connections. With a proxy, configure only trusted addresses and have the proxy replace caller-supplied forwarding headers.

## API and access control

The default HTTP port is **8080**, with local base URL `http://localhost:8080`. Application APIs use `/api`; Actuator and OpenAPI paths are outside that prefix.

- Swagger UI (dev/staging): `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON (dev/staging): `http://localhost:8080/v3/api-docs`
- Public aggregate/component health: `http://localhost:8080/actuator/health`

Production disables Swagger UI and OpenAPI JSON. Use generated OpenAPI documentation for request/response details.

Authentication is stateless: login issues access and refresh JWTs; protected requests send `Authorization: Bearer <access-token>`. Redis stores revoked token hashes with expiration. Roles are **`SUPER_ADMIN`, `ADMIN`, `DOCTOR`, `PHARMACIST`**. URL rules enforce role access, method annotations enforce permissions, and services apply ownership/domain checks where implemented. Authorities load from persisted role grants, falling back to static defaults when a role has none.

Login, refresh, forgot/reset password, `/api/public/**`, available appointment slots, `POST /api/chatbot/query` and health are public. Documentation routes are permitted when enabled. Other routes require authentication and relevant access; knowledge-base management is restricted to doctors and super administrators. Public patients do not have a staff login role.

## Important workflows

- **Appointments:** staff/public booking reserves a doctor/time slot. Queue operations move scheduled appointments through arrival and completion, with cancellation/no-show paths. The no-show scheduler applies the configured grace period. Queue completion attempts automatic invoice creation.
- **Prescriptions and dispensing:** doctors issue prescriptions with in-house or external-purchase items. In-house dispensing previews availability, requires confirmation for a stock shortfall, updates cumulative quantities and stock, and supports follow-up dispensing. Issued prescriptions can be cancelled. External dispensing has separate event, verification and completion operations.
- **Billing:** completed consultations and dispensing events are invoice sources. Payments are cash only; partial payments retain `UNPAID` until the balance is settled. Paid invoices cannot have their lines edited.
- **Backup:** authorized system users can create/download encrypted backups and perform explicitly confirmed destructive restore. Monthly scheduling uses the configured day, clamped to the last day of shorter months. Retain the encryption key and backup files for recovery.

## Testing and CI

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean verify
```

Tests include service/unit tests, security/MockMvc integration tests, concurrency tests and database upgrade coverage. Default test configuration uses H2 in MySQL compatibility mode; Redis and other integrations are replaced/mocked in relevant tests. Run from the repository root: the migration integration test reads `migrations/001_grant_doctor_medicine_read.sql` from disk.

JaCoCo generates local reports at `target/site/jacoco/index.html`. No coverage percentage badge is asserted.

[Backend CI](https://github.com/OneCareSystems/onecare-backend/actions/workflows/ci.yml) runs on pushes and pull requests targeting `develop`/`main`. It uses Java 21, Maven caching and MySQL 8.4, then runs `./mvnw clean verify`. CI initializes `schema.sql` against its disposable database and substitutes a no-op data script so tests create their own fixtures. The workflow consumes repository secrets for JWT, SMTP and frontend URL configuration. It does not deploy the application.

## Docker

Build from the repository root with a running Docker daemon:

```powershell
docker build -t onecare-backend .
```

The build stage uses `eclipse-temurin:21-jdk` and the Maven wrapper. Wrapper/POM layers and `dependency:go-offline -B` precede source copying for dependency caching. Packaging skips tests; run normal Maven verification separately.

The runtime uses `eclipse-temurin:21-jre-alpine`, copies only the executable JAR, runs as `spring:spring`, exposes 8080 and starts with `java -jar app.jar`. It excludes the build JDK, Maven cache and source tree. `.dockerignore` limits the context to wrapper/configuration and source inputs, with the requested `.env-example` exception; local secrets and generated output are excluded. No extra build inputs are needed for packaging with tests skipped.

For a local container, prepare a private `docker-local.env` with the configuration above plus `SPRING_PROFILES_ACTIVE=dev` and `SPRING_SQL_INIT_MODE=never`. Use container-reachable database, Redis and chatbot hostnames: on Docker Desktop, host services can be addressed through `host.docker.internal`; `localhost` refers to the container itself. Mount an existing persistent backup directory whose permissions allow the runtime user to write:

```powershell
docker run --rm --name onecare-backend -p 8080:8080 --env-file docker-local.env --mount 'type=bind,source=<absolute-backup-directory>,target=/app/backups' onecare-backend
```

Replace the mount source before running. `/app` is owned by root, so the non-root application needs the writable backup mount even at startup. Keep this private env file outside version control; the Docker context allowlist excludes it. Check logs and `http://localhost:8080/actuator/health` after launch. Database/Redis failures report `DOWN`; unavailable SMTP can produce `DEGRADED` with HTTP 200. The Dockerfile defines no container healthcheck.

## Deployment

This repository provides the Docker image definition and staging/production profiles. It contains no Compose stack, infrastructure provisioning or automated deployment workflow. Supply external MySQL/Redis endpoints, chatbot credentials, frontend configuration, backup storage/key and SMTP as appropriate. Provision/upgrade the schema separately; staging/prod validate it and never initialize it automatically. Runtime configuration is supplied when launching the container, not baked into the image.

## Troubleshooting

- **Missing required configuration / unresolved placeholders:** select the intended profile and supply its variables. The script's check is narrower than Spring's full startup requirements.
- **Schema validation failure:** initialize a fresh disposable database or apply reviewed upgrades to an existing one. Do not enable the destructive schema initializer on an existing database.
- **JWT key parsing failure:** supply a valid Base64 key of sufficient length rather than a plain-text password.
- **Redis errors or `DOWN` health:** verify Redis connectivity, including container hostnames. Revocation checks depend on Redis.
- **Cannot create backup directory:** make the configured directory writable by the runtime user and mount it persistently for containers.
- **Password reset email unavailable / `DEGRADED`:** check SMTP host, authentication and TLS configuration; booking confirmations remain a separate logging stub.
