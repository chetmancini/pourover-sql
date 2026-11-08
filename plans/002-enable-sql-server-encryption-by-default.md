# Plan 002: Enable SQL Server encryption by default

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 5b74317..HEAD -- src/main/java/io/github/chetmancini/pouroversql/clients/SQLServerClient.java src/main/java/io/github/chetmancini/pouroversql/ConnectionParams.java src/test/java/io/github/chetmancini/pouroversql/clients/ClientFactoryTest.java docs/drivers.md docs/engines.md CHANGELOG.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: MED
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `5b74317`, 2026-11-06

## Why this matters

Pour-Over currently overrides the Microsoft JDBC driver's secure encryption default with `encrypt=false` whenever the caller omits the property. This silently disables transport encryption while leaving certificate trust strict, which can also make an ordinary secure connection fail rather than safely negotiate it. Leaving `encrypt` unset unless explicitly supplied restores the driver's secure default, preserves `trustServerCertificate=false`, and retains `encrypt=false` as a deliberate compatibility opt-out.

## Current state

- `src/main/java/io/github/chetmancini/pouroversql/clients/SQLServerClient.java` — constructs `SQLServerDataSource`. Its `flag` helper converts an omitted boolean property to `false`, and `initDataSource()` applies that result to both `encrypt` and `trustServerCertificate`.
- `src/main/java/io/github/chetmancini/pouroversql/ConnectionParams.java` — public property documentation currently says both settings default to `false`.
- `src/test/java/io/github/chetmancini/pouroversql/clients/ClientFactoryTest.java` — unit-tests driver data-source properties. `sqlServerEncryptDefaultsToFalseAsBefore` explicitly pins the insecure legacy behavior and must be replaced, not retained or renamed around the same assertion.
- `docs/drivers.md` — identifies tested driver `com.microsoft.sqlserver:mssql-jdbc:13.6.2.jre11` and states SQL Server is not e2e tested.
- `docs/engines.md` — repeats the incorrect statement that both properties default to `false`.
- `CHANGELOG.md` — unreleased behavior notes likewise say SQL Server `encrypt` and `trustServerCertificate` default to `false`.

The implementation to change is exactly:

```java
// SQLServerClient.java:23-26,40-56
/**
 * Client for SQLServer. Supported custom properties are listed on {@link ConnectionParams};
 * {@code encrypt} and {@code trustServerCertificate} default to {@code false}.
 */
// ...
private boolean flag(String key) {
    Boolean value = this.booleanProperty(key);
    return value != null && value;
}

@Override
public DataSource initDataSource() {
    SQLServerDataSource ds = new SQLServerDataSource();
    ds.setUser(this.params.user());
    ds.setPassword(this.params.password());
    ds.setServerName(this.host(DEFAULT_HOST));
    ds.setPortNumber(this.port(DEFAULT_PORT));
    ds.setDatabaseName(this.params.database());

    // String overload: setEncrypt(boolean) is deprecated for removal in mssql-jdbc.
    ds.setEncrypt(Boolean.toString(this.flag("encrypt")));
    ds.setTrustServerCertificate(this.flag("trustServerCertificate"));
```

The legacy test pin is:

```java
// ClientFactoryTest.java:112-120
@Test
void sqlServerEncryptDefaultsToFalseAsBefore() {
    SQLServerDataSource ds = (SQLServerDataSource) ClientFactory.makeSQLClient(SqlEngine.SQLSERVER,
            new ConnectionParams(null, null, "u", null, "db", null)).dataSource();
    assertThat(ds.getServerName()).isEqualTo("localhost");
    assertThat(ds.getPortNumber()).isEqualTo(1433);
    assertThat(ds.getEncrypt()).isEqualToIgnoringCase("false");
    assertThat(ds.getTrustServerCertificate()).isFalse();
}
```

