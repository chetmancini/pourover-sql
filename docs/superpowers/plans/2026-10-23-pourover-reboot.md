# Pour-Over SQL Reboot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reboot Pour-Over SQL as a modern Java 21 library and CLI under `io.github.chetmancini`, with e2e tests, a no-Docker browser playground, examples, CI, and polished docs.

**Architecture:** Keep the JDBC-to-rows-to-writers pipeline. Replace the mutable params classes and swallow-and-return-null error handling with records and a thrown `QueryException`. Add a streaming API next to list, callback and file output. Characterization tests pin current behavior before any refactor.

**Tech Stack:** Java 21, Gradle Kotlin DSL with version catalog and wrapper, JUnit 5, AssertJ, H2 (unit tests and playground), Testcontainers (Postgres, MySQL e2e only), picocli, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-23-pourover-reboot-design.md`

## Global Constraints

- Java 21 (LTS) build target, toolchain pinned to 21; Gradle Kotlin DSL, version catalog, committed wrapper.
- Java package root `io.github.chetmancini.pouroversql`; Maven group `io.github.chetmancini`; project name `pourover-sql`.
- Existing files keep Simon Data's copyright and Apache notices; new files carry "Copyright 2026 Chet Mancini" under Apache 2.0. Add a `NOTICE`-style attribution; never claim Simon Data's code as solely the author's.
- README credit reads "originally created at Simon Data, Inc., reboot by Chet Mancini"; remove "owned by Simon Data" wording, `simon-data` URLs in POM/README, and the `ADOPTERS.md` Simon Data entry.
- Nothing is published to Maven Central, and the public GitHub repo's branches, Actions and Pages are not touched, without the author's explicit go-ahead. The release workflow is dry-run only.
- Engines unchanged: Snowflake, Redshift, PostgreSQL, MySQL, SQL Server, Athena, Informix. Athena uses the official AWS driver. Informix and Redshift drivers stay only if they resolve from a public repository, otherwise optional and user-supplied.
- E2E (Testcontainers) covers Postgres and MySQL in CI. SQL Server is stretch. Snowflake, Redshift, Athena, Informix are documented as "not e2e tested".
- `slf4j-simple` is never a library dependency (CLI/runtime only). Drop Guava, commons-collections, commons-text, commons-dbutils where the JDK covers them.
- JUnit 5 + AssertJ; Testcontainers only in the e2e suite, never required by the demo.
- No secrets or real data in fixtures. Passwords are never logged and are redacted in `toString`.
- CLI flags stay stable. A breaking Java API at 1.0 is allowed with a migration note in `CHANGELOG.md`.
- Demo: browser playground on in-memory H2, JDK `HttpServer`, no extra web framework, no Docker, no CLI required.

## Review Focus

1. A failing query (bad SQL, unreachable host): old code logged and returned `null` / `-1`; a reasonable user expects an exception, and the CLI to exit non-zero with a message and no password in it.
2. `QueryParams` built with only fetch size, max rows and timeout: old `getLogFrequency()` returned null (inverted check) and could NPE in `RowHandler`; expected is a sane default.
3. Values with commas, quotes, newlines, unicode, `null`, and column labels with spaces or mixed case, in both CSV and JSONL and under snake/camel key-case.
4. Empty result set: expected is a valid empty JSONL file, and a CSV with a header row only if column labels are available, with a row count of 0.
5. CLI run non-interactively (no TTY, password only via `EXTRACT_DB_PASSWORD` or absent): old `System.console()` null caused an NPE; expected is a clear error.

---

### Task 1: Build migration to Gradle Kotlin DSL, Java 21, JUnit 5

**Files:**
- Create: `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`
- Delete: `build.gradle`, `settings.gradle`
- Modify: `gradle.properties`, `.gitignore`, all `src/test/java/**` (JUnit 4 to 5)

**Interfaces:**
- Produces: `./gradlew build` compiles main and tests on JDK 21; `./gradlew test` runs unit tests; `./gradlew shadowJar`-equivalent fat-jar task named `fatJar` (via maintained shadow plugin); root project name `pourover-sql`, group `io.github.chetmancini`.

- [ ] **Step 1:** Generate the wrapper and Kotlin DSL build keeping the current dependency set (versions bumped only as needed to compile on 21); use `java-library`, `application`, the current maintained `com.gradleup.shadow`, `maven-publish`, `signing`.
- [ ] **Step 2:** Migrate the 9 existing test classes to JUnit 5 (`org.junit.jupiter.api.*`), adding AssertJ.
- [ ] **Step 3: Verify**
  Run: `./gradlew clean build`
  Expected: BUILD SUCCESSFUL, all 9 test classes executed.
- [ ] **Step 4: Commit** `build: migrate to Gradle Kotlin DSL, Java 21 and JUnit 5`

### Task 2: Characterization tests against in-memory H2

**Files:**
- Create: `src/test/java/com/simondata/pouroversql/CharacterizationTest.java` (still old package)
- Modify: `build.gradle.kts` (add `testRuntimeOnly` H2)

**Interfaces:**
- Consumes: `SQLExtractor(SQLClient)`, `SQLClient` interface (implement a tiny H2-backed test client extending `AbstractSQLClient` pattern, or a test `SQLClient` that wraps an H2 `DataSource`).
- Produces: a green test class documenting current behavior, including the known bugs marked `@Disabled("fixed in Task 5: ...")` with the expected-after behavior.

- [ ] **Step 1:** Write tests (names and assertions): `csvQuotesCommasAndQuotes` (value `a,"b"` round-trips as `"a,""b"""`), `jsonlEscapesNewlinesAndUnicode`, `nullsAreWrittenAsJsonNull`, `snakeAndCamelKeyCase` (column `FirstName` yields `first_name` and `firstName`), `callbackReceivesEveryRowAndReturnsCount`, `emptyResultWritesEmptyJsonl`, plus the bug pins: `queryAsListReturnsNullOnBadSql`, `threeArgQueryParamsLogFrequencyIsNull`.
- [ ] **Step 2:** Run: `./gradlew test --tests '*CharacterizationTest'`; Expected: PASS (bugs asserted as they currently behave).
- [ ] **Step 3: Commit** `test: characterize current behavior`

### Task 3: Package rename and attribution

**Files:**
- Move: `src/**/com/simondata/pouroversql/**` to `src/**/io/github/chetmancini/pouroversql/**` (use `git mv` to keep history)
- Create: `NOTICE`
- Modify: every moved file's `package`/imports, `build.gradle.kts` (`mainClass`, POM), `README.md` credit line, `ADOPTERS.md`

**Interfaces:**
- Produces: package root `io.github.chetmancini.pouroversql` with sub-packages `clients`, `writers`, `util` unchanged in structure.

- [ ] **Step 1:** `git mv` the trees, rewrite package and import statements, update `mainClass`.
- [ ] **Step 2:** Leave Simon Data headers on existing files; add `NOTICE` stating original creation at Simon Data, Inc. and the reboot's copyright. Update POM URLs/SCM to `github.com/chetmancini/pourover-sql`.
- [ ] **Step 3: Verify**
  Run: `./gradlew clean build` and `git grep -n "com.simondata" -- ':!NOTICE' ':!*.md'`
  Expected: build passes; grep returns no matches.
- [ ] **Step 4: Commit** `refactor: move to io.github.chetmancini package, add attribution`

### Task 4: Dependency upgrade and driver sourcing

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`, `AbstractSQLClient`, `CustomQueryRunner`, `RowHandler`, writers and `TextFormat` as needed

**Interfaces:**
- Consumes: Task 2 tests as the safety net.
- Produces: library with no `commons-dbutils`, Guava, commons-collections or commons-text; plain JDBC in `AbstractSQLClient`/`CustomQueryRunner`; drivers declared `runtimeOnly` (or optional); `slf4j-simple` only on the CLI runtime classpath.

- [ ] **Step 1:** Bump all dependencies to current stable; replace Athena fork with the official AWS Athena JDBC driver; attempt to resolve Informix and Redshift from public repos and mark any that fail as user-supplied (document in a `docs/drivers.md` table).
- [ ] **Step 2:** Replace dbutils `QueryRunner`/`MapListHandler` with plain `PreparedStatement`/`ResultSet` mapping, keeping `LinkedHashMap` column order.
- [ ] **Step 3: Verify**
  Run: `./gradlew clean build dependencies --configuration compileClasspath`
  Expected: build passes; compile classpath contains only the JDK-backed set (slf4j-api, JSON library) and no removed libraries.
- [ ] **Step 4: Commit** `build: upgrade dependencies, drop legacy libraries, source drivers`

### Task 5: Modern API with streaming and real errors

**Files:**
- Create: `ConnectionParams.java`, `QueryOptions.java`, `FormatOptions.java`, `KeyCase.java`, `OutputFormat.java`, `Pourover.java`, `RowStream.java`, `QueryException.java` (all under `io.github.chetmancini.pouroversql`)
- Delete: `SQLExtractor`, `SQLParams*`, `QueryParams`, `FormattingParams`, `InputParams`, `FileOutputFormat`, `KeyCaseFormat` (replace per-engine `*SQLParams` with `ConnectionParams` plus engine-specific URL building inside each client)
- Test: `src/test/java/io/github/chetmancini/pouroversql/PouroverTest.java`

**Interfaces:**
- Produces:
  - `record ConnectionParams(String host, Integer port, String user, String password, String database, Properties properties)`; `toString()` redacts the password.
  - `record QueryOptions(int fetchSize, Integer maxRows, Integer timeoutSeconds, int logFrequency)` with compact-constructor validation (positive values) `static QueryOptions defaults()` (fetch 10_000, log frequency 100_000), and `static QueryOptions of(int fetchSize, Integer maxRows, Integer timeoutSeconds)` using the default log frequency.
  - `enum KeyCase { DEFAULT, SNAKE, CAMEL }`, `enum OutputFormat { JSONL, CSV }`, `record FormatOptions(KeyCase keyCase)`.
  - `final class Pourover`: `static Pourover connect(SqlEngine engine, ConnectionParams params)`, `static Pourover of(javax.sql.DataSource ds)`; `List<Map<String,Object>> list(String sql, QueryOptions o)`; `long forEach(String sql, Consumer<Map<String,Object>> rowConsumer, QueryOptions o)`; `RowStream stream(String sql, QueryOptions o)`; `Path writeTo(String sql, Path file, OutputFormat f, FormatOptions fo, QueryOptions o)`. Each also has an overload using `QueryOptions.defaults()`/default `FormatOptions`.
  - `final class RowStream implements AutoCloseable` with `Stream<Map<String,Object>> rows()` and `close()` releasing the connection.
  - `class QueryException extends RuntimeException` wrapping `SQLException`, message never containing the password.

- [ ] **Step 1: Write failing tests** in `PouroverTest` using `Pourover.of(h2DataSource)`: `listReturnsRowsInColumnOrder`, `streamClosesConnection`, `badSqlThrowsQueryExceptionWithoutPassword`, `optionsFactoryAppliesDefaultLogFrequency` (`QueryOptions.of(500, null, null).logFrequency()` equals 100_000; `new QueryOptions(500, null, null, 0)` throws `IllegalArgumentException`), `emptyResultWritesEmptyJsonl`, `csvSpecialCharactersRoundTrip`, `snakeAndCamelKeyCase`.
- [ ] **Step 2:** Run: `./gradlew test --tests '*PouroverTest'`; Expected: FAIL (types not defined).
- [ ] **Step 3:** Implement the types above; reuse the existing writers (`CSVRowWriter`, `JsonLRowWriter`, `CallbackRowWriter`) behind `Pourover`; delete the old classes and un-`@Disable` or delete the Task 2 bug-pin tests to assert the new behavior.
- [ ] **Step 4:** Run: `./gradlew test`; Expected: PASS.
- [ ] **Step 5:** Add `CHANGELOG.md` migration table (old class to new class) under `## 1.0.0 (unreleased)`.
- [ ] **Step 6: Commit** `feat: records-based API with streaming and QueryException`

### Task 6: CLI on picocli

**Files:**
- Create: `src/main/java/io/github/chetmancini/pouroversql/cli/PouroverCommand.java`
- Delete: `ExtractorRunner.java`
- Test: `src/test/java/io/github/chetmancini/pouroversql/cli/PouroverCommandTest.java`

**Interfaces:**
- Consumes: `Pourover`, `ConnectionParams`, `QueryOptions`, `FormatOptions`, `OutputFormat`, `SqlEngine`.
- Produces: `PouroverCommand implements Callable<Integer>` with the same flags as today (`-u/--user`, `-h/--host`, `-p/--port`, `-d/--database`, `-t/--type`, `-s/--sql`, `--dry`, `--format`, `-f/--file`, `-c/--case`, `--fetchsize`, `--timeout`, `--maxrows`, `--custom k=v`); exit code 0 on success, 1 on query failure, 2 on usage error. Password from `EXTRACT_DB_PASSWORD`, else console, else a clear error when no console exists.

- [ ] **Step 1: Write failing tests** (picocli `CommandLine` with captured streams, H2 URL via `--custom`): `maxrowsFlagIsHonored` (guards the old `maxRows` vs `maxrows` mismatch), `badSqlExitsOneWithoutPassword`, `noConsoleAndNoEnvPasswordExitsTwoWithMessage`, `dryRunPrintsParamsWithRedactedPassword`.
- [ ] **Step 2:** Run: `./gradlew test --tests '*PouroverCommandTest'`; Expected: FAIL.
- [ ] **Step 3:** Implement `PouroverCommand`; set `mainClass` to it; move `slf4j-simple` to the CLI runtime classpath.
- [ ] **Step 4:** Run: `./gradlew test fatJar`; Expected: PASS, fat jar in `build/libs`.
- [ ] **Step 5: Commit** `feat: picocli CLI with stable flags and proper exit codes`

### Task 7: Testcontainers e2e suite

**Files:**
- Create: `src/e2eTest/java/io/github/chetmancini/pouroversql/e2e/PostgresE2ETest.java`, `MySqlE2ETest.java`, `src/e2eTest/resources/seed-*.sql`
- Modify: `build.gradle.kts` (separate `e2eTest` source set and task, not part of `test`)

**Interfaces:**
- Consumes: `Pourover.connect(SqlEngine, ConnectionParams)`, `OutputFormat`.
- Produces: `./gradlew e2eTest` runs against real containers; skipped with a clear message when Docker is unavailable.

- [ ] **Step 1: Write the tests** for each engine against a seeded table: `listsRowsWithTypes` (ints, text, timestamp, null), `writesJsonlAndCsvFiles`, `streamsLargeResultWithSmallFetchSize` (10_000 rows, fetch size 100, constant memory not asserted, count asserted), `connectionFailureThrowsQueryException`.
- [ ] **Step 2:** Run: `./gradlew e2eTest`; Expected: PASS with Docker running.
- [ ] **Step 3: Commit** `test: add Testcontainers e2e for Postgres and MySQL`

### Task 8: Browser playground demo and examples

**Files:**
- Create: `demo/src/main/java/io/github/chetmancini/pouroversql/demo/Playground.java`, `demo/src/main/resources/web/index.html`, `demo/src/main/resources/seed.sql`, `examples/` (three standalone Java files: `ListExample`, `StreamExample`, `FileExample`), `demo/build.gradle.kts`, `examples/build.gradle.kts`
- Test: `demo/src/test/java/.../PlaygroundTest.java`

**Interfaces:**
- Consumes: `Pourover.of(DataSource)`, `list`, `writeTo`/serializers, `KeyCase`.
- Produces: `Playground.main(String[])` starts a JDK `HttpServer` on `localhost:8080` (port overridable via `PORT`) with `POST /query` (body: SQL and key-case, response: JSON with `jsonl`, `csv`, `javaSnippet`, `rowCount`); read-only guard rejecting non-`SELECT` statements; seeded in-memory H2 with sample tables.

- [ ] **Step 1: Write failing tests** with an ephemeral port: `selectReturnsJsonlAndCsv`, `nonSelectIsRejectedWith400`, `badSqlReturns400WithMessage`.
- [ ] **Step 2:** Run: `./gradlew :demo:test`; Expected: FAIL, then PASS after implementing.
- [ ] **Step 3:** Build the single-page UI (SQL box, key-case toggle, tabs for JSONL/CSV/Java call). Apply the `frontend-design` skill guidance for the page.
- [ ] **Step 4:** Add `./gradlew :demo:run` and a JBang entry (`//DEPS`) in `demo/README.md`; compile examples in the `build` task.
- [ ] **Step 5: Verify** by running `./gradlew :demo:run` and loading the page in a browser to check a query renders all three tabs.
- [ ] **Step 6: Commit** `feat: H2 browser playground and examples`

### Task 9: CI, dependency updates, release dry run

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `.github/dependabot.yml`

**Interfaces:**
- Produces: `ci.yml` runs `./gradlew build` on JDK 21 on push and PR and `./gradlew e2eTest` on a Docker-enabled runner; `release.yml` triggers on tag, builds sources and javadoc jars, and publishes only to a local staging directory (the Central step is commented out and marked "enable after namespace verification").

- [ ] **Step 1:** Write the three files; validate syntax with `actionlint` if installed.
- [ ] **Step 2:** Run locally: `./gradlew publishToMavenLocal`; Expected: jar, sources, javadoc, POM with the new coordinates and license and developer data, signing skipped when no key is configured.
- [ ] **Step 3: Commit** `ci: add GitHub Actions, Dependabot, release dry run` (workflows are not pushed or enabled on the public repo until the author says so)

### Task 10: Docs and polish

**Files:**
- Modify: `README.md`, `CONTRIBUTING.md`, `CHANGELOG.md` (rename from `CHANGELOG`), `.gitignore`
- Create: `SECURITY.md`, `docs/drivers.md` (extend from Task 4), `docs/engines.md` (engine matrix with e2e status)
- Delete: `TODO.md` (move the three ideas to a "Roadmap" README section)

**Interfaces:**
- Consumes: the final API and CLI flags from Tasks 5 and 6.

- [ ] **Step 1:** Rewrite the README: one-paragraph pitch, 60-second quickstart (playground link first, then 5-line library snippet, then CLI), engine matrix, CLI reference, attribution line, badges that exist (CI only).
- [ ] **Step 2:** Add Javadoc generation to the build and a Pages workflow file (not enabled).
- [ ] **Step 3: Verify**
  Run: `./gradlew clean build javadoc` and a link check over `README.md` snippets (compile each snippet in `examples/`).
  Expected: build and javadoc succeed with no warnings treated as errors.
- [ ] **Step 4: Commit** `docs: rewrite README and project docs`

---

## Self-review notes

- Spec coverage: stack (T1, T4), attribution/ownership (T3, T10), API modernization with streaming (T5), CLI (T6), e2e (T7), demo and examples (T8), CI and release (T9), docs (T10), compatibility via characterization tests (T2), untestable engines documented (T4, T10).
- Left as builder's calls (per spec): JSON library choice, final naming inside the types above, picocli details, test layout.
- `SQLExtractor` is removed rather than shimmed (1.0 break, migration table in CHANGELOG); say so if you want a deprecated shim instead.
