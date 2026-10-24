/*
Copyright 2019-present, Simon Data, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at:
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package io.github.chetmancini.pouroversql.clients;

import io.github.chetmancini.pouroversql.ConnectionParams;

import java.util.Objects;

/**
 * ClientFactory
 *
 * Factory class to build the correct type of SQLClient based on input.
 */
public final class ClientFactory {

    private ClientFactory() {
    }

    /**
     * Factory method to build a client by engine type.
     * @param engine the type of client to build
     * @param params connection settings
     * @return SQLClient for the engine
     * @throws IllegalArgumentException for engines without a built-in client (ORACLE, BIGQUERY)
     * @throws IllegalStateException if the engine's JDBC driver is not on the classpath
     */
    public static SQLClient makeSQLClient(SqlEngine engine, ConnectionParams params) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(params, "params");
        requireDriver(engine);
        return switch (engine) {
            case ATHENA -> new AwsAthenaClient(params);
            case SQLSERVER -> new SQLServerClient(params);
            case MYSQL -> new MySQLClient(params);
            case POSTGRESQL -> new PostgreSQLClient(params);
            case REDSHIFT -> new RedshiftClient(params);
            case INFORMIX -> new InformixSQLClient(params);
            case SNOWFLAKE -> new SnowflakeClient(params);
            case ORACLE -> throw new IllegalArgumentException(
                    "Oracle is not supported: its driver requires a login to download. Use Pourover.of(DataSource).");
            case BIGQUERY -> throw new IllegalArgumentException(
                    "BigQuery is not supported yet. Use Pourover.of(DataSource).");
        };
    }

    private static void requireDriver(SqlEngine engine) {
        switch (engine) {
            case ATHENA -> requireDriver("com.amazon.athena.jdbc.AthenaDriver", "Athena",
                    "the official AWS Athena JDBC driver (downloaded from AWS, not on Maven Central)");
            case SQLSERVER -> requireDriver("com.microsoft.sqlserver.jdbc.SQLServerDriver", "SQL Server",
                    "com.microsoft.sqlserver:mssql-jdbc");
            case MYSQL -> requireDriver("com.mysql.cj.jdbc.Driver", "MySQL", "com.mysql:mysql-connector-j");
            case POSTGRESQL -> requireDriver("org.postgresql.Driver", "PostgreSQL", "org.postgresql:postgresql");
            case REDSHIFT -> requireDriver("com.amazon.redshift.Driver", "Redshift",
                    "com.amazon.redshift:redshift-jdbc42");
            case INFORMIX -> requireDriver("com.informix.jdbc.IfxDriver", "Informix", "com.ibm.informix:jdbc");
            case SNOWFLAKE -> requireDriver("net.snowflake.client.jdbc.SnowflakeDriver", "Snowflake",
                    "net.snowflake:snowflake-jdbc");
            case ORACLE, BIGQUERY -> {
                // No built-in client; makeSQLClient reports that.
            }
        }
    }

    static void requireDriver(String driverClass, String engineName, String artifact) {
        try {
            Class.forName(driverClass, false, ClientFactory.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException(engineName + " JDBC driver not found on the classpath (" + driverClass
                    + "). Add " + artifact + " to your dependencies; see docs/drivers.md.", e);
        }
    }
}
