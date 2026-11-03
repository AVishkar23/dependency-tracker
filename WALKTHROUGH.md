# Walkthrough — read this before you talk about the project

Purpose: this is YOUR project to explain in interviews. Everything below is how it
actually works, in the order an interviewer is likely to probe. Don't claim more than
this: it's a personal project, built from scratch, ~1 evening of code plus tests.
The honest origin story: "I built dependency automation at Citizens; afterward I
rebuilt the core idea myself in Spring Boot to learn the framework."

## The 30-second pitch

"It's a Spring Boot REST API that tracks Maven dependencies across projects. You
register a project, upload its pom.xml, and it parses out every dependency —
resolving property versions and handling BOM-managed ones. Each dependency can have
a 'latest allowed' version, which is the team's policy ceiling, and the API can list
what's outdated. Data's in PostgreSQL via Spring Data JPA, it runs in Docker, and
there are unit tests for the parser plus a MockMvc test of the whole flow."

## How each piece works

- **Entry point:** `DependencyTrackerApplication` — `@SpringBootApplication` triggers
  component scanning and auto-configuration (embedded Tomcat, JPA, datasource).
- **Entities:** `TrackedProject` (name, repoUrl) and `DependencyEntry`
  (groupId, artifactId, currentVersion, latestAllowedVersion, scope).
  `@ManyToOne` from entry → project. IDs are DB-generated (`GenerationType.IDENTITY`).
- **Repositories:** interfaces extending `JpaRepository` — Spring Data generates the
  SQL. `findByProjectAndGroupIdAndArtifactId` is a *derived query*: the method name
  IS the query.
- **Service layer:** `ProjectService` holds the business rules and `@Transactional`
  boundaries. `importPom` deletes the old entries, flushes (so the unique work in the
  same transaction happens in the right order), inserts the new ones, and carries over
  each dependency's previously set `latestAllowedVersion` by matching groupId+artifactId.
- **Parser:** `PomParserService` uses the JDK DOM parser (`DocumentBuilderFactory`).
  Two subtleties: (1) versions like `${jackson.version}` are resolved from the pom's
  `<properties>` block; (2) only the *direct* `<dependencies>` child of `<project>` is
  read, so `<dependencyManagement>` entries (policy declarations, not real deps) are
  skipped. DOCTYPE is disabled to prevent XXE attacks on untrusted XML.
- **Controller:** `ProjectController` — `@RestController`, DTO records in/out, correct
  status codes (201 on create). `ApiExceptionHandler` (`@RestControllerAdvice`) maps
  `NoSuchElementException` → 404 and `IllegalArgumentException` → 400 with a JSON body.
- **Config:** `application.properties` reads DB settings from env vars with local
  defaults (`${DB_URL:...}`). Tests swap in H2 via `src/test/resources`, so the suite
  needs no real database.
- **Docker:** multi-stage Dockerfile — stage 1 builds with Maven on a Temurin 17 image,
  stage 2 runs the jar on a slim JRE image. `docker-compose.yml` adds PostgreSQL 16 and
  wires the app's env vars to it.

## Likely questions, honest answers

- *"Why Spring Data JPA instead of JDBC?"* — The queries are simple CRUD + derived
  finders; JPA removes boilerplate and gives transactions. For complex reporting SQL
  I'd drop to native queries or JdbcTemplate.
- *"How would you check Maven Central for the real latest version?"* — Next step, not
  built yet: a scheduled job calling Maven Central's REST API per artifact, cached,
  with backoff. The policy ceiling exists because auto-adopting the absolute latest
  breaks builds — that constraint is the lesson from the Citizens tool.
- *"How does the outdated check scale?"* — Currently filtered in memory after loading
  a project's dependencies (a project's dep list is small — hundreds at most). At
  org scale you'd push the comparison into a JPQL query.
- *"What was the hardest bug class?"* — Pom formats: property versions, managed
  dependencies with no version, and dependencyManagement looking identical to real
  dependencies in the XML. The parser tests cover all three.
- *"Did you use Spring Boot at Citizens?"* — NO. Say it exactly like this: "At Citizens
  the work was Java services and the dependency tool; Spring Boot is what I used in
  this personal project." Never merge the two stories.

## Before you push it live

1. Run `mvn test` once yourself and watch it pass. (Pushing to GitHub also runs it in
   CI — the green check on the repo is the public proof.)
2. Run `docker compose up --build` and hit the three curl calls in the README.
3. Click through each file once — 20 minutes — so the code feels like yours.
