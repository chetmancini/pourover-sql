package com.simondata.pouroversql.clients;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * Test-only client backed by an in-memory H2 database. Extends the real
 * AbstractSQLClient so the production query and error-handling paths are exercised.
 */
public class H2TestClient extends AbstractSQLClient {

    private final String url;

    public H2TestClient(String url) {
        super(null);
        this.url = url;
    }

    @Override
    protected DataSource initDataSource() {
        return new DriverManagerDataSource(url);
    }

    @Override
    protected String getDriverName() {
        return "org.h2.Driver";
    }

    private static final class DriverManagerDataSource implements DataSource {
        private final String url;

        DriverManagerDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
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
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