At the planned-at commit, a direct probe of the tested `mssql-jdbc` driver reported a newly constructed `SQLServerDataSource.getEncrypt()` value of `True`; the same probe through `SQLServerClient` reported `false`. The unit assertion should therefore expect the driver default to be encryption enabled (case-insensitive `true`) while `trustServerCertificate` remains `false`. “Strict” here means the existing strict certificate-validation posture: do not relax trust to make encryption work. This plan does **not** add support for the separate string value `encrypt=strict`; `ConnectionParams` still parses this option as a boolean.

The existing explicit-property coverage must remain intact:

```java
// ClientFactoryTest.java:71-99 (excerpt)
props.setProperty("encrypt", "true");
props.setProperty("trustServerCertificate", "true");
// ... other supported SQL Server properties ...
assertThat(ds.getEncrypt()).isEqualToIgnoringCase("true");
assertThat(ds.getTrustServerCertificate()).isTrue();
assertThat(ds.getTrustStore()).isEqualTo("/ts.jks");
assertThat(ds.getTrustStoreType()).isEqualTo("JKS");
assertThat(ds.getAuthentication()).isEqualToIgnoringCase("ActiveDirectoryPassword");
assertThat(ds.getColumnEncryptionSetting()).isEqualToIgnoringCase("Enabled");
assertThat(ds.getFailoverPartner()).isEqualTo("backup");
assertThat(ds.getHostNameInCertificate()).isEqualTo("*.example");
```

`AbstractSQLClient.booleanProperty` (context only, lines 74–80 and 96–109) returns null for an omitted property and otherwise parses the documented boolean spellings. Reuse it; do not add another parser. Follow the existing Java 21, four-space indentation, explicit-import, JUnit 6, and AssertJ conventions in `ClientFactoryTest`. No database connection is needed to inspect the configured `SQLServerDataSource`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Focused SQL Server setup tests | `./gradlew test --tests 'io.github.chetmancini.pouroversql.clients.ClientFactoryTest'` | exit 0; all selected tests pass |
| Full unit/build regression | `./gradlew build` | exit 0; all unit, example, and demo tests pass |
| Public API documentation | `./gradlew javadoc` | exit 0; Javadoc/doclint emits no warnings |
| Whitespace check | `git diff --check` | exit 0, no output |

The baseline at the planned-at commit passed `./gradlew build` and `./gradlew javadoc`. These tests instantiate and inspect the Microsoft data source but do not connect to SQL Server. Do not run or claim SQLServer e2e coverage; `docs/drivers.md` explicitly says that engine is not e2e tested.

## Scope

**In scope** (the only source, test, and documentation files you may modify):

- `src/main/java/io/github/chetmancini/pouroversql/clients/SQLServerClient.java`
- `src/main/java/io/github/chetmancini/pouroversql/ConnectionParams.java`
- `src/test/java/io/github/chetmancini/pouroversql/clients/ClientFactoryTest.java`
- `docs/drivers.md`
- `docs/engines.md`
- `CHANGELOG.md`

The executor may additionally update this plan's existing row in `plans/README.md`, subject to the executor instructions above.

**Out of scope** (do NOT touch, even though they look related):

- Changing `trustServerCertificate` from its current default `false`, automatically trusting certificates, or changing certificate/host-name verification settings.
- Adding `encrypt=strict` support, new authentication options, driver-version negotiation, connection retries, or new driver properties. Explicit `encrypt=true` and `encrypt=false` are the only new behavior cases to pin beyond omission.
- Changing explicit `encrypt=false`, explicit `encrypt=true`, or any other SQL Server property mapping.
- `AbstractSQLClient.java`, Gradle/driver versions, e2e tests, and all non-SQL Server clients.
- Credential-scrubbing implementation details from plan 001. If both plans are applied in one worktree, preserve the other plan's `ConnectionParams` and documentation changes rather than reverting them.

## Git workflow

- Branch: `advisor/002-enable-sql-server-encryption-by-default`
- Make one logical commit; suggested message: `fix: enable SQL Server encryption by default`
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Defer omitted encryption to the driver and replace the legacy test pin

In `SQLServerClient.initDataSource()`, leave this line unchanged:

```java
ds.setTrustServerCertificate(this.flag("trustServerCertificate"));
```

