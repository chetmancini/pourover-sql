# Plan 001: Centralize credential scrubbing

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 5b74317..HEAD -- src/main/java/io/github/chetmancini/pouroversql/CredentialScrubber.java src/main/java/io/github/chetmancini/pouroversql/ConnectionParams.java src/main/java/io/github/chetmancini/pouroversql/Pourover.java src/main/java/io/github/chetmancini/pouroversql/RowStream.java src/main/java/io/github/chetmancini/pouroversql/QueryException.java src/main/java/io/github/chetmancini/pouroversql/clients/DriverManagerDataSource.java src/test/java/io/github/chetmancini/pouroversql/OptionsTest.java src/test/java/io/github/chetmancini/pouroversql/QueryExceptionTest.java src/test/java/io/github/chetmancini/pouroversql/cli/PouroverCommandTest.java src/test/java/io/github/chetmancini/pouroversql/clients/ClientFactoryTest.java README.md docs/drivers.md CHANGELOG.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `5b74317`, 2026-11-06

## Why this matters

Credentials can currently escape through `ConnectionParams.toString()`, the CLI dry run, `DriverManagerDataSource.toString()`, and nested JDBC exception diagnostics. The exposure is broader than the main password: custom properties such as passphrases and credentials, and secret-valued properties in a full Athena JDBC URL, are not consistently collected or removed. One internal scrubber and cycle-safe exception-graph sanitization will make the existing diagnostic paths safe without changing driver configuration or adding a public API.

## Current state

- `src/main/java/io/github/chetmancini/pouroversql/ConnectionParams.java` — immutable connection settings and their log-safe rendering. `isSecretKey` recognizes only three substrings, and `toString()` prints `host` verbatim even though Athena uses a full JDBC URL as `host`.
- `src/main/java/io/github/chetmancini/pouroversql/Pourover.java` — connects clients and wraps JDBC failures in both eager and streaming query setup. It retains only `params.password()` for later exception scrubbing and passes that secret into `RowStream`.
- `src/main/java/io/github/chetmancini/pouroversql/RowStream.java` — lazy query path; its `advance` and `close` methods also call `QueryException.wrap` with the same main-password-only secret. Both callers must migrate with `Pourover`.
- `src/main/java/io/github/chetmancini/pouroversql/QueryException.java` — unchecked JDBC wrapper. It scrubs only the top `SQLException`, and only when that top message contains the main password.
- `src/main/java/io/github/chetmancini/pouroversql/clients/DriverManagerDataSource.java` — `DriverManager` data source used by Athena, Snowflake, and Redshift. Its `toString()` currently includes the raw JDBC URL.
- `src/main/java/io/github/chetmancini/pouroversql/cli/PouroverCommand.java` — CLI; `--dry` prints `ConnectionParams` directly at lines 194–195. No production CLI change should be necessary once `ConnectionParams` is safe.
- `src/main/java/io/github/chetmancini/pouroversql/clients/AwsAthenaClient.java` — context only; lines 45–47 pass `params.host()` to `DriverManagerDataSource` as the full JDBC URL.
- `src/test/java/io/github/chetmancini/pouroversql/OptionsTest.java` — existing `ConnectionParams` and basic `QueryException` regression tests.
- `src/test/java/io/github/chetmancini/pouroversql/cli/PouroverCommandTest.java` — existing captured-stdout/stderr CLI tests, including dry-run tests at lines 133–166.
- `src/test/java/io/github/chetmancini/pouroversql/clients/ClientFactoryTest.java` — existing data-source configuration tests and a Snowflake `toString()` assertion.
- `README.md`, `docs/drivers.md`, and `CHANGELOG.md` — user-facing claims currently describe password redaction but not the complete credential and exception-graph behavior.

The current property recognition and rendering are:

```java
// ConnectionParams.java:103-127
static boolean isSecretKey(String key) {
    String lower = key.toLowerCase(Locale.ROOT);
    return lower.contains("password") || lower.contains("token") || lower.contains("secret");
}

@Override
public String toString() {
    StringBuilder props = new StringBuilder("{");
    for (String name : new TreeSet<>(properties.stringPropertyNames())) {
        if (props.length() > 1) {
            props.append(", ");
        }
        props.append(name).append('=').append(isSecretKey(name) ? REDACTED : properties.getProperty(name));
    }
    props.append('}');
    return "ConnectionParams[host=" + host
            + ", port=" + port
            + ", user=" + user
            + ", password=" + (password == null ? "null" : REDACTED)
            + ", database=" + database
            + ", properties=" + props + "]";
}
```

