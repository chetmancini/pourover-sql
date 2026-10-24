/*
 * Copyright 2026 Chet Mancini
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.chetmancini.pouroversql.clients;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Minimal DataSource that connects via DriverManager with a JDBC URL and properties.
 * Avoids any compile-time dependency on a vendor driver class.
 * The password is never included in {@link #toString()}.
 */
final class DriverManagerDataSource implements DataSource {
    private final String url;
    private final Properties properties;

    DriverManagerDataSource(String url, Properties properties) {
        this.url = url;
        this.properties = properties;
    }

    /** Builds a data source with optional user and password properties. */
    static DriverManagerDataSource of(String url, String user, String password) {
        Properties props = new Properties();
        if (user != null) {
            props.setProperty("user", user);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        return new DriverManagerDataSource(url, props);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, properties);
    }

    @Override
    public Connection getConnection(String username, String pass) throws SQLException {
        Properties props = new Properties();
        props.putAll(properties);
        if (username != null) {
            props.setProperty("user", username);
        }
        if (pass != null) {
            props.setProperty("password", pass);
        }
        return DriverManager.getConnection(url, props);
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

    @Override
    public String toString() {
        return "DriverManagerDataSource{url=" + url + ", password=[REDACTED]}";
    }
}
