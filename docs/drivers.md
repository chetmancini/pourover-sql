# JDBC drivers

| Engine | Driver artifact / class | Bundled? | How to supply | E2E tested |
|--------|-------------------------|----------|---------------|------------|
| PostgreSQL | `org.postgresql:postgresql:42.7.14`, class `org.postgresql.Driver` | Bundled | Nothing to do | Yes (`postgres:16-alpine`) |
| MySQL | `com.mysql:mysql-connector-j:26.10.0`, class `com.mysql.cj.jdbc.Driver` | Bundled | Nothing to do | Yes (`mysql:8.4`) |
| SQL Server | `com.microsoft.sqlserver:mssql-jdbc:13.6.1.jre11`, class `com.microsoft.sqlserver.jdbc.Driver` | Bundled | Nothing to do | Not e2e tested |
| Snowflake | `net.snowflake:snowflake-jdbc:4.4.0`, class `net.snowflake.client.jdbc.SnowflakeDriver` | Bundled | Nothing to do | Not e2e tested |
| Redshift | `com.amazon.redshift:redshift-jdbc42:2.3.0` (Maven Central), class `com.amazon.redshift.Driver` | Bundled | Nothing to do | Not e2e tested |
| Informix | `com.ibm.informix:jdbc:15.0.1.4` (Maven Central), class `com.informix.jdbc.IfxDriver` | Bundled | Nothing to do | Not e2e tested |
| Athena | Official AWS Athena JDBC driver, class `com.amazon.athena.jdbc.AthenaDriver` (URL schemes `jdbc:athena`, deprecated `jdbc:awsathena`) | User-supplied | See below | Not e2e tested |

## Athena

The official AWS driver is distributed from AWS (S3 download), not Maven Central, so it is not bundled.
Download the driver jar from the AWS Athena JDBC documentation and put it on the runtime classpath:

- Library use: add the jar to your application's runtime classpath.
- CLI use: `java -cp pourover-sql-all.jar:/path/to/athena-jdbc.jar io.github.chetmancini.pouroversql.ExtractorRunner ...`

`AwsAthenaClient` takes the full JDBC URL as the host (for example
`jdbc:athena://region=us-east-1;Workgroup=primary`) and connects through `DriverManager`,
so no vendor class is needed at compile time.

## Notes

- PostgreSQL and MySQL are tested end to end against real databases in Testcontainers (`./gradlew e2eTest`, needs Docker; skipped when Docker is unavailable, and not part of `build`). The other engines are not e2e tested.
- Redshift and Informix resolve from Maven Central at current versions, so they are bundled.
- All bundled drivers appear with `runtime` scope in the published POM. The only compile-scope dependency is `slf4j-api`.
- `slf4j-simple` is a CLI-only runtime binding (included in the fat jar and `./gradlew run`), never in the library POM.