The full-URL contract is explicit in the same file:

```java
// ConnectionParams.java:45-53
/**
 * <p>{@link #toString()} never includes the password or the value of any property whose key
 * contains {@code password}, {@code token} or {@code secret}.
 *
 * @param host the server host, or for ATHENA the full JDBC URL
 */
```

`Pourover` currently discards every other credential source when it constructs its description and secret field:

```java
// Pourover.java:70-77,87-92
private final DataSource dataSource;
private final String secret;
private final String description;

private Pourover(DataSource dataSource, String secret, String description) {
    this.dataSource = dataSource;
    this.secret = secret;
    this.description = description;
}

public static Pourover connect(SqlEngine engine, ConnectionParams params) {
    Objects.requireNonNull(engine, "engine");
    Objects.requireNonNull(params, "params");
    DataSource ds = ClientFactory.makeSQLClient(engine, params).dataSource();
    return new Pourover(ds, params.password(), "Pourover[engine=" + engine + ", " + params + "]");
}
```

The eager path wraps failures at `Pourover.java:276-278`; streaming setup at lines 181–186 and `RowStream.java:119,156` call the same wrapper with the same `secret`:

```java
} catch (SQLException e) {
    throw QueryException.wrap(e, secret);
}
```

The current wrapper drops all nested diagnostics when it creates a replacement, and leaves an unsanitized original graph intact when the top message does not contain the password:

```java
// QueryException.java:62-76
static QueryException wrap(SQLException e, String secret) {
    SQLException cause = e;
    String driverMessage = e.getMessage() == null ? "" : e.getMessage();
    if (secret != null && !secret.isEmpty() && driverMessage.contains(secret)) {
        driverMessage = driverMessage.replace(secret, REDACTED);
        SQLException scrubbed = new SQLException(driverMessage, e.getSQLState(), e.getErrorCode());
        scrubbed.setStackTrace(e.getStackTrace());
        cause = scrubbed;
    }
    StringBuilder message = new StringBuilder("Query failed");
    if (e.getSQLState() != null) {
        message.append(" [SQLState ").append(e.getSQLState()).append(']');
    }
    message.append(": ").append(driverMessage);
    return new QueryException(message.toString(), cause);
}
```

The other raw diagnostic is:

```java
// DriverManagerDataSource.java:106-109
@Override
public String toString() {
    return "DriverManagerDataSource{url=" + url + ", password=[REDACTED]}";
}
```

At the planned-at commit, focused probes with generated fictional inputs reproduced all three concrete failures: an Athena URL credential appeared in `ConnectionParams.toString()`/CLI dry output, a nested cause retained its credential, and replacing a scrubbed cause discarded its suppressed diagnostics (suppressed count `0`). These are behavioral facts to encode in tests; no audit transcript or external reproduction artifact is needed.

Use the existing conventions in `OptionsTest.java` and `PouroverCommandTest.java`: JUnit 6 (`org.junit.jupiter.api.Test`), AssertJ, generated credentials via `UUID.randomUUID()`, four-space indentation, explicit imports, and no wildcard imports. Unit tests run on Java 21 and use in-memory H2 where a database is needed. Do not add a dependency, logging framework, public utility, or new driver property.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Focused value/scrubber tests | `./gradlew test --tests 'io.github.chetmancini.pouroversql.OptionsTest'` | exit 0; all selected tests pass |
| Exception graph tests | `./gradlew test --tests 'io.github.chetmancini.pouroversql.OptionsTest' --tests 'io.github.chetmancini.pouroversql.QueryExceptionTest'` | exit 0; all selected tests pass |
| CLI and data-source tests | `./gradlew test --tests 'io.github.chetmancini.pouroversql.cli.PouroverCommandTest' --tests 'io.github.chetmancini.pouroversql.clients.ClientFactoryTest'` | exit 0; all selected tests pass |
| Full unit/build regression | `./gradlew build` | exit 0; all unit, example, and demo tests pass |
| Public API documentation | `./gradlew javadoc` | exit 0; Javadoc/doclint emits no warnings |
| Whitespace check | `git diff --check` | exit 0, no output |

