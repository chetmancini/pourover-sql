# Changelog

## 1.0.0 (unreleased)

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
- **JSON output skips HTML escaping.** `=`, `<`, `>`, `&` and `'` are written as-is; they are no longer turned into Gson's HTML-safe Unicode escapes (a backslash, `u` and four hex digits). The output is still valid JSON.
- **Dates and times are ISO-8601 strings.** In JSONL and CSV, `java.sql.Timestamp`, `Date` and `Time` and the `java.time` types are written as ISO-8601 strings, for example `2024-01-02T03:04:05`, `2024-01-02` or `2024-01-02T03:04:05+02:00`. Before, Gson failed on `java.time` values under JDK 21, and CSV used `Timestamp.toString()`.
- **CSV quoting.** Quoting is decided from a value's rendered text whatever its type, so JSON/JSONB objects, arrays and other non-string values containing the delimiter, a quote or a newline are quoted too. Before, only `String` values were quoted. A value is quoted when it contains `\n` or `\r`; before, it was quoted only when it contained the platform line separator. Header names are quoted by the same rules as values.
- **Empty CSV results have a header.** `writeTo` with `OutputFormat.CSV` writes the header row (key case applied) from the result's column labels even when there are no rows. An empty JSONL result is still an empty file.
- **File output is UTF-8 everywhere,** including `FileRowWriter.open(OutputStream)`.
- **Streams release connections.** `RowStream` closes its connection, statement and result set on `close()`, when its `Stream` is closed, or once the last row is read. `Pourover.of(DataSource)` never closes the caller's DataSource.
- **Engine properties.** The PostgreSQL and SQL Server options formerly on `PostgreSQLSQLParams` and `SQLServerSQLParams` are read from `ConnectionParams.properties`, with the same keys and defaults: PostgreSQL connections are read-only unless `readOnly=false`, and SQL Server `encrypt` and `trustServerCertificate` default to `false`. Snowflake, Redshift and Athena now pass all properties to the driver, so Snowflake `warehouse` and `role` work. MySQL and Informix still ignore them.
- **Unsupported engines throw.** `ClientFactory` throws `IllegalArgumentException` for `ORACLE` and `BIGQUERY` instead of returning `null`.

### Build

- Gradle Kotlin DSL, Java 21 toolchain, JUnit 5 and AssertJ, H2-backed tests.
- Package moved from `com.simondata.pouroversql` to `io.github.chetmancini.pouroversql`.
- Dropped Guava, commons-collections, commons-text and commons-dbutils. `slf4j-simple` is now only on the CLI runtime classpath.
