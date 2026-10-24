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

import com.mysql.cj.jdbc.MysqlDataSource;
import io.github.chetmancini.pouroversql.ConnectionParams;

import javax.sql.DataSource;

/**
 * <h1>MySQL Client</h1>
 * Provide facilities for querying MySQL or compatible databases
 * such as AWS Aurora or MariaDB. Custom properties are ignored.
 *
 * @author  Chet Mancini
 * @since   2019-03-31
 */
public class MySQLClient extends AbstractSQLClient {

    private static final int DEFAULT_PORT = 3306;
    private static final String DEFAULT_HOST = "localhost";

    /**
     * Constructor
     * @param params the parameters to use for the connection.
     */
    public MySQLClient(ConnectionParams params) {
        super(params);
    }

    @Override
    protected DataSource initDataSource() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUser(this.params.user());
        ds.setServerName(this.host(DEFAULT_HOST));
        ds.setPort(this.port(DEFAULT_PORT));
        ds.setPassword(this.params.password());
        ds.setDatabaseName(this.params.database());
        return ds;
    }

    @Override
    protected String getDriverName() {
        return "com.mysql.cj.jdbc.Driver";
    }
}