The baseline at the planned-at commit passed `./gradlew build` and `./gradlew javadoc`. These gates do not require Docker, a live database, or the user-supplied Athena driver; do not substitute `e2eTest`.

## Scope

**In scope** (the only source, test, and documentation files you may modify):

- `src/main/java/io/github/chetmancini/pouroversql/CredentialScrubber.java` (create; package-private)
- `src/main/java/io/github/chetmancini/pouroversql/ConnectionParams.java`
- `src/main/java/io/github/chetmancini/pouroversql/Pourover.java`
- `src/main/java/io/github/chetmancini/pouroversql/RowStream.java`
- `src/main/java/io/github/chetmancini/pouroversql/QueryException.java`
- `src/main/java/io/github/chetmancini/pouroversql/clients/DriverManagerDataSource.java`
- `src/test/java/io/github/chetmancini/pouroversql/OptionsTest.java`
- `src/test/java/io/github/chetmancini/pouroversql/QueryExceptionTest.java` (create)
- `src/test/java/io/github/chetmancini/pouroversql/cli/PouroverCommandTest.java`
- `src/test/java/io/github/chetmancini/pouroversql/clients/ClientFactoryTest.java`
- `README.md`
- `docs/drivers.md`
- `CHANGELOG.md`

The executor may additionally update this plan's existing row in `plans/README.md`, subject to the executor instructions above.

**Out of scope** (do NOT touch, even though they look related):

- Hiding usernames or other non-secret connection fields. In particular, do not redact an ordinary hostname merely because it contains a word such as `credential`.
- Changing, filtering, decoding, or adding JDBC driver properties; supporting new URL schemes; parsing usernames; URL-encoding/decoding credentials; or redacting arbitrary key/value-looking text that is not in the configured full JDBC URL.
- Adding log statements, diagnostic collection, telemetry, a public sanitizer API, a new module, or a generic exception-mapping framework.
- Reconstructing vendor/driver exception classes by reflection or preserving their concrete types. Generic `SQLException`/`Throwable` replacements are intentional.
- `PouroverCommand.java`, `AwsAthenaClient.java`, Gradle files, and SQL Server encryption behavior. The existing CLI call site should become safe through `ConnectionParams`; SQL Server defaults belong to plan 002.

## Git workflow

- Branch: `advisor/001-centralize-credential-scrubbing`
- Make one logical commit, or separate the helper/exception and diagnostic/documentation changes into two logical commits. Suggested message: `fix: centralize credential scrubbing`
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Add the internal scrubber and route `ConnectionParams` through it

Create `src/main/java/io/github/chetmancini/pouroversql/CredentialScrubber.java` in package `io.github.chetmancini.pouroversql`. The class must be `final` and package-private (no `public` modifier), with this internal shape; private implementation helpers are fine:

```java
final class CredentialScrubber {
    static final String REDACTED = "[REDACTED]";

    static CredentialScrubber from(ConnectionParams params) { ... }
    static CredentialScrubber empty() { ... }
    static boolean isSecretKey(String key) { ... }
    static String scrubJdbcUrl(String url) { ... }
    String scrub(String text) { ... }
}
```

Implement exactly these semantics:

1. `isSecretKey` lower-cases with `Locale.ROOT` and recognizes a key containing any of `password`, `token`, `secret`, `passphrase`, or `credential`. Recognition is case-insensitive and applies both to `ConnectionParams.properties` and to properties embedded in a full JDBC URL.
2. `from(params)` collects the main `params.password()`, the values of every secret-like custom property, and values of secret-like properties in `params.host()` when that host starts with `jdbc:`. Ignore null and blank values (`String.isBlank()`) when building the replacement list so empty/whitespace input cannot cause runaway replacement. Deduplicate values and replace longer values before shorter values so overlapping credentials cannot leave a partially exposed suffix.
3. Use one private parser for property assignments in a full JDBC URL: a key follows `?`, `;`, or `&`, continues to `=`, and its value continues until the next `;` or `&` (or the end). Check the key with `isSecretKey`. This must cover both query-style and Athena semicolon-style URLs. Do not treat the scheme, authority, path, or an ordinary host as a key/value property, and do not collect username values.
4. `scrubJdbcUrl(url)` returns a null, non-`jdbc:` URL, or URL without secret-like properties unchanged. Otherwise, rewrite **only the value spans** of the recognized secret-like property assignments to `REDACTED`, preserving every delimiter, key, ordinary setting, authority, and path byte-for-byte. It uses only credentials parsed from that URL and must not run global secret replacement over the URL: even a short URL credential such as the literal `true` must not alter an unrelated `encrypt=true` setting or similar text in the host. A matcher/rebuilder that replaces only the parsed value group is suitable.
5. `scrub(text)` is the exception-diagnostic operation. It returns null/unchanged text when there is nothing to replace; otherwise it replaces every occurrence of every value collected by that instance with `REDACTED`. It must not mutate `ConnectionParams` or its defensive property copies.
6. `empty()` supplies a scrubber with no values for `Pourover.of(DataSource)`.

