# Pour-Over SQL

[![CI](https://github.com/chetmancini/pourover-sql/actions/workflows/ci.yml/badge.svg)](https://github.com/chetmancini/pourover-sql/actions/workflows/ci.yml)
<!-- The CI badge starts working after the first push of the workflow to GitHub. -->

Pour-Over SQL runs a SQL query against a JDBC database and gives you the rows: as a list, as a stream you read one row at a time, or as a JSONL or CSV file. It is a Java library and a command-line tool. You write the SQL, it handles the connection, the fetch size, the cleanup and the file format. It has no mapping layer and no query builder, so it suits dynamic queries, exports and one-off extracts.

## Quickstart

### Try it in the browser (about a minute)

You need Java 21. From a clone of this repository:

```
./gradlew :demo:run
```

Then open <http://localhost:8080>. Type a `SELECT` against the sample coffee-orders tables, and the page shows the JSONL, the CSV and the Java call for it. It runs on an in-memory H2 database, so there is no Docker and no setup. Set `PORT` to use another port. More in [demo/README.md](demo/README.md).

### Use it as a library

Connect to a database and list rows:

```java
Pourover db = Pourover.connect(SqlEngine.POSTGRESQL,
        new ConnectionParams("localhost", 5432, "app", password, "main"));
List<Map<String, Object>> users = db.list("SELECT * FROM users");
```

Each row is a `LinkedHashMap` keyed by column label, in column order. A failed query throws an unchecked `QueryException`. Runnable versions of the snippets in this README are in [examples/](examples/README.md).

### Use it from the command line

Build the fat jar once:

```
./gradlew shadowJar
```

Then run a query and write the result:

```
export EXTRACT_DB_PASSWORD='<password>'
echo 'SELECT * FROM users' > users.sql
java -jar build/libs/pourover-sql-<version>-all.jar \
    --type postgres --user app --database main \
    --sql users.sql --file users.jsonl --case snake
```

Add `--dry` to print the parameters (with the password redacted) without connecting.

## Install

Nothing is published yet. For now, build from source with the Gradle wrapper (Java 21):

```
git clone https://github.com/chetmancini/pourover-sql.git
cd pourover-sql
./gradlew build
```

That runs the unit tests, compiles the examples and writes the library jar to `build/libs/`. `./gradlew shadowJar` writes the runnable fat jar next to it (`pourover-sql-<version>-all.jar`).

The library will be available as `io.github.chetmancini:pourover-sql` with the first release. That is not out yet, so do not add it to a build file today.

## Using the library

`Pourover.connect(engine, params)` builds a connection for one of the supported engines. If you already have a `DataSource` (a pool, or a test database), wrap it with `Pourover.of(dataSource)`; Pour-Over never closes a data source you gave it.

`list` returns every row in memory:

```java
Pourover db = Pourover.of(dataSource);
List<Map<String, Object>> rows = db.list("SELECT * FROM beans ORDER BY \"Score\" DESC");
```

`stream` reads rows one at a time. Close the stream (try-with-resources) to release the connection:

```java
try (RowStream stream = db.stream("SELECT \"N\" FROM numbers")) {
    long evens = stream.rows()
            .filter(row -> ((Number) row.get("N")).longValue() % 2 == 0)
            .count();
    System.out.println("Even numbers between 1 and 100000: " + evens);
}
```

`writeTo` writes JSONL or CSV, with optional key-case conversion:

```java
Path jsonl = db.writeTo("SELECT * FROM cups", dir.resolve("cups.jsonl"), OutputFormat.JSONL, snake);
Path csv = db.writeTo("SELECT * FROM cups", dir.resolve("cups.csv"), OutputFormat.CSV, snake);
```

`forEach` hands each row to a `Consumer` and returns the row count. `QueryOptions` sets fetch size, max rows and timeout; `FormatOptions` sets the key case (`DEFAULT`, `SNAKE`, `CAMEL`).

Queries run with autocommit off and are rolled back, so a query with side effects does not leave its writes behind. Pour-Over is for reading.

Generate the Javadoc with `./gradlew javadoc`; it lands in `build/docs/javadoc`.

## Supported engines

| Engine | `--type` values | JDBC driver | End-to-end tested |
|---|---|---|---|
| PostgreSQL | `postgresql`, `postgres` | bundled | yes |
| MySQL / MariaDB | `mysql`, `mariadb` | bundled | yes |
| SQL Server / Azure SQL | `sqlserver`, `azure`, `mssql` | bundled | no |
| Snowflake | `snowflake` | bundled | no |
| Amazon Redshift | `redshift` | bundled | no |
| Informix | `informix` | bundled | no |
| Amazon Athena | `athena` | you supply it | no |

"End-to-end tested" means the test suite runs queries against a real server in Testcontainers (`./gradlew e2eTest`, needs Docker). The other engines are covered by unit tests only and have not been exercised against a live server. Default ports, URL shapes and notes per engine are in [docs/engines.md](docs/engines.md); driver details, including how to add the Athena driver, are in [docs/drivers.md](docs/drivers.md).

`ORACLE` and `BIGQUERY` exist as names but have no client; they throw `IllegalArgumentException`.

## Command-line reference

```
java -jar pourover-sql-<version>-all.jar [options]
```

| Flag | Default | Description |
|---|---|---|
| `-u`, `--user` | none (required) | Database user. |
| `-h`, `--host` | `localhost` | Database host. For Athena, the full JDBC URL. |
| `-p`, `--port` | the engine's default port | Database port. |
| `-d`, `--database` | none | Database name. |
| `-t`, `--type` | `SQLSERVER` | Engine, from the table above. |
| `-s`, `--sql` | stdin | Path of the file holding the SQL. Reads standard input when absent. |
| `-f`, `--file` | `query_result.json` | Output file. |
| `--format` | `json` | `json` (one JSON object per line) or `csv`. |
| `-c`, `--case` | `DEFAULT` | Key case: `DEFAULT` (column labels as returned), `snake` or `camel`. |
| `--fetchsize` | `10000` | JDBC fetch size. Must be greater than zero. |
| `--maxrows` | no limit | Maximum rows to write. Must be greater than zero. |
| `--timeout` | no timeout | Query timeout in seconds. Must be greater than zero. |
| `--custom` | none | Engine property as `key=value`; repeat for more. See `ConnectionParams` in the Javadoc for the keys. |
| `--dry` | off | Print connection, query and format options (password redacted), then exit `0`. |
| `--help` | | Show usage. |
| `--version` | | Print the version. |

Exit codes: `0` success, `1` the query or the output file failed (the message goes to stderr), `2` usage error (bad option value, unknown `--type`, `--format` or `--case`, or no password available).

The password comes from the `EXTRACT_DB_PASSWORD` environment variable, or a console prompt when that is unset. With neither, the command exits `2`. There is no password flag, on purpose: it would end up in shell history and process listings.

## Why Pour-Over SQL

Most Java database libraries are built around mapping rows to objects. That is the wrong tool when the query is not known until runtime, or when you just want the result in a file. Pour-Over keeps to the small job of running SQL and returning or writing rows, and it does the parts that are easy to get wrong: streaming with a cursor where the driver supports it, closing connections, quoting CSV, rendering dates as ISO-8601, and not putting the password in an error message.

## What it is not

It is not an ORM or a query builder. It does not generate SQL, map rows to classes, manage schemas or pool connections. It is not a general data-loading tool either: it reads and exports, and it rolls back whatever a query changes.

## Roadmap

Ideas, not promises:

- More output formats beyond JSONL and CSV.
- Splitting output across several files by row count or file size.
- More control over JSON serialization.

Known limitations of the current code are listed in the [changelog](CHANGELOG.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). To report a security problem, see [SECURITY.md](SECURITY.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).

## Attribution

Originally created at Simon Data, Inc., reboot by Chet Mancini. Existing source files keep their original Simon Data, Inc. copyright and license notices; new work is Copyright 2026 Chet Mancini. See [NOTICE](NOTICE). Offered without warranty.
