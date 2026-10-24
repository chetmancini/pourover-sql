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
package io.github.chetmancini.pouroversql;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Test-only DataSource over a private in-memory H2 database. It records every
 * connection it hands out so tests can assert that Pourover released them.
 */
final class H2DataSource implements DataSource {

    /** Throwaway credential generated per run, used only to prove it never leaks into messages. */
    static final String PASSWORD = "pw-" + UUID.randomUUID();
    static final String USER = "sa";

    private final String url;
    private final List<Connection> handedOut = new CopyOnWriteArrayList<>();

    H2DataSource() {
        this("");
    }

    /**
     * @param urlOptions extra H2 URL settings, each starting with ';' (e.g. ";LAZY_QUERY_EXECUTION=1")
     */
    H2DataSource(String urlOptions) {
        try {
            // Explicit load: DriverManager service discovery is not reliable here.
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        this.url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1" + urlOptions;
    }

    /** Runs setup statements on a connection that is not tracked. */
    void exec(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD);
             Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    /** All connections handed out so far. */
    List<Connection> connections() {
        return handedOut;
    }

    /** True when at least one connection was handed out and every one is closed. */
    boolean allConnectionsClosed() throws SQLException {
        if (handedOut.isEmpty()) {
            return false;
        }
        for (Connection c : handedOut) {
            if (!c.isClosed()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection c = DriverManager.getConnection(url, USER, PASSWORD);
        handedOut.add(c);
        return c;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection c = DriverManager.getConnection(url, username, password);
        handedOut.add(c);
        return c;
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