Move the secret-key policy out of `ConnectionParams`; remove its now-unused `Locale` import and its private duplicate marker if the helper marker is used instead. Keep the existing defensive-copy behavior, sorted property names, and overall `ConnectionParams[...]` shape. A null main password still renders as `password=null`; any non-null main password, including a blank one, renders as `password=[REDACTED]`. Secret-like property values likewise render as `[REDACTED]`, including blank values. Sanitize the `host` component separately with `CredentialScrubber.scrubJdbcUrl(host)`, then return the structured rendering directly. **Never** call `CredentialScrubber.from(this).scrub(...)` on the complete rendering or on non-URL fields: the main password and custom-property values must not affect unrelated host, user, database, or non-secret property text.

Update `ConnectionParams` Javadoc to state that the main password, secret-like property values, and credentials in full JDBC URL properties are redacted; list all five key substrings.

In `OptionsTest`, retain `connectionParamsToStringRedactsSecrets` and extend/add focused tests. Use generated credentials except for the deliberate short-value collision case below:

- mixed-case `passphrase` and `credential` property names are redacted while a normal property remains visible;
- both query-style and semicolon-style full Athena JDBC URLs redact only secret-valued property spans with mixed-case keys, retain ordinary URL settings, and do not redact an ordinary host whose name merely contains a secret-like word;
- a main password and a custom secret property each using the short literal value `true` are redacted only in their own structured fields, while an ordinary host, user, database, and non-secret property containing the literal word `true` remain intact; separately, a full JDBC URL with a secret property valued `true` retains an unrelated ordinary property valued `true`;
- null and blank main/custom secrets do not change unrelated diagnostic text and do not throw; the structured `password`/secret-property fields still use their established null/redacted rendering.

**Verify**: `./gradlew test --tests 'io.github.chetmancini.pouroversql.OptionsTest'` → exit 0; existing option tests and the new rendering cases pass.

### Step 2: Scrub the complete exception graph without losing diagnostics

Change `Pourover` and `RowStream` to store one `CredentialScrubber` instead of `String secret`:

- `Pourover.connect(...)` constructs it with `CredentialScrubber.from(params)`;
- `Pourover.of(...)` uses `CredentialScrubber.empty()`;
- the private `Pourover` constructor accepts the scrubber, and `stream(...)` passes that same instance to the package-private `RowStream` constructor;
- both `QueryException.wrap` calls in `Pourover` (stream setup at current lines 181–186 and eager execution at lines 276–278) and both calls in `RowStream` (`advance` and `close`) pass the scrubber;
- the existing description and all public method signatures remain unchanged; the `RowStream` constructor is package-private and may change.

Change package-private `QueryException.wrap` to accept that scrubber. Remove the old `String secret` path rather than leaving a parallel sanitizer. Build the outer `QueryException` message from the scrubbed top-level driver message, retaining the existing `Query failed [SQLState ...]: ...` format. Update class/method Javadoc to describe credentials and recursive cause/next/suppressed sanitization.

Implement sanitization as an identity-based graph operation, not a single `SQLException` replacement. The required behavior is:

1. Discover each unique `Throwable` once using identity (`IdentityHashMap`/identity set), following `getCause()`, `SQLException.getNextException()`, and `getSuppressed()`. Use explicit worklists/frames rather than unbounded recursive descent. Do not use `equals`/`hashCode` for visited state; self-references, multi-node cycles, and very deep chains must terminate without `StackOverflowError`.
2. A node is directly dirty when `scrubber.scrub(node.getMessage())` differs from its original message. Compute which nodes need replacement: a directly dirty node and every ancestor that reaches it through cause/next/suppressed edges. A reverse-edge worklist from directly dirty nodes is a straightforward way to propagate this through cycles without recursion overflow. Nodes in unaffected subgraphs must be retained by identity.
3. Copy only nodes that need replacement. Allocate and memoize each original-to-copy mapping by identity **before** linking children, so shared nodes and SQL next-exception cycles can resolve to the same copy. For an `SQLException`, use only `new SQLException(scrubbedMessage, originalSQLState, originalErrorCode)`; for any other throwable that must be copied, use a generic `Throwable` with the scrubbed message. Never reflectively instantiate or reconstruct the original concrete class.
4. On every copied node, preserve the original stack trace with `setStackTrace`. Link children with iterative frames and an identity set of nodes active on the current copy path:
   - Reattach every SQL `nextException` edge with `setNextException`, including self/back edges; the early memoized copies make these cycles representable and preserve them.
   - Reattach acyclic and shared `cause` edges with `initCause`, and acyclic/shared suppressed edges with `addSuppressed` in their original order.
   - If a cause or suppressed edge points to the copy itself or to an active ancestor, omit that back edge instead of calling `initCause`/`addSuppressed`. In particular, never attempt self-cause or self-suppression. This deliberately terminates non-representable cause/suppressed cycles on copied nodes while retaining every reachable node's sanitized message through the remaining edges. Do not use reflection to reproduce an arbitrary overridden cause graph.
   On copied SQL exceptions, preserve each node's own SQLState and vendor error code, not only the root's.
5. If no node needs replacement, return the original root as the cause, leaving even a clean cyclic graph completely untouched. If only a descendant is dirty, ancestors on the path are generic copies, but unaffected siblings/descendants remain the original objects. Never mutate the original exception graph. The only intentional structural loss is termination of cause/suppressed back edges that cannot safely be represented on generic copies through Java 21 public APIs; acyclic diagnostics and SQL next-exception cycles are preserved.

Keep the two existing basic tests in `OptionsTest`, adapting them to the new internal signature. Create `QueryExceptionTest` in the same package for the graph regressions. Use generated credentials and explicit, small stack traces. Cover all of the following with named tests:

- top-level scrubbing retains SQLState, vendor code, and stack trace;
- a credential known only through a mixed-case custom `passphrase`/`credential` property is removed from a nested non-SQL cause and from the outer message;
- a credential extracted from both forms of full Athena JDBC URL is removed when a driver echoes the URL;
- a dirty nested cause is sanitized while SQLState/vendor code/stack traces on copied SQL ancestors are preserved;
- a multi-node `nextException` chain is fully retained and scrubbed, including a clean trailing node retained by identity;
- acyclic suppressed close failures are all retained in order and scrubbed, with their stack traces preserved;
- a self-referencing SQL next-exception cycle terminates during wrapping and remains a cycle in the sanitized graph;
- a test-local `Throwable` subclass overriding `getCause()` simulates a two-node cause cycle; wrapping terminates without stack overflow, sanitizes every reachable message, and omits/terminates the copied cause back edge rather than asserting an impossible generic cause cycle;
- a suppressed back-edge graph constructed with public `addSuppressed` calls terminates without stack overflow or prohibited self-suppression, sanitizes all reachable messages, and omits the copied back edge;
- an entirely clean graph, including a simulated clean cause cycle, returns the original root and original nested nodes by identity;
- null/blank secret collections leave messages and graph identity unchanged;
- a test-local driver-like `SQLException` subclass with a dirty message becomes exactly generic `SQLException`, proving there is no driver-type reconstruction;
- original exception messages and links are not mutated.

Do not weaken `queryExceptionKeepsCauseWhenNothingToScrub`; it is the identity-preservation guard.

**Verify**: `./gradlew test --tests 'io.github.chetmancini.pouroversql.OptionsTest' --tests 'io.github.chetmancini.pouroversql.QueryExceptionTest'` → exit 0; all new graph cases and existing value tests pass.

### Step 3: Close the CLI and data-source diagnostic paths