Replace the unconditional `ds.setEncrypt(Boolean.toString(this.flag("encrypt")))` with a null-check using the existing nullable parser:

```java
Boolean encrypt = this.booleanProperty("encrypt");
if (encrypt != null) {
    // String overload: setEncrypt(boolean) is deprecated for removal in mssql-jdbc.
    ds.setEncrypt(Boolean.toString(encrypt));
}
```

Keep the explanatory comment attached to the string-overload call. Do not call `setEncrypt` at all when the property is absent. Keep the existing `flag` helper because `trustServerCertificate` still needs its omitted-to-false behavior. No other setter or property branch changes.

Update `SQLServerClient` class Javadoc: `encrypt` now follows the JDBC driver default when omitted (enabled in the currently tested driver), and `trustServerCertificate` continues to default to `false`. State that callers may explicitly set `encrypt=false` to opt out.

In `ClientFactoryTest`, delete `sqlServerEncryptDefaultsToFalseAsBefore` and replace it with `sqlServerEncryptUsesDriverDefaultWhenOmitted`. It must create the data source with no custom properties and assert:

- `getServerName()` is `localhost` and `getPortNumber()` is `1433` (the existing defaults remain unchanged);
- `getEncrypt()` is `true`, ignoring case, matching the tested driver's default rather than Pour-Over's old override;
- `getTrustServerCertificate()` is `false`.

Add `sqlServerEncryptCanBeExplicitlyDisabled`: set only `encrypt` to `false`, build the data source, and assert `getEncrypt()` is false and `getTrustServerCertificate()` remains false. Retain `sqlServerPropertiesAreApplied` as the explicit-true and custom-property regression; if its intent is clearer, add a small assertion-focused explicit-true test, but do not duplicate the entire property matrix. Do not reintroduce an omitted-property assertion for false under any name.

Run the focused tests now, before editing docs, so behavior and test expectations are locked together.

**Verify**: `./gradlew test --tests 'io.github.chetmancini.pouroversql.clients.ClientFactoryTest'` → exit 0; omitted encryption is true, explicit false is false, explicit true remains true, and all existing custom-property tests pass.

### Step 2: Align public Javadoc and driver/engine documentation

In `ConnectionParams` Javadoc, change only the SQL Server property description at current lines 32–36. It must say:

- `encrypt` and `trustServerCertificate` are boolean properties;
- omitted `encrypt` is left to the JDBC driver default (enabled in the tested `mssql-jdbc` version), while omitted `trustServerCertificate` defaults to `false`;
- explicit `encrypt=false` opts out;
- the rest of the listed SQL Server properties and all other engines' documentation remain unchanged.

In `docs/drivers.md`, add a concise **SQL Server** bullet under “Engine notes”: omission uses the driver's encryption default (true for `mssql-jdbc:13.6.2.jre11`), certificate trust is not relaxed (`trustServerCertificate=false` by default), and `encrypt=false` is an explicit opt-out. Keep the driver's “Not e2e tested” table entry and the general note unchanged.

In `docs/engines.md`, replace the SQL Server half of the current engine-properties sentence at line 23 with the same behavior. Do not alter the PostgreSQL read-only statement or any e2e claim.

In `CHANGELOG.md`, add a bullet under `1.0.0 (unreleased)` → `Changed` describing the secure default and explicit opt-out. Also update the existing **Engine properties** behavior bullet at current line 100 so it no longer says `encrypt=false` is the default. Keep the historical migration information accurate to the new 1.0.0 behavior; do not create a second release section.

**Verify**: `./gradlew javadoc` → exit 0; changed public Javadoc compiles and doclint reports no warnings.

### Step 3: Run the complete regression and patch gates

Run the full build after code, tests, and docs are complete. Confirm the final patch contains no accidental driver upgrade, unrelated property change, or live-server claim.

**Verify**:

- `./gradlew build` → exit 0; all unit, example, and demo tests pass.
- `git diff --check` → exit 0 with no output.
- `git diff -- src/main/java/io/github/chetmancini/pouroversql/clients/SQLServerClient.java src/test/java/io/github/chetmancini/pouroversql/clients/ClientFactoryTest.java` → shows the conditional `setEncrypt`, the secure omitted-default test, the explicit-false test, and no change to the `trustServerCertificate` setter or other property mappings.
- `git status --short` → shows only in-scope files and, when applicable, `plans/README.md`; no build artifacts or unrelated files are staged/committed.

## Test plan

- Replace the legacy insecure pin with `sqlServerEncryptUsesDriverDefaultWhenOmitted`; assert encryption enabled and strict certificate trust by default, plus the existing host/port defaults.
- Add `sqlServerEncryptCanBeExplicitlyDisabled`; omitted trust remains false.
- Keep `sqlServerPropertiesAreApplied` passing as the explicit-true and existing-custom-properties guard: encrypt true, trustServerCertificate true when explicitly requested, trust store settings, authentication, column encryption, failover partner, integrated security, and host name in certificate retain their current mappings.
- Keep `sqlServerAccessTokenIsApplied` unchanged and passing.
- `./gradlew test --tests 'io.github.chetmancini.pouroversql.clients.ClientFactoryTest'` → all selected tests pass.
- `./gradlew build` and `./gradlew javadoc` → full unit regression and public API doclint pass. These are the only required integration gates; no SQLServer e2e result is claimed.

## Done criteria

- [ ] `SQLServerClient` never calls `setEncrypt` when `encrypt` is omitted, calls it with the caller's boolean value when present, and still defaults `trustServerCertificate` to false.
- [ ] The old `sqlServerEncryptDefaultsToFalseAsBefore` test and its omitted-false assertion are gone; new tests pin omitted true, explicit false, and existing explicit true/custom-property behavior.
- [ ] `ConnectionParams`/`SQLServerClient` Javadoc, `docs/drivers.md`, `docs/engines.md`, and `CHANGELOG.md` consistently describe the driver default, strict certificate trust, and explicit opt-out.
- [ ] No other SQL Server property mapping, driver version, public method signature, or non-SQL Server behavior changed.
- [ ] Focused tests, `./gradlew build`, `./gradlew javadoc`, and `git diff --check` exit 0.
- [ ] Documentation still says SQL Server is not e2e tested; no live-server verification is claimed.
- [ ] No files outside the in-scope list are modified except the permitted `plans/README.md` status row.
- [ ] `plans/README.md` status row updated, unless the reviewer maintains the index.

## STOP conditions

Stop and report back (do not improvise) if:

- The current implementation or legacy test no longer matches the excerpts, or the tested driver no longer reports encryption enabled for a fresh `SQLServerDataSource`. Do not hard-code around a changed driver default; report the version/default mismatch.
- Making omission inherit the driver default appears to require changing `AbstractSQLClient`, the driver version, `trustServerCertificate`, or any file outside Scope.
- Existing explicit true/false or custom-property tests fail after the conditional setter change; do not delete or weaken those guards.
- A reviewer requests `encrypt=strict` support or automatic certificate trust as part of this fix; those are separate behavior changes and are explicitly out of scope.
- A verification step attempts to contact a live SQL Server, or a failure requires Docker/e2e setup rather than the unit-level data-source inspection described here.
- A step's verification fails twice after a reasonable in-scope correction.

## Maintenance notes

- Future `mssql-jdbc` upgrades must rerun `ClientFactoryTest`; the omitted-default assertion is intentionally sensitive to the driver's secure default. If the driver default changes, review the security and compatibility impact rather than automatically editing the expected string.
- Keep `encrypt` handling nullable. Routing it through `flag` again would silently recreate the insecure omission behavior.
- Do not confuse encryption with certificate trust: the secure default here is encryption enabled plus `trustServerCertificate=false`. A user-supplied trust setting remains an explicit caller decision.
- `encrypt=strict` is a distinct Microsoft JDBC mode and is not accepted by the current boolean property contract. Adding it requires its own documented API decision and tests.
- Reviewers should verify that no SQLServer e2e claim slipped into the docs and that all non-encryption property assertions remain unchanged.
