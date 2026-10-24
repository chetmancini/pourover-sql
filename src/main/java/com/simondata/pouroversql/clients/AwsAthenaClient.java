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
package com.simondata.pouroversql.clients;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * <h1>Athena Client</h1>
 * Provide facilities for querying AWS Athena
 *
 * The official AWS Athena JDBC driver is not published to Maven Central, so it is
 * user-supplied on the runtime classpath. The connection is made through
 * {@link DriverManager} with the JDBC URL given as the host.
 *
 * This client is in DEVELOPMENT and has not yet been tested in production.
 *
 * @author  Chet Mancini
 * @version 1.0
 * @since   2019-03-31
 */
public class AwsAthenaClient extends AbstractSQLClient {

    /**
     * Constructor
     * @param params SQLParams the params for the connection.
     */
    public AwsAthenaClient(SQLParams params) {
        super(params);
    }

    private String getUrl() {
        return "jdbc:awsathena://athena.{REGION}.amazonaws.com:443";
    }

    @Override
    protected DataSource initDataSource() {
        return new DriverManagerDataSource(
                this.params.getHost(), this.params.getUser(), this.params.getPassword());
    }

    @Override
    protected String getDriverName() {
        return "com.amazon.athena.jdbc.AthenaDriver";
    }

    /** Minimal DataSource that connects via DriverManager, avoiding any vendor class dependency. */
    private static final class DriverManagerDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;

        DriverManagerDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return getConnection(user, password);
        }

        @Override
        public Connection getConnection(String username, String pass) throws SQLException {
            return DriverManager.getConnection(url, username, pass);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("Not a wrapper for " + iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