Make no production edit to `PouroverCommand`; its existing `out.println(params)` at lines 194–195 must inherit the safe rendering. Extend `dryRunPrintsParamsWithRedactedPassword` or add a neighboring dry-run test that supplies:

- an Athena full JDBC host containing a generated credential in a URL property;
- generated custom `passphrase` and `credential` property values through repeated `--custom` options;
- a generated environment password.

Assert exit `0`, empty stderr, no occurrence of any generated credential in stdout/stderr, `[REDACTED]` in stdout, and preservation of the ordinary host/workgroup/non-secret custom setting. Assert the connection factory was not invoked and no output file was created. Reuse the existing `run` helper; do not start a driver or a database.

In `DriverManagerDataSource`, remove the raw URL from `toString()` rather than duplicating URL sanitization in the `clients` package. A fixed result such as `DriverManagerDataSource{password=[REDACTED]}` is sufficient. Update its class Javadoc to say the URL is omitted because it can contain credentials. Keep `url()`, `properties()`, and connection behavior unchanged.

Add a package-level test in `ClientFactoryTest` that constructs `DriverManagerDataSource` directly with a generated credential-bearing JDBC URL and an empty `Properties`, then asserts `toString()` contains neither the full URL nor the generated credential. Tighten the existing Snowflake assertion so it also proves `ds.toString()` does not contain `ds.url()`.

**Verify**: `./gradlew test --tests 'io.github.chetmancini.pouroversql.cli.PouroverCommandTest' --tests 'io.github.chetmancini.pouroversql.clients.ClientFactoryTest'` → exit 0; dry-run and data-source diagnostic tests pass without external services.

### Step 4: Update user-facing guarantees and run all gates

Make concise documentation updates, not a new security guide:

- `README.md`: where `--dry` and error handling currently promise password redaction (including lines 57, 164, and 168), state that known custom-property and full-JDBC-URL credentials are also redacted and nested JDBC cause/next/suppressed diagnostics are sanitized without infinite recursion. Do not claim that impossible cause/suppressed back edges are reproduced on generic copies. Keep the password environment-variable behavior unchanged.
- `docs/drivers.md`: in the Athena section, warn that full URLs can carry credential properties and state that Pour-Over redacts recognized values from its own parameter/dry-run and JDBC exception diagnostics. Do not claim that arbitrary third-party logging is sanitized.
- `CHANGELOG.md`: under `1.0.0 (unreleased)` → `Fixed`, add a bullet covering custom-property/Athena URL redaction and cycle-safe exception sanitization. State that SQLState/vendor/stack data, acyclic cause/next/suppressed diagnostics, and SQL next-exception cycles are preserved; unsafe cause/suppressed back edges on copied nodes are terminated. Update the existing error and dry-run behavior bullets so none still promise protection for only the main password.

Then run every final gate.

**Verify**:

- `./gradlew build` → exit 0; all unit, example, and demo tests pass.
- `./gradlew javadoc` → exit 0; Javadoc/doclint reports no warnings.
- `git diff --check` → exit 0 with no output.
- `git status --short` → shows only in-scope files and, when applicable, `plans/README.md`; no build artifacts or unrelated files are staged/committed.

## Test plan

- Extend `OptionsTest.connectionParamsToStringRedactsSecrets` using its existing AssertJ/UUID pattern. Add separate URL-property, short-literal collision, and null/blank cases rather than one opaque assertion block. The short-literal case must prove that structured redaction never rewrites unrelated `true` text and that URL redaction changes only secret-property value spans.
- Create `QueryExceptionTest` for nested non-SQL causes, SQL next-exception chains and cycles, acyclic suppressed close failures, simulated cause cycles, suppressed back edges, clean-graph identity, generic replacement of driver-specific types, and SQLState/vendor/stack preservation. Each test must inspect the complete relevant reachable graph, not only `QueryException.getMessage()`; cyclic-copy tests assert termination and sanitized messages, not impossible cause/suppressed back-edge preservation.
- Extend `PouroverCommandTest` dry-run coverage for main, custom-property, and Athena URL credentials together; assert both captured streams and that dry mode never connects.
- Extend `ClientFactoryTest` so raw JDBC URLs cannot return through `DriverManagerDataSource.toString()`.
- Focused commands in Steps 1–3 → all selected tests pass.
- `./gradlew build` → full regression suite passes; `./gradlew javadoc` → public docs pass doclint. No SQLServer or Athena e2e claim is made or required.

