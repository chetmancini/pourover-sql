# Changelog

## 1.0.0 (unreleased)

### Added

- `Pourover`, a single entry point with `list`, `forEach`, `stream` and `writeTo`, plus the `ConnectionParams`, `QueryOptions`, `FormatOptions`, `KeyCase`, `OutputFormat`, `QueryException`, `RowStream` and `RowFormatter` types.
- `--help` and `--version` on the command line, and the exit codes `0`, `1` and `2`. The other flags existed in 0.x.
- `RowWriter.begin(List<String>)`, a default no-op method called with the column keys before the first row (`CSVRowWriter` uses it to write the header, including for empty results). Custom `RowWriter` implementations compile unchanged and can override it.
- `ResultSets.columnLabels(ResultSet)` is public.
- A browser playground on in-memory H2 (`./gradlew :demo:run`) and runnable examples in `examples/`.
- Unit tests on H2 and an opt-in end-to-end suite for PostgreSQL and MySQL in Testcontainers (`./gradlew e2eTest`).
- A test that fails when the README snippets drift from the compiled examples.
- GitHub Actions workflows (CI, release dry run, Javadoc to Pages on manual dispatch), Dependabot, sources and Javadoc jars, `SECURITY.md`, `docs/drivers.md` and `docs/engines.md`.

### Changed

- The Java API is rebuilt around records and `Pourover`; see the migration table below.
- The package moved from `com.simondata.pouroversql` to `io.github.chetmancini.pouroversql`.
- Gradle Kotlin DSL, Java 21, JUnit 6 (Jupiter API) and AssertJ.
- Behavior changes you may notice (details below): rows are plain `LinkedHashMap`s, JSON output is not HTML-escaped, dates and times are ISO-8601, key-case collisions throw, errors are thrown instead of logged, queries run with autocommit off and are rolled back unless the caller's connection is already in a transaction, CLI exit codes, the CLI no longer forces logging to TRACE, and the Athena driver is user-supplied.

### Removed

- `SQLExtractor` (no deprecated shim), `SQLParams` and its engine subclasses, `QueryParams`, `FormattingParams`, `KeyCaseFormat`, `FileOutputFormat`, `RowHandler`, `PrettyPrintingWriter`, `TextFormat.parseInteger` and `FileRowWriter.openStdOut` (unused).
- commons-cli, Guava, commons-collections, commons-text and commons-dbutils.
- The bundled third-party Athena driver artifact (`com.syncron.amazonaws:simba-athena-jdbc-driver`); supply it yourself (see `docs/drivers.md`).

### Fixed

- `--maxrows` was ignored because the option was read under the wrong key; it now limits rows.
- `QueryParams(fetchSize, maxRows, timeout)` left the log frequency null because of an inverted null check.
- The CLI failed with a `NullPointerException` when there was no password and no console; it now exits `2` with a message.
- Query errors were logged and swallowed (`null` or `-1` returned); they now throw.
- CSV output only quoted `String` values and only on the platform line separator; quoting now follows the rendered text.
- Gson failed on `java.time` values under JDK 21.
- `SqlEngine.byName` upper-cased with the default locale, so `informix` was not found under a Turkish locale; it now uses `Locale.ROOT`.
- Snake and camel key case split all-caps labels into single letters (`USER_ID` became `u_s_e_r_i_d`).
- JSONL failed on `ARRAY`, `CLOB`, `BLOB` and driver-specific values, and `list()` returned LOB/array locators that stopped working once the connection closed; values are now materialized (see Behavior changes).

### Playground (details)

A browser playground on in-memory H2 (`./gradlew :demo:run`) shows JSONL, CSV and the Java call for any SELECT, plus runnable examples in `examples/`; neither needs Docker or the CLI.

### Breaking: new Java API

The Java API is now built around records and a single `Pourover` entry point. `SQLExtractor` has been removed; there is no deprecated shim. The command-line flags are unchanged.

```java
Pourover db = Pourover.connect(SqlEngine.POSTGRESQL,
        new ConnectionParams("localhost", 5432, "app", password, "main"));
List<Map<String, Object>> rows = db.list("SELECT * FROM users");
db.writeTo("SELECT * FROM events", Path.of("events.jsonl"), OutputFormat.JSONL);
try (RowStream stream = db.stream("SELECT * FROM big_table")) {
    stream.rows().forEach(row -> ...);
}
```

#### Migration table

