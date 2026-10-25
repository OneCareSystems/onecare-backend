# CI/CD Pipeline

Backend CI/CD definition for `onecare-backend`:

```text
.github/workflows/ci.yml
```

Every pipeline stage is a separate GitHub Actions **job**, so each one reports as its **own
check** on the pull request . No stage uses `continue-on-error`, `|| true` or Maven
failure-swallowing flags, so a failing lint or security-scan stage turns its check red and
blocks the merge. All credentials come from GitHub Secrets — no plaintext secret exists
in any workflow file. The coverage gate is a standalone job after `test`, so a coverage
shortfall can only ever fail `coverage-gate`.

Reference documents: 
SRS **NFRM-002** (OpenAPI artefact), **NFRM-003** (overall test coverage
≥ 70%), **NFRPO-002**; SDS **2.6.5** (pipeline stages), **2.5.5** (security testing),
**2.1.6** (static analysis).

## Pipeline stages

| # | Job (check name)    | SDS 2.6.5 stage | Tooling                                                     | Fails the PR when…                                  |
|---|---------------------|-----------------|-------------------------------------------------------------|-----------------------------------------------------|
| A | `lint`              | 2 (code quality)| Checkstyle (`config/checkstyle/checkstyle.xml`)             | any style/unused-import violation is found          |
| B | `static-analysis`   | 2 (code quality)| SpotBugs (`mvn compile spotbugs:check`)                     | any High-severity bug pattern is found              |
| C | `security-scan`     | 2 (security)    | gitleaks, GitHub dependency review, OWASP Dependency-Check  | a secret, a high/critical vulnerable dependency is found |
| D | `build`             | 1               | `mvn -DskipTests package` → bootable jar artefact           | compilation or packaging fails                      |
| E | `test`              | 3               | `mvn clean test` (unit + integration, MySQL 8.4 service)    | any test fails                                      |
| F | `coverage-gate`     | 3               | JaCoCo `jacoco:check` (needs `test`)                        | overall instruction coverage < 70%                  |
| G | `docker-build`      | 4               | Docker Buildx; builds on PRs and publishes SHA-tagged image to GHCR on pushes | the container image does not build or publish       |
| — | `ci-gate`           | —               | aggregate gate (`if: always()`)                             | any stage above failed, was skipped or was cancelled |

Jobs A–E and G run in parallel; `coverage-gate` waits for `test` because it consumes the
coverage data artefact; `ci-gate` waits for everything and **names the failing stage** in its
error output (`::error::CI stage '<name>' did not succeed … the pull request is blocked …`).

### Branch protection (required checks)

Configure the repository so the merge is blocked until CI passes:

1. Settings → Branches → require status checks to pass before merging.
2. Add **`ci-gate`** as the required check (it fails whenever any stage fails and names it),
   and optionally also the individual stage checks (`lint`, `static-analysis`,
   `security-scan`, `build`, `test`, `coverage-gate`, `docker-build`) if you want every stage
   visible as a required check of its own.
3. Require the PR branch to be up to date and dismiss stale reviews.

The `docker-build` job publishes images for pushes to `develop` and `main` as
`ghcr.io/onecaresystems/onecare-backend:sha-<full-commit-sha>`. Pull requests build the
image for validation but do not log in to GHCR or publish it. The job uses the repository's
`GITHUB_TOKEN` with `packages: write`; no separate registry secret is required.

## Secrets inventory

The workflow contains **no production credential literals**. The MySQL fallbacks are disposable
test-container values only. Configure these repository secrets
(Settings → Secrets and variables → Actions):

