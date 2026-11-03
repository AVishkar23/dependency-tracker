# Dependency Tracker

A Spring Boot REST API that keeps track of what Maven dependencies a set of projects
is running — and which ones have fallen behind the newest version their team allows.

It grows out of dependency-automation work I did on a bank fraud-risk engineering team
(keeping every repository's libraries current). That tool was internal; this is my own
from-scratch rebuild of the core idea as a standalone Spring Boot service, built to learn
the framework properly: layered architecture, Spring Data JPA, and a tested REST API.

## What it does

1. Register a project (`POST /api/projects`).
2. Upload its `pom.xml` (`POST /api/projects/{id}/pom`) — the service parses it with the
   JDK DOM parser, resolves `${property}` versions from the pom's `<properties>`, keeps
   BOM-managed dependencies (no version of their own), and ignores the
   `<dependencyManagement>` section, which only declares policy, not actual dependencies.
3. Set the newest allowed version for a dependency
   (`PATCH /api/projects/{id}/dependencies/{dependencyId}`).
4. Ask what's outdated (`GET /api/projects/{id}/dependencies/outdated`).

Re-importing a pom replaces the dependency list but preserves previously set
"latest allowed" ceilings, matched by groupId + artifactId.

## Tech

- Java 17, Spring Boot 3.3 (Web, Data JPA)
- PostgreSQL in Docker; H2 for tests
- JUnit 5 + MockMvc tests: parser unit tests and a full create → import → patch → query flow
- Multi-stage Dockerfile; `docker-compose.yml` runs the app + PostgreSQL together

## Run it

```bash
docker compose up --build
# app on http://localhost:8080, PostgreSQL on localhost:5432
```

Or without Docker (needs a local PostgreSQL, or edit `application.properties`):

```bash
mvn spring-boot:run
```

Every push runs the test suite in GitHub Actions (`.github/workflows/ci.yml`).

## Try it

```bash
# create a project
curl -X POST localhost:8080/api/projects -H 'Content-Type: application/json' \
  -d '{"name":"payments-service","repoUrl":"https://github.com/example/payments"}'

# import its pom.xml
curl -X POST localhost:8080/api/projects/1/pom -H 'Content-Type: application/xml' \
  --data-binary @pom.xml

# set the newest allowed version for dependency 1
curl -X PATCH localhost:8080/api/projects/1/dependencies/1 -H 'Content-Type: application/json' \
  -d '{"latestAllowedVersion":"2.17.2"}'

# see what's outdated
curl localhost:8080/api/projects/1/dependencies/outdated
```

## Tests

```bash
mvn test
```

## Design notes

- **Layers:** controller → service → Spring Data JPA repositories. The controller only
  speaks DTO records; JPA entities never leave the service layer.
- **Parser safety:** the DOM factory disables DOCTYPE declarations entirely — a pom.xml
  is untrusted input, and this blocks XXE / billion-laughs style attacks.
- **Outdated rule:** `latestAllowedVersion != null && != currentVersion`. Version
  comparison here is policy-based (the team's ceiling), not a "newest on Maven Central"
  lookup — that distinction is deliberate: "latest" is not always safe to adopt.