| Old (0.x) | New (1.0) |
|---|---|
| `SQLExtractor(engine, host, port, database, user, password)` | `Pourover.connect(engine, new ConnectionParams(host, port, user, password, database))` (note the argument order) |
| `SQLExtractor(engine, SQLParams)` | `Pourover.connect(engine, ConnectionParams)` |
| `SQLExtractor(SQLClient)` (custom client) | `Pourover.of(DataSource)` |
| `SQLExtractor.queryAsList(sql[, QueryParams])` | `Pourover.list(sql[, QueryOptions])` |
| `SQLExtractor.queryWithCallback(sql, Function[, QueryParams])` returning `Integer` | `Pourover.forEach(sql, Consumer[, QueryOptions])` returning `long` |
| `SQLExtractor.queryToFile(sql, File, FileOutputFormat[, QueryParams])` | `Pourover.writeTo(sql, Path, OutputFormat[, FormatOptions[, QueryOptions]])` |
| _(none)_ | `Pourover.stream(sql[, QueryOptions])` returning an `AutoCloseable` `RowStream` |
| `SQLParams`, `PostgreSQLSQLParams`, `SQLServerSQLParams`, `SnowflakeSQLParams` | `ConnectionParams` record; engine options go in its `properties` (keys listed in its Javadoc) |
| `QueryParams(fetchSize, maxRows, timeout)` | `QueryOptions.of(fetchSize, maxRows, timeoutSeconds)` or `new QueryOptions(fetchSize, maxRows, timeoutSeconds, logFrequency)` |
| `QueryParams.getDefaultQueryParams()` | `QueryOptions.defaults()` |
| `FormattingParams` / `FormattingParams.setKeyCaseFormat(String)` | `FormatOptions(KeyCase)` / `KeyCase.fromName(String)` |
| `KeyCaseFormat.SNAKE_CASE` / `CAMEL_CASE` / `DEFAULT` | `KeyCase.SNAKE` / `CAMEL` / `DEFAULT` |
| `FileOutputFormat.JSON` / `CSV` | `OutputFormat.JSONL` / `CSV` (`OutputFormat.fromName("json")` still works) |
| `InputParams.logValues()` | `toString()` on the records (passwords and secret-looking properties are redacted) |
| `RowHandler` | Removed; row handling is internal to `Pourover` |
| `SQLClient.queryAsList` / `queryWithHandler` / `setQueryParams` | `SQLClient` now only builds a `DataSource`; query through `Pourover` |
| `ClientFactory.makeSQLClient(String, SQLParams)` | `ClientFactory.makeSQLClient(SqlEngine.byName(name), ConnectionParams)` |
| `CallbackRowWriter(Function)` | `CallbackRowWriter(Consumer)` |
| _(none)_ | `RowFormatter.jsonl` / `csvHeader` / `csvRow` render single rows to strings |

#### Behavior changes

