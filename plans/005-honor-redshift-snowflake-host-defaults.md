# Plan 005: Honor Redshift and Snowflake host defaults

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 5b74317..HEAD -- src/main/java/io/github/chetmancini/pouroversql/clients/RedshiftClient.java src/main/java/io/github/chetmancini/pouroversql/clients/SnowflakeClient.java src/test/java/io/github/chetmancini/pouroversql/clients/ClientFactoryTest.java docs/engines.md CHANGELOG.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `5b74317`, 2026-11-06

## Why this matters

`ConnectionParams` documents that a null host receives an engine default, but Redshift and Snowflake interpolate the null directly into their JDBC URLs. The resulting `jdbc:redshift://null:5439/db` and `jdbc:snowflake://null` URLs try to use the literal host `null` instead of the localhost convention already used by PostgreSQL and SQL Server. Applying the existing `host("localhost")` helper makes omitted hosts behave as documented without changing explicitly configured hosts, ports, databases, users, or driver properties.

## Current state

- `src/main/java/io/github/chetmancini/pouroversql/ConnectionParams.java` — public connection-settings record; its class Javadoc at lines 23–25 says: "Any field may be null; each engine applies its own defaults (for example `localhost` and the engine's standard port)." This contract is already correct and does **not** need editing.
- `src/main/java/io/github/chetmancini/pouroversql/clients/AbstractSQLClient.java` — shared client helper. Lines 66–68 are the convention to reuse:
  ```java
  protected String host(String defaultHost) {
      return params.host() != null ? params.host() : defaultHost;
  }
  ```
- `src/main/java/io/github/chetmancini/pouroversql/clients/RedshiftClient.java` — builds the Redshift URL. Lines 28 and 38–41 currently are:
  ```java
  private static final int DEFAULT_PORT = 5439;

  private String getRedshiftURL() {
      return String.format("jdbc:redshift://%s:%s/%s",
              this.params.host(), this.port(DEFAULT_PORT), this.params.database()
      );
  }
  ```
- `src/main/java/io/github/chetmancini/pouroversql/clients/SnowflakeClient.java` — builds the Snowflake URL. Lines 43–50 currently are:
  ```java
  @Override
  protected DataSource initDataSource() {
      Properties props = this.driverProperties();
      if (this.params.database() != null) {
          props.setProperty("db", this.params.database());
      }
      String host = this.params.host();
      String port = this.params.port() != null ? ":" + this.params.port() : "";
      return new DriverManagerDataSource("jdbc:snowflake://" + host + port, props);
  }
  ```
- `src/main/java/io/github/chetmancini/pouroversql/clients/PostgreSQLClient.java:32-33,47` and `SQLServerClient.java:29-30,50` — exemplars: each declares `private static final String DEFAULT_HOST = "localhost";` and passes it to `this.host(DEFAULT_HOST)`. Match this constant/helper pattern.
- `src/test/java/io/github/chetmancini/pouroversql/clients/ClientFactoryTest.java` — engine-specific unit tests. `snowflakePassesPropertiesThroughToDriver` (lines 122–136) and `redshiftBuildsUrlAndPassesProperties` (lines 138–146) already pin explicit-host URLs and property pass-through. They cast the result to `DriverManagerDataSource` and inspect `url()`; use that same pattern for omitted hosts.
- `docs/engines.md:15` — currently says Snowflake and Redshift need an explicit host; update this sentence with the code fix so it lists both among the engines whose host defaults to `localhost`.
- `CHANGELOG.md` — the `1.0.0 (unreleased)` / `Fixed` section contains concise user-visible bug fixes.

No live Redshift or Snowflake service was exercised during recon; this plan is strictly about locally constructed data-source URLs and must not claim live-engine verification.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Focused client tests | `./gradlew test --tests 'io.github.chetmancini.pouroversql.clients.ClientFactoryTest'` | exit 0; all `ClientFactoryTest` tests pass |
| Full build and tests | `./gradlew build` | exit 0; `BUILD SUCCESSFUL` |
| Public Javadoc/doclint | `./gradlew javadoc` | exit 0; no Javadoc errors |

At the planned-at commit, the baseline `./gradlew build` and `./gradlew javadoc` passed.

## Scope

**In scope** (the only files you should modify):
- `src/main/java/io/github/chetmancini/pouroversql/clients/RedshiftClient.java`
- `src/main/java/io/github/chetmancini/pouroversql/clients/SnowflakeClient.java`
- `src/test/java/io/github/chetmancini/pouroversql/clients/ClientFactoryTest.java`
- `docs/engines.md`
- `CHANGELOG.md`

**Out of scope** (do NOT touch, even though they look related):
- `ConnectionParams.java` and `AbstractSQLClient.java` — the public contract and shared helper already express the required behavior.
- Defaults for database, user, password, properties, or ports; only the null **host** behavior is defective.
- Other engines, live-engine integration tests, driver configuration, or changes to explicit-host URL syntax.
- Additional documentation beyond `docs/engines.md`: the existing `ConnectionParams` Javadoc already documents the default, so no other docs/Javadoc change is needed.

## Git workflow

- Branch: `advisor/005-honor-redshift-snowflake-host-defaults`.
- Commit the small fix and its regressions together; use `fix: default Redshift and Snowflake hosts to localhost`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Route both URL builders through the existing host helper

