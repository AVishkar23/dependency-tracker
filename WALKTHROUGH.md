# Walkthrough — read this before you talk about the project

Purpose: this is YOUR project to explain in interviews. Everything below is how it
actually works, in the order an interviewer is likely to probe. Don't claim more than
this: it's a personal project, built from scratch and iterated on afterward — first
the CRUD/import skeleton, then the parts that make it interesting: a Maven-accurate
version-ordering engine, a hardened pom parser, a classified outdated report, and
OpenAPI docs. 47 tests, all green in CI.
The honest origin story: "I built dependency automation at Citizens; afterward I
rebuilt the core idea myself in Spring Boot to learn the framework."

## The 30-second pitch

"It's a Spring Boot REST API that tracks Maven dependencies across projects. You
register a project, upload its pom.xml, and it parses out every dependency —
resolving property placeholders, dependencyManagement pins, and parent-POM
inheritance. Each dependency can have a 'latest allowed' version — the team's policy
ceiling — and the API reports what's outdated, classified by how big the version gap
is. The comparisons use a from-scratch implementation of Maven's version ordering,
because string comparison gets versions wrong. Data's in PostgreSQL via Spring Data
JPA, it runs in Docker, and 47 tests cover the version engine, the parser, and the
full API flow."

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
- **Parser:** `PomParserService` uses the JDK DOM parser (`DocumentBuilderFactory`),
  namespace-aware (elements matched by local name, so real poms with the Maven
  `xmlns` parse the same as bare ones). What it handles, and why each matters:
  (1) `${...}` placeholders are interpolated *anywhere* in a string and resolved
  recursively — that's what makes CI-friendly versions work
  (`<version>${revision}</version>` + `${project.version}` in a dependency);
  unknown placeholders stay literal rather than crashing the import.
  (2) A dependency with no `<version>` inherits the pin from this pom's own
  `<dependencyManagement>`; an *imported* BOM (scope `import`) is deliberately not
  followed — its pins live in another artifact — so BOM-only-managed deps keep a
  null version. (3) A declared `<parent>` supplies `project.version`/`groupId` and
  `project.parent.version`. (4) Only the *direct* `<dependencies>` child of
  `<project>` is read, so dependencyManagement entries and `<profiles>` deps are
  not mistaken for real dependencies. (5) Duplicate declarations: last wins, as in
  Maven. (6) A hard range like `[1.0,2.0)` is recorded at the floor it resolves
  from. DOCTYPE is disabled to prevent XXE attacks on untrusted XML, and a
  non-`<project>` root is rejected with a clear message.
- **Version engine:** the `version` package is dependency-free and implements
  Maven's `ComparableVersion` semantics: tokenize on `.`/`-`/digit-letter
  transitions; numeric tokens compare as big integers (`2.9.0 < 2.17.2`);
  qualifier tokens rank alpha < beta < milestone < rc < snapshot < (release) < sp
  with aliases (cr→rc, ga/final→""); trailing null-equivalents (0, "", empty
  sub-lists) are normalized away so `2.15` equals `2.15.0`. `VersionRange` parses
  bracket specs and unions; `VersionGap` classifies a jump as MAJOR/MINOR/PATCH/
  QUALIFIER by finding the first segment where the two versions differ.
- **Outdated rule:** `isOutdated` compares with `MavenVersion`: outdated means the
  ceiling is *strictly newer* than the current version. Know this story — an
  earlier version compared strings for inequality, which flagged a dependency
  whose current version was *ahead* of its ceiling (and called 2.15 ≠ 2.15.0).
  The fix is pinned by a flow test (`outdatedUsesMavenSemanticsNotStringComparison`).
- **Report:** `GET /api/projects/{id}/report` returns total/outdated counts, a
  per-severity breakdown, and the outdated entries sorted worst-first (severity,
  then groupId/artifactId) — built by `ProjectService.report`.
- **Ceiling validation:** setting a ceiling validates it as a plain Maven version
  (`MavenVersion.isValidVersionString`); ranges and garbage get a 400, because a
  ceiling is a single decision, not a constraint spec.
- **Controller:** `ProjectController` — `@RestController`, DTO records in/out, correct
  status codes (201 on create). `ApiExceptionHandler` (`@RestControllerAdvice`) maps
  `NoSuchElementException` → 404 and `IllegalArgumentException` → 400 as RFC 7807
  problem JSON. springdoc generates the OpenAPI spec at `/v3/api-docs` (Swagger UI
  at `/swagger-ui.html`) from `@Tag`/`@Operation` annotations + `OpenApiConfig`.
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
  org scale you'd denormalize or index, but you can't push Maven version ordering
  into SQL — it doesn't exist there — which is why the comparison lives in one
  small, heavily tested Java package.
- *"Why implement ComparableVersion yourself instead of using Maven's?"* — To
  understand the semantics instead of trusting a black box; in production I'd
  happily reuse `org.apache.maven:maven-artifact`'s implementation — the rules
  (qualifier ranks, aliases, normalization) are the same, and my tests encode them
  (27 version tests, including the canonical ordering chain from Maven's docs).
- *"What was the hardest bug class?"* — Two: pom formats (property versions,
  dependencyManagement pins vs imported BOMs, parent inheritance, duplicates,
  ranges — the parser tests cover each) and version-comparison semantics: the
  original outdated check compared strings, so it flagged dependencies that were
  *ahead* of their ceiling. Both are the kind of bug that only shows up with
  adversarial inputs, which is why the test suite concentrates there.
- *"Did you use Spring Boot at Citizens?"* — NO. Say it exactly like this: "At Citizens
  the work was Java services and the dependency tool; Spring Boot is what I used in
  this personal project." Never merge the two stories.

## Before you push it live

1. Run `mvn test` once yourself and watch it pass. (Pushing to GitHub also runs it in
   CI — the green check on the repo is the public proof.)
2. Run `docker compose up --build` and hit the three curl calls in the README.
3. Click through each file once — 20 minutes — so the code feels like yours.