| Secret                   | Used by       | Required | Purpose                                                     |
|--------------------------|---------------|----------|-------------------------------------------------------------|
| `JWT_SECRET`             | `test`        | yes      | HMAC signing key injected into the test run                 |
| `SMTP_USERNAME`          | `test`        | yes      | Mail sender user for mail-related integration tests         |
| `SMTP_PASSWORD`          | `test`        | yes      | Mail sender password (app password)                         |
| `FRONTEND_BASE_URL`      | `test`        | yes      | Base URL used by password-reset links                       |
| `CI_MYSQL_USER`          | `test`        | optional | MySQL service user; falls back to the ephemeral default     |
| `CI_MYSQL_PASSWORD`      | `test`        | optional | MySQL service password; falls back to the ephemeral default |
| `CI_MYSQL_ROOT_PASSWORD` | `test`        | optional | MySQL root password (health check); default root password   |
| `NVD_API_KEY`            | `security-scan` | optional | NVD API key that speeds up OWASP Dependency-Check updates   |

Notes:

* The MySQL service containers are **ephemeral per run**. The workflow reads the optional
  `CI_MYSQL_*` secrets in the service and test-process environments, with disposable defaults
  for runs where those secrets are unavailable (for example, forked pull requests).
* `NVD_API_KEY` is optional: the OWASP Dependency-Check step runs without it (only slower).
* GitHub's native secret scanning and push protection should be enabled in repository
  settings; they complement the `gitleaks` step.

## Coverage gate

* Threshold: **70%** overall instruction coverage, defined once as the Maven property
  `coverage.gate.minimum` (`0.70`) in `pom.xml` and restated explicitly in the
  `coverage-gate` job so the check log names the enforced value.
* Rule: JaCoCo `BUNDLE` / `INSTRUCTION` / `COVEREDRATIO`, `haltOnFailure=true`.
* The `check` goal is **not bound to any lifecycle phase**, so `mvn test` and `mvn package`
  can never trip the gate. It runs only in the dedicated `coverage-gate` job:

```bash
./mvnw -B jacoco:report
./mvnw -B jacoco:check -Dcoverage.gate.minimum=0.70
```

* Result: when all tests pass but coverage is below 70%, the `test` check stays green and
  **only `coverage-gate` fails**, reported on its own (and named by `ci-gate`). Raise or lower
  the threshold by editing the single `coverage.gate.minimum` property.

## OpenAPI document artefact

The `test` job uploads `target/openapi/openapi.json` (generated while the application context
runs in `CiPipelineIntegrationTest`) as the `openapi-doc` artefact, and the `build` job
uploads the bootable jar as `backend-jar`. Reports uploaded for triage:
`checkstyle-report`, `spotbugs-report`, `dependency-check-report`, `test-reports`,
`coverage-report`.

## Running the pipeline locally

```bash
./mvnw -B checkstyle:check                 # same lint gate as the lint job
./mvnw -B compile spotbugs:check           # same static analysis as static-analysis
./mvnw -B clean test                       # same tests as the test job
./mvnw -B jacoco:report jacoco:check       # same gate as the coverage-gate job
gitleaks dir . -c .gitleaks.toml           # same secret scan as security-scan
docker build --tag onecare-backend:local . # same build as docker-build
```

<!-- ## Known deviations from SDS 2.6.5

1. **Stages 5 and 6 are deferred.** Staging deployment and production monitoring are **not**
   part of this workflow; they remain **deferred** to a follow-up deliverable. CI covers
   stages 1–4 only.
2. **SonarQube substituted.** SonarQube is replaced by the documented combination of
   **Checkstyle** (lint/style gate) + **SpotBugs** (bytecode defect gate), plus OWASP
   Dependency-Check and gitleaks for the security gates. The substitution is deliberate:
   it keeps every gate free, self-hosted and runnable in GitHub Actions without an external
   SonarQube instance or token.
3. **Frontend is split into its own repository.** ESLint and the frontend unit tests run in
   `onecare-frontend`'s own `.github/workflows/ci.yml`, and the **frontend Docker image build
   is therefore also executed there** (it is a deviation only in the sense that it is not in
   *this* workflow). This backend workflow builds and publishes the backend image
   (`docker-build`).
4. **GitHub dependency review** complements OWASP Dependency-Check on pull requests
   (GitHub Advisory DB, `fail-on-severity: high`); both run in the single `security-scan`
   check. -->