## Done criteria

- [ ] Exactly one package-private root `CredentialScrubber` owns secret-key recognition, credential collection, URL-only redaction, and exception-text replacement; `ConnectionParams`, `Pourover`, `RowStream`, and `QueryException` all use it, and no public sanitizer or old `String secret` wrapping path remains.
- [ ] `ConnectionParams.toString()` uses structured password/property redaction plus `scrubJdbcUrl(host)` only; it never runs all configured secrets over the complete rendering. Short-secret tests prove unrelated `true` text and ordinary URL settings survive, while null/blank handling remains safe.
- [ ] Main passwords, case-insensitive `password`/`token`/`secret`/`passphrase`/`credential` property values, and secret-valued properties in full JDBC URLs are redacted in their intended diagnostic contexts.
- [ ] CLI `--dry` output is covered for all three credential sources without changing the CLI production call site.
- [ ] `QueryException` sanitizes cause, next-exception, and suppressed graphs with identity-based, non-recursive traversal; preserves SQLState/vendor codes/stack traces, acyclic/shared diagnostics, and SQL next-exception cycles; reuses clean nodes; explicitly terminates unsafe cause/suppressed back edges on copies; and never reconstructs driver-specific classes.
- [ ] `DriverManagerDataSource.toString()` no longer emits its URL and no second sanitizer was introduced in `clients`.
- [ ] README, Athena driver docs, Javadoc, and CHANGELOG match the implemented guarantee and its limits.
- [ ] All focused commands, `./gradlew build`, `./gradlew javadoc`, and `git diff --check` exit 0.
- [ ] No files outside the in-scope list are modified except the permitted `plans/README.md` status row.
- [ ] `plans/README.md` status row updated, unless the reviewer maintains the index.

## STOP conditions

Stop and report back (do not improvise) if:

- Any current-state excerpt differs materially, `QueryException.wrap` has additional callers not shown here, or another production path bypasses the single scrubber.
- The full-JDBC-URL contract has expanded beyond the `ConnectionParams.host()` usage described here, or a requested fix would require changing driver properties or supporting a new URL syntax.
- A cycle test seems to require reflection, mutation of JDK internals, a vendor exception constructor, or preservation of an arbitrary overridden cause back edge. Use the specified public-API test subclasses/back-edge termination; never use reflection. If all reachable nodes cannot be sanitized under those constraints, stop and report rather than silently dropping an entire acyclic diagnostic branch.
- A verification failure appears only because a test pins a driver-specific concrete exception type or expects raw URL output; replace that insecure pin with the behavior specified here, but stop if unrelated tests require a production change outside Scope.
- The CLI dry-run fix seems to require editing `PouroverCommand.java`, or safe data-source output seems to require making the root helper public.
- A step's verification fails twice after a reasonable in-scope correction, or the fix appears to require an out-of-scope file.

## Maintenance notes

- Add future credential-bearing property-name variants to `CredentialScrubber.isSecretKey` and cover both custom properties and full JDBC URL properties. Do not reintroduce local key lists in callers.
- If another engine accepts a full JDBC URL, reuse `CredentialScrubber.scrubJdbcUrl` for structured rendering and `CredentialScrubber.from(ConnectionParams)` for exception diagnostics rather than adding URL parsing in that client. Deliberately reviewed URL syntax changes need new query/semicolon and short-value collision cases.
- Any new JDBC failure path must route through the stored scrubber. `Pourover.of(DataSource)` cannot know pool credentials and therefore has an empty scrubber; do not pretend otherwise.
- Exception sanitization must remain identity-based and iterative. Reviewers should scrutinize memoization-before-linking, active-path back-edge detection, the explicit cause/suppressed back-edge termination, SQL next-exception cycle preservation, SQLState/vendor/stack copying, acyclic suppressed ordering, and identity reuse of clean subgraphs; a simple recursive `getCause()` replacement is neither cycle-safe nor equivalent.
- Third-party drivers and application loggers can emit messages outside Pour-Over's control. This plan sanitizes Pour-Over's own parameter rendering and wrapped JDBC diagnostics only; broader logging redaction is deliberately deferred.
