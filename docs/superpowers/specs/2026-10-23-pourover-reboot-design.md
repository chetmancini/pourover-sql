# Pour-Over SQL Reboot: Design

Date: 2026-10-23

## Intent

Pour-Over SQL is a small Java library and CLI that runs arbitrary SQL against many databases over JDBC and returns rows as JSONL, CSV, a list of maps, or row-by-row callbacks. It was written at Simon Data (2019) and has been abandoned since.

The author (Chet Mancini) wants to reboot it under their own name as a modern, polished project that is (a) easy for other people to adopt and (b) a credible portfolio piece. The direction stays the same; the quality bar and presentation change.

## Goals

- Same purpose: simple, dynamic SQL in, JSONL/CSV/objects out, from Java or the command line.
- Modern stack (author's decision: "modernized", Java stays; Kotlin only if clearly better, and it is not, see Decisions).
- Proof it works: end-to-end tests against real databases, a demo, and runnable examples.
- Easy adoption: published artifact, one-command demo, clear README, generated API docs.
- Looks maintained: CI, automated releases, contribution docs, a real CHANGELOG.

## Non-goals

- Not an ORM or query builder.
- No new database engines as part of the reboot (the existing set stays; see Scope).
- No rewrite into another language.
- No GUI.

## Anti-goals

- A reboot that is only a rename. If the code is modernized but there is no demo, tests and docs, it has failed.
- Breaking the "simple to use" feel with a heavier API.
- Shipping engines that cannot be tested or built reproducibly just to keep a feature checkbox.

## Current state (verified)

- Java 8, Gradle with legacy config (`compile`, `jcenter`, `mainClassName`, shadow 4.0.2, release plugin, git-publish for gh-pages).
- Package `com.simondata.pouroversql`, Gradle group `simon`. About 2,100 lines in main.
- Engines: Snowflake, Redshift, PostgreSQL, MySQL, SQL Server, AWS Athena, Informix.
- Output: CSV, JSONL, in-memory list, callback. Key-case options.
- Tests: JUnit 4, unit tests only (params, formatting, writers). No database-backed tests.
- Dependencies are old (Guava 27, Gson 2.8.5, commons-*, mysql-connector 8.0.9-rc, postgres 42.2.1, slf4j-simple as a compile dependency of the library).
- Athena uses a third-party fork (`com.syncron.amazonaws:simba-athena-jdbc-driver`) and Informix/Redshift drivers come from non-standard sources; these are build and licensing risks.
- Ownership: README says "owned by Simon Data, Inc."; build.gradle header and POM point at `simon-data`. The LICENSE file is the unfilled Apache template.

## Constraints

- Preserve unrelated history; keep git history (the repo is already `chetmancini/pourover-sql` on GitHub).
- Follow the author's conventions: Java here (they prefer TypeScript elsewhere, but this repo stays Java), `final`/immutability, no `any`-style escape hatches, no secrets or real data in fixtures.
- Nothing is published to Maven Central or pushed as a release without explicit author approval.

## Decisions made by the author

- Stay Java, modernized.
- Open to Kotlin only if actually much better.
- Wants a slick demo, examples and/or e2e tests.
- Agreed direction: move under their name with new packages; Testcontainers-based e2e tests; demo; Maven Central; CI; new README; API docs.

## Calls made by the drafter (author can override)

- **Language:** Java 21 (LTS) as the build target. Kotlin not used: the library is a thin JDBC layer, users are mostly Java developers, and Kotlin would add a runtime dependency for little gain.
- **Build:** Gradle with Kotlin DSL, version catalog, Gradle wrapper committed, toolchains pinned to 21. Replace shadow/release/git-publish with maintained equivalents.
- **Tests:** JUnit 5, AssertJ, Testcontainers. Unit tests migrated from JUnit 4.
- **E2E scope:** Postgres and MySQL (Testcontainers) run in CI. SQL Server is a stretch goal (large image, EULA acceptance). Snowflake, Redshift, Athena, Informix cannot run in containers; they stay covered by unit tests and documented as "not e2e tested".
- **Dependencies:** upgrade all to current stable. Drop Guava, commons-collections, commons-text, and commons-dbutils where the JDK covers them. Gson may be replaced by Jackson or kept; builder decides. Make `slf4j-simple` a runtime/CLI-only dependency, never a library dependency.
- **API:** modernize with records and sealed types for params and engines, `try-with-resources` streaming API (`Stream`/`Iterator` of rows) alongside the existing list/callback/file modes. Keep the old shapes' behavior where it still makes sense; a breaking 1.0 is acceptable.
- **CLI:** keep the same options; move to picocli. Ship as a fat jar and as a JBang-runnable artifact. A native image is out of scope.
- **Demo:** superseded by the author's decision below (no Docker, no CLI): an in-memory H2 browser playground.
- **Examples:** a small `examples/` directory with standalone Java examples that compile in CI.
- **CI/release:** GitHub Actions for build and test on JDK 21, dependency updates via Dependabot or Renovate, a release workflow to Maven Central triggered by tag (publishing step stays disabled until the author approves).
- **Docs:** rewrite README (what, why, 60-second quickstart, engine matrix, CLI reference), CONTRIBUTING, SECURITY, a real CHANGELOG, Javadoc published through GitHub Pages.
- **Name:** keep the project name `pourover-sql`.
- **Housekeeping:** remove `ADOPTERS.md` entry for Simon Data unless the author decides otherwise; replace `TODO.md` with GitHub issues.

## Additional calls from the builder check (drafter's calls, author can override)

- **Compatibility:** no known external consumers other than the author. Before refactoring, write characterization tests for key-case semantics, CSV quoting, null handling and the callback contract so any behavior change is deliberate. The CLI flags stay stable; the Java API may break at 1.0, with a short migration note in the CHANGELOG.
- **Hard-to-source drivers:** use the official AWS Athena JDBC driver instead of the Syncron fork. Informix and Redshift drivers are kept only if they resolve from a public repository; otherwise they become optional, user-supplied drivers (documented, not bundled). Single artifact for the core, with drivers as `runtimeOnly`/optional so users pick what they need.
- **Central publishing:** the author's Sonatype/Central Portal account and namespace verification are assumed not to exist yet. The release workflow is built and dry-run against a local or staging repository only; real publishing is a later, separate step.
- **Existing GitHub repo:** branches, CI and Pages are only touched on the author's explicit go-ahead.

## Decisions on the open items (from the author)

1. **Ownership: keep the original notice, add the author's for new work.** The author wrote the code but says Simon Data owns it. Plan: leave Simon Data's copyright and license notices on existing files; new files and new work carry the author's copyright under Apache 2.0. Rationale (drafter's note, not legal advice): the code was already released under Apache 2.0, which permits modification and redistribution if notices are kept, but does not permit claiming Simon Data's code as the author's own work or implying their endorsement. If the author later gets written permission or assignment from Simon Data, the copyright can move to the author as a follow-up; it is not assumed. Consequences: README drops "owned by Simon Data" wording in favor of an accurate "originally created at Simon Data, Inc., reboot by Chet Mancini" credit; the POM, `ADOPTERS.md` and URLs stop pointing at `simon-data`. Existing public repo and Pages/Actions stay untouched until the author says go.
2. **Package and group ID: `io.github.chetmancini`.** Java package root `io.github.chetmancini.pouroversql`. Maven group `io.github.chetmancini`.
3. **Demo: no Docker and no CLI.** The author wants something that needs neither. Drafter's guess, to be confirmed at approval: a small browser playground served from an embedded in-memory H2 database (JDK `HttpServer`, no extra framework) where you type SQL and see JSONL, CSV and the Java API call side by side, started with a single `main` method you can run from an IDE or JBang and also hosted as static docs if feasible. Docker and Testcontainers remain only for the e2e test suite, not the demo.

## Left to the builder

- Exact API shape of the streaming interface and naming of the new types.
- Choice of JSON library, picocli configuration, and the test layout.
- The order of work (suggested: build and package migration, then tests and CI, then API modernization, then demo and docs).
- Whether to keep the legacy `SQLExtractor` entry point as a deprecated shim.