- **Errors are thrown.** JDBC failures throw an unchecked `QueryException` (with `getSqlState()`) instead of being logged and swallowed. Before, `queryAsList` returned `null` and `queryWithCallback` returned `-1`. The message never contains the connection password. File errors throw `UncheckedIOException`.
- **Rows are plain `LinkedHashMap`s.** Rows are keyed by column label in column order. The case-insensitive key lookup from the old commons-dbutils maps is gone, so `row.get("ID")` no longer finds a column labelled `id`.
- **Option validation.** `QueryOptions` rejects zero or negative values for fetch size, max rows, timeout and log frequency. The old `QueryParams(fetchSize, maxRows, timeout)` constructor had an inverted null check that left the log frequency null; `QueryOptions.of` now applies the default (100,000).
- **Key case.** `FormatOptions` key case applies to `writeTo` output. `list`, `forEach` and `stream` return raw column labels; call `FormatOptions.formatKeys(row)` to convert them. If two columns convert to the same key (for example `userId` and `user_id` under `SNAKE`), an `IllegalStateException` naming both columns is thrown instead of one value silently overwriting the other.
- **Snake and camel case keep acronyms together.** A run of capitals is one word, and words split on `_`, `-` and spaces: `USER_ID` becomes `user_id` / `userId`, `ORDERID` becomes `orderid`, `HTTPStatus` becomes `http_status` / `httpStatus`, and `Order ID` becomes `order_id` / `orderId`. Before, every capital started a new word (`USER_ID` became `u_s_e_r_i_d`), which broke upper-case labels from Snowflake, H2 and other engines that upper-case unquoted identifiers.
- **JSON output skips HTML escaping.** `=`, `<`, `>`, `&` and `'` are written as-is; they are no longer turned into Gson's HTML-safe Unicode escapes (a backslash, `u` and four hex digits). The output is still valid JSON.
- **Dates and times are ISO-8601 strings.** In JSONL and CSV, `java.sql.Timestamp`, `Date` and `Time` and the `java.time` types are written as ISO-8601 strings, for example `2024-01-02T03:04:05`, `2024-01-02` or `2024-01-02T03:04:05+02:00`. Before, Gson failed on `java.time` values under JDK 21, and CSV used `Timestamp.toString()`.
- **Arrays, LOBs, binary and driver types are materialized.** Rows from `list`, `forEach`, `stream` and `writeTo` are read fully while the row is current, so they stay usable after the connection closes: SQL `ARRAY` becomes a `List`, `CLOB` and `SQLXML` become a `String`, and `BLOB` and `byte[]` become a Base64 string (RFC 4648, no line breaks). Any other value whose class is not from `java.*` or `javax.*` (for example a PostgreSQL `PGobject` for `json`/`jsonb`) becomes its `toString()`. Before, `list()` returned driver locators that failed once the connection closed, JSONL failed with a Gson `JsonIOException` on these types under JDK 21, and CSV wrote `byte[]` as `[B@...`.
- **Rendering never reflects into unknown types.** `RowFormatter` writes `byte[]` as Base64 in JSONL and CSV, lists, maps and arrays element by element (as JSON, also inside a CSV field, for example `"[1,2]"`), and any type that is not a JDK string, number, boolean, character or date/time with `toString()`.
- **CSV quoting.** Quoting is decided from a value's rendered text whatever its type, so JSON/JSONB objects, arrays and other non-string values containing the delimiter, a quote or a newline are quoted too. Before, only `String` values were quoted. A value is quoted when it contains `\n` or `\r`; before, it was quoted only when it contained the platform line separator. Header names are quoted by the same rules as values.
- **Empty CSV results have a header.** `writeTo` with `OutputFormat.CSV` writes the header row (key case applied) from the result's column labels even when there are no rows. An empty JSONL result is still an empty file.
- **File output is UTF-8 everywhere,** including `FileRowWriter.open(OutputStream)`.
- **Queries run with autocommit off.** When the connection arrives with autocommit on, `Pourover` turns it off for the query, rolls back, and turns it back on before the connection is closed or returned to a pool. This makes `fetchSize` stream with a cursor on PostgreSQL, which ignores it in autocommit mode. A connection already in a transaction (autocommit off) belongs to the caller and is left untouched: no rollback and no autocommit change. If the driver refuses to turn autocommit off (Informix databases without logging), the query runs in autocommit mode.
- **Streams release connections.** `RowStream` closes its connection, statement and result set on `close()`, when its `Stream` is closed, or once the last row is read. `Pourover.of(DataSource)` never closes the caller's DataSource.
- **Engine properties.** The PostgreSQL and SQL Server options formerly on `PostgreSQLSQLParams` and `SQLServerSQLParams` are read from `ConnectionParams.properties`, with the same keys and defaults: PostgreSQL connections are read-only unless `readOnly=false`, and SQL Server `encrypt` and `trustServerCertificate` default to `false`. Snowflake, Redshift and Athena now pass all properties to the driver, so Snowflake `warehouse` and `role` work. MySQL and Informix still ignore them.
- **Athena driver is user-supplied.** The official AWS driver is not on Maven Central, so it is no longer bundled; put it on the runtime classpath (see `docs/drivers.md`).
- **Unsupported engines throw.** `ClientFactory` throws `IllegalArgumentException` for `ORACLE` and `BIGQUERY` instead of returning `null`.

### CLI

- Rebuilt on picocli; the flags are unchanged, and the single-dash 0.x forms (`-dry`, `-format`, `-fetchsize`, `-timeout`, `-maxrows`, `-custom`) still work. `--help` and `--version` are new (standard picocli options).
- Exit codes: `0` success, `1` query or output failure, or any other runtime error while running the query (for example a key-case collision), `2` usage error (bad or unknown option values, `--timeout`/`--fetchsize`/`--maxrows` of zero or less, an unknown `--type`, `--format` or `--case`, or no password available). Errors are one line on stderr, never the password or a stack trace.
- With no `EXTRACT_DB_PASSWORD` and no console the CLI now exits `2` with a clear message; before, it failed with a `NullPointerException`.
- `--maxrows` now limits rows; before, the option was read under the wrong key and ignored.
- Logging: the CLI no longer forces slf4j-simple to TRACE; it uses the slf4j-simple defaults (override with `-Dorg.slf4j.simpleLogger.defaultLogLevel=...`).
- `--dry` prints the connection parameters (password redacted), query options and format options to stdout, then exits `0`. It never prompts for a password: it shows `password=[REDACTED]` when `EXTRACT_DB_PASSWORD` is set and `password=null` otherwise.

### Build

- Java 21 toolchain and H2-backed tests (see Changed for the rest). The CLI is built on picocli instead of commons-cli. `slf4j-simple` is now only on the CLI runtime classpath.

### Known limitations

- If `writeTo` fails part way (a query error or an I/O error), the partly written output file may remain on disk; the library does not delete it.
- MySQL streaming with `fetchSize` needs the driver's `useCursorFetch` option, which is not enabled; without it Connector/J reads the whole result into memory.
- The driver reads a single very large value (a big text or binary column) into memory; streaming is per row, not per value.
- Athena, Snowflake, Redshift, Informix and SQL Server are not tested end to end; only PostgreSQL and MySQL are.
