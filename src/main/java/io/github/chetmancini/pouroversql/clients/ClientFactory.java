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
     */
    public static SQLClient makeSQLClient(SqlEngine engine, ConnectionParams params) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(params, "params");
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
}
