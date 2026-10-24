# Engines

How each engine is reached, as implemented in `io.github.chetmancini.pouroversql.clients`. Pass the engine to `Pourover.connect(SqlEngine, ConnectionParams)` or to the CLI with `--type`. Names are case-insensitive; spaces count as underscores.

| Engine | `--type` names | Default port | Connection built from | E2E tested |
|---|---|---|---|---|
| PostgreSQL | `postgresql`, `postgres` | 5432 | `PGSimpleDataSource` with host, port, database, user, password | Yes (`postgres:16-alpine`) |
| MySQL / MariaDB | `mysql`, `mariadb`, `maria` | 3306 | `MysqlDataSource` with host, port, database, user, password | Yes (`mysql:8.4`) |
| SQL Server / Azure SQL | `sqlserver`, `azure`, `mssql`, `ms_sql`, `sql_server`, `microsoft_sql_server` | 1433 | `SQLServerDataSource` with host, port, database, user, password | No |
| Snowflake | `snowflake` | none (the URL has no port unless you give one) | `jdbc:snowflake://<host>[:<port>]`, database as the `db` property | No |
| Amazon Redshift | `redshift` | 5439 | `jdbc:redshift://<host>:<port>/<database>` | No |
| Informix | `informix`, `ibm_informix` | 1526 | `IfxDataSource` with host, port, database, user, password | No |
| Amazon Athena | `athena`, `awsathena`, `aws_athena` | none | The host is the full JDBC URL, for example `jdbc:athena://region=us-east-1;Workgroup=primary` | No |

The host defaults to `localhost` for PostgreSQL, MySQL, SQL Server and Informix. Snowflake and Redshift need an explicit host.

`ORACLE` and `BIGQUERY` are recognised names with no client; asking for them throws `IllegalArgumentException`.

## Engine properties

`ConnectionParams.properties` (CLI: `--custom key=value`) carries engine options:

- PostgreSQL and SQL Server read a fixed list of keys (listed in the `ConnectionParams` Javadoc). PostgreSQL connections are read-only unless `readOnly=false`; SQL Server `encrypt` and `trustServerCertificate` default to `false`.
- Snowflake, Redshift and Athena pass every property to the driver.
- MySQL and Informix ignore properties.

## What "E2E tested" means

`./gradlew e2eTest` starts PostgreSQL and MySQL in Testcontainers and checks listing, streaming, file output and fetch size against them. It needs Docker and is skipped without it. The other engines are covered by unit tests of their connection setup only; they have not been run against a live server. Drivers and how to supply them are in [drivers.md](drivers.md).
