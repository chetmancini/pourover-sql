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

import javax.sql.DataSource;
import java.util.Properties;

/**
 * <h1>PostgreSQL Client</h1>
 * Provide facilities for querying Snowflake.
 *
 * This client is in DEVELOPMENT and has not yet been tested in production.
 *
 * @author  Chet Mancini
 * @since   2019-03-31
 */
public class SnowflakeClient extends AbstractSQLClient {

    /**
     * Constructor
     * @param params SQLParams for the connection
     */
    SnowflakeClient(SQLParams params) {
        super(params);
    }

    /**
     * Constructor
     * @param params SQLParams for the connection
     * @param queryParams QueryParams to default to future queries.
     */
    SnowflakeClient(SQLParams params, QueryParams queryParams) {
        super(params, queryParams);
    }

    @Override
    protected DataSource initDataSource() {
        Properties props = new Properties();
        if (this.params.getUser() != null) {
            props.setProperty("user", this.params.getUser());
        }
        if (this.params.getPassword() != null) {
            props.setProperty("password", this.params.getPassword());
        }
        if (this.params.getDatabase() != null) {
            props.setProperty("db", this.params.getDatabase());
        }
        String host = this.params.getHost();
        String port = this.params.getPort() != null ? ":" + this.params.getPort() : "";
        return new DriverManagerDataSource("jdbc:snowflake://" + host + port, props);
    }

    @Override
    protected String getDriverName() {
        return "net.snowflake.client.jdbc.SnowflakeDriver";
    }
}