In `RedshiftClient.java`, add `private static final String DEFAULT_HOST = "localhost";` beside `DEFAULT_PORT`, and replace only the first URL argument, `this.params.host()`, with `this.host(DEFAULT_HOST)`. Keep `this.port(DEFAULT_PORT)`, the database interpolation, and formatting unchanged.

In `SnowflakeClient.java`, add the same private `DEFAULT_HOST` constant beside the class declaration and change the local assignment to `String host = this.host(DEFAULT_HOST);`. Preserve the existing optional-port logic, database property handling, and driver properties exactly.

**Verify**: `./gradlew test --tests 'io.github.chetmancini.pouroversql.clients.ClientFactoryTest'` → exit 0; the pre-existing explicit-host and property tests still pass.

### Step 2: Add omitted-host regressions and retain explicit-host coverage

In `ClientFactoryTest.java`, add focused tests using `ClientFactory.makeSQLClient(...).dataSource()` and `DriverManagerDataSource.url()`:

- Redshift with `new ConnectionParams(null, null, "u", null, "dev", null)` produces exactly `jdbc:redshift://localhost:5439/dev`.
- Snowflake with `new ConnectionParams(null, null, "u", null, "db", null)` produces exactly `jdbc:snowflake://localhost`.
- Keep the existing Redshift explicit-host assertion `jdbc:redshift://rs.example:5439/dev` and Snowflake explicit-host assertion `jdbc:snowflake://acct.snowflakecomputing.com`; these are the explicit-host controls. If consolidating tests, preserve all existing property assertions as well.

Do not open a connection in these tests. Use placeholder non-secret values as in the current tests.

**Verify**: `./gradlew test --tests 'io.github.chetmancini.pouroversql.clients.ClientFactoryTest'` → exit 0; both omitted-host tests and both explicit-host cases pass.

### Step 3: Record the user-visible fix and run final gates

In `docs/engines.md`, replace line 15 so it reads: “The host defaults to `localhost` for PostgreSQL, MySQL, SQL Server, Informix, Snowflake and Redshift.” Remove the contradictory requirement for an explicit Snowflake/Redshift host; leave every table entry and all other engine guidance unchanged.

Add one bullet under `CHANGELOG.md` → `1.0.0 (unreleased)` → `Fixed`: omitted Redshift and Snowflake hosts now use `localhost` rather than placing the literal text `null` in the JDBC URL. Do not add claims about connecting to a live service.

**Verify**: `./gradlew build` → exit 0 and `BUILD SUCCESSFUL`.

**Verify**: `./gradlew javadoc` → exit 0 with no Javadoc errors.

## Test plan

- Add omitted-host tests for Redshift and Snowflake in `ClientFactoryTest`, modeled after its existing `redshiftBuildsUrlAndPassesProperties` and `snowflakePassesPropertiesThroughToDriver` tests.
- Retain explicit-host tests so the helper does not replace a supplied host.
- Existing Redshift default-port, Snowflake optional-port, database, and property assertions must remain green.
- Verification: `./gradlew test --tests 'io.github.chetmancini.pouroversql.clients.ClientFactoryTest'` → all pass, including two new omitted-host regressions.
- Final regression gates: `./gradlew build` and `./gradlew javadoc` → both exit 0.

## Done criteria

- [ ] A null Redshift host yields `jdbc:redshift://localhost:5439/<database>` and an explicit host is unchanged.
- [ ] A null Snowflake host with no port yields `jdbc:snowflake://localhost` and an explicit host is unchanged.
- [ ] Both clients use `AbstractSQLClient.host(...)` with a private `DEFAULT_HOST` constant; neither URL builder reads `params.host()` directly.
- [ ] `ClientFactoryTest` covers omitted and explicit hosts for both engines without opening connections.
- [ ] `docs/engines.md` lists Redshift and Snowflake among the localhost defaults and no longer requires an explicit host.
- [ ] `CHANGELOG.md` records the fix without a live-engine claim.
- [ ] `./gradlew test --tests 'io.github.chetmancini.pouroversql.clients.ClientFactoryTest'` exits 0.
- [ ] `./gradlew build` exits 0.
- [ ] `./gradlew javadoc` exits 0.
- [ ] No files outside the in-scope list are modified (`git status --short`), apart from the permitted `plans/README.md` status update.
- [ ] `plans/README.md` status row updated unless the reviewer maintains the index.

## STOP conditions

Stop and report back (do not improvise) if:

- The current URL-building excerpts or the shared `host(String)` helper differ from "Current state" after accounting for the drift check.
- Making the two tests pass appears to require editing `ConnectionParams`, `AbstractSQLClient`, another engine, or build configuration.
- A supplied non-null host is altered, or a fix would require defaults for database/user/password rather than only host.
- A step's verification fails twice after a reasonable fix attempt.

## Maintenance notes

- Future engine clients with a localhost convention should reuse `AbstractSQLClient.host(DEFAULT_HOST)` instead of interpolating `params.host()` directly.
- Reviewers should scrutinize that only null-host handling changed: explicit hosts, explicit/default ports, database handling, and driver properties must remain byte-for-byte compatible in their URLs/property maps.
- Live Redshift/Snowflake connectivity remains unverified and is intentionally not part of this plan.
