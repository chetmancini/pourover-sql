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

import io.github.chetmancini.pouroversql.clients.ClientFactory;
import io.github.chetmancini.pouroversql.clients.SqlEngine;
import io.github.chetmancini.pouroversql.util.ResultSets;
import io.github.chetmancini.pouroversql.writers.CSVRowWriter;
import io.github.chetmancini.pouroversql.writers.CallbackRowWriter;
import io.github.chetmancini.pouroversql.writers.FileRowWriter;
import io.github.chetmancini.pouroversql.writers.JsonLRowWriter;
import io.github.chetmancini.pouroversql.writers.RowWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs SQL and returns rows as a list, a callback per row, a stream, or a JSONL/CSV file.
 *
 * <pre>{@code
 * Pourover db = Pourover.connect(SqlEngine.POSTGRESQL,
 *         new ConnectionParams("localhost", 5432, "app", password, "main"));
 * List<Map<String, Object>> rows = db.list("SELECT id, name FROM users");
 * db.writeTo("SELECT * FROM events", Path.of("events.jsonl"), OutputFormat.JSONL);
 * }</pre>
 *
 * <p>Rows are {@link java.util.LinkedHashMap}s keyed by column label in column order; SQL NULL
 * is {@code null}. Every call opens its own connection and closes the connection, statement and
 * result set before returning (or, for {@link #stream}, when the {@link RowStream} is closed),
 * including when an exception is thrown. JDBC errors are thrown as {@link QueryException}.
 * Instances are immutable and safe to share between threads if the DataSource is.
 */
public final class Pourover {

    private static final Logger logger = LoggerFactory.getLogger(Pourover.class);

    private final DataSource dataSource;
    private final String secret;
    private final String description;

    private Pourover(DataSource dataSource, String secret, String description) {
        this.dataSource = dataSource;
        this.secret = secret;
        this.description = description;
    }

    /**
     * Connects to one of the built-in engines. No connection is opened until a query runs.
     * @param engine the database engine
     * @param params connection settings; see {@link ConnectionParams} for engine properties
     * @return a Pourover for that database
     * @throws IllegalArgumentException if the engine has no built-in client (ORACLE, BIGQUERY)
     */
    public static Pourover connect(SqlEngine engine, ConnectionParams params) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(params, "params");
        DataSource ds = ClientFactory.makeSQLClient(engine, params).dataSource();
        return new Pourover(ds, params.password(), "Pourover[engine=" + engine + ", " + params + "]");
    }

    /**
     * Uses an existing DataSource, such as a connection pool. Pourover borrows a connection per
     * call and closes it (returning it to the pool); it never closes the DataSource itself.
     * @param ds the DataSource to use
     * @return a Pourover backed by that DataSource
     */
    public static Pourover of(DataSource ds) {
        Objects.requireNonNull(ds, "ds");
        return new Pourover(ds, null, "Pourover[dataSource=" + ds.getClass().getName() + "]");
    }

    /**
     * Runs a query with default options and collects every row.
     * @param sql the query
     * @return the rows, in result order
     * @throws QueryException on JDBC errors
     */
    public List<Map<String, Object>> list(String sql) {
        return list(sql, QueryOptions.defaults());
    }

    /**
     * Runs a query and collects every row.
     * @param sql the query
     * @param o execution options
     * @return the rows, in result order
     * @throws QueryException on JDBC errors
     */
    public List<Map<String, Object>> list(String sql, QueryOptions o) {
        List<Map<String, Object>> rows = new ArrayList<>();
        run(sql, o, FormatOptions.defaults(), new CallbackRowWriter(rows::add));
        return rows;
    }

    /**
     * Runs a query with default options and passes each row to a consumer.
     * @param sql the query
     * @param rowConsumer receives each row; exceptions it throws propagate after cleanup
     * @return the number of rows
     * @throws QueryException on JDBC errors
     */
    public long forEach(String sql, Consumer<Map<String, Object>> rowConsumer) {
        return forEach(sql, rowConsumer, QueryOptions.defaults());
    }

    /**
     * Runs a query and passes each row to a consumer as it is read.
     * @param sql the query
     * @param rowConsumer receives each row; exceptions it throws propagate after cleanup
     * @param o execution options
     * @return the number of rows
     * @throws QueryException on JDBC errors
     */
    public long forEach(String sql, Consumer<Map<String, Object>> rowConsumer, QueryOptions o) {
        Objects.requireNonNull(rowConsumer, "rowConsumer");
        return run(sql, o, FormatOptions.defaults(), new CallbackRowWriter(rowConsumer));
    }

    /**
     * Runs a query with default options and returns its rows lazily.
     * @param sql the query
     * @return an open RowStream the caller must close
     * @throws QueryException on JDBC errors
     */
    public RowStream stream(String sql) {
        return stream(sql, QueryOptions.defaults());
    }

    /**
     * Runs a query and returns its rows lazily. The connection stays open until the RowStream
     * (or the Stream from {@link RowStream#rows()}) is closed or fully read.
     * @param sql the query
     * @param o execution options
     * @return an open RowStream the caller must close
     * @throws QueryException on JDBC errors
     */
    public RowStream stream(String sql, QueryOptions o) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(o, "o");
        Connection connection = null;
        PreparedStatement statement = null;
        boolean originalAutoCommit = true;
        boolean autoCommitChanged = false;
        try {
            connection = dataSource.getConnection();
            originalAutoCommit = beginReadOnlyTransaction(connection);
            autoCommitChanged = true;
            statement = prepare(connection, sql, o);
            ResultSet resultSet = statement.executeQuery();
            return new RowStream(connection, statement, resultSet, secret, o.logFrequency(), originalAutoCommit);
        } catch (SQLException e) {
            closeAfterFailure(e, statement);
            if (autoCommitChanged) {
                endTransaction(connection, originalAutoCommit);
            }
            closeAfterFailure(e, connection);
            throw QueryException.wrap(e, secret);
        } catch (RuntimeException | Error e) {
            closeAfterFailure(e, statement);
            if (autoCommitChanged) {
                endTransaction(connection, originalAutoCommit);
            }
            closeAfterFailure(e, connection);
            throw e;
        }
    }

    /**
     * Runs a query with default options and writes the rows to a file with keys unchanged.
     * @param sql the query
     * @param file the output file, created or truncated
     * @param f the output format
     * @return {@code file}
     * @throws QueryException on JDBC errors
     * @throws java.io.UncheckedIOException if the file cannot be written
     */
    public Path writeTo(String sql, Path file, OutputFormat f) {
        return writeTo(sql, file, f, FormatOptions.defaults(), QueryOptions.defaults());
    }

    /**
     * Runs a query with default options and writes the rows to a file.
     * @param sql the query
     * @param file the output file, created or truncated
     * @param f the output format
     * @param fo formatting options such as key case
     * @return {@code file}
     * @throws QueryException on JDBC errors
     * @throws java.io.UncheckedIOException if the file cannot be written
     */
    public Path writeTo(String sql, Path file, OutputFormat f, FormatOptions fo) {
        return writeTo(sql, file, f, fo, QueryOptions.defaults());
    }

    /**
     * Runs a query and writes the rows to a file as UTF-8 JSONL or CSV, one line per row, using
     * the conventions documented on {@link RowFormatter}. The file is opened before the query runs;
     * if the query fails, the partial file is left in place.
     * @param sql the query
     * @param file the output file, created or truncated
     * @param f the output format
     * @param fo formatting options such as key case
     * @param o execution options
     * @return {@code file}
     * @throws QueryException on JDBC errors
     * @throws java.io.UncheckedIOException if the file cannot be written
     */
    public Path writeTo(String sql, Path file, OutputFormat f, FormatOptions fo, QueryOptions o) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(f, "f");
        Objects.requireNonNull(fo, "fo");
        Objects.requireNonNull(o, "o");
        try (FileRowWriter writer = writerFor(f)) {
            writer.open(file);
            run(sql, o, fo, writer);
        }
        return file;
    }

    private static FileRowWriter writerFor(OutputFormat f) {
        return switch (f) {
            case JSONL -> new JsonLRowWriter();
            case CSV -> new CSVRowWriter();
        };
    }

    private long run(String sql, QueryOptions o, FormatOptions fo, RowWriter writer) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(o, "o");
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = beginReadOnlyTransaction(connection);
            try (PreparedStatement statement = prepare(connection, sql, o);
                 ResultSet resultSet = statement.executeQuery()) {
                writer.begin(columnKeys(resultSet, fo));
                long count = 0;
                while (resultSet.next()) {
                    writer.writeRow(fo.formatKeys(ResultSets.toMap(resultSet)));
                    count++;
                    if (count % o.logFrequency() == 0) {
                        logger.info("Handled {} rows...", count);
                    }
                }
                return count;
            } finally {
                // After the statement and result set are closed, before the connection is returned.
                endTransaction(connection, originalAutoCommit);
            }
        } catch (SQLException e) {
            throw QueryException.wrap(e, secret);
        }
    }

    /**
     * Turns autocommit off so drivers honor fetchSize with a cursor (PostgreSQL ignores it in
     * autocommit mode and loads the whole result into memory).
     * @return the autocommit value to restore
     */
    private static boolean beginReadOnlyTransaction(Connection connection) throws SQLException {
        boolean original = connection.getAutoCommit();
        connection.setAutoCommit(false);
        return original;
    }

    /**
     * Rolls back the read-only transaction and restores autocommit so a pooled connection is
     * returned as it was lent. Failures are logged, not thrown: the query outcome is already decided.
     */
    static void endTransaction(Connection connection, boolean originalAutoCommit) {
        try {
            connection.rollback();
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException e) {
            logger.debug("Could not roll back and restore autocommit", e);
        }
    }

    /** Output keys for the result's columns, with key case (and its collision check) applied. */
    private static List<String> columnKeys(ResultSet resultSet, FormatOptions fo) throws SQLException {
        Map<String, Object> labels = new LinkedHashMap<>();
        for (String label : ResultSets.columnLabels(resultSet)) {
            labels.put(label, null);
        }
        return new ArrayList<>(fo.formatKeys(labels).keySet());
    }

    private static PreparedStatement prepare(Connection connection, String sql, QueryOptions o) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setFetchSize(o.fetchSize());
            if (o.timeoutSeconds() != null) {
                statement.setQueryTimeout(o.timeoutSeconds());
            }
            if (o.maxRows() != null) {
                statement.setMaxRows(o.maxRows());
            }
            return statement;
        } catch (SQLException | RuntimeException e) {
            closeAfterFailure(e, statement);
            throw e;
        }
    }

    private static void closeAfterFailure(Throwable failure, AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception e) {
                failure.addSuppressed(e);
            }
        }
    }

    /**
     * Describes this instance without credentials.
     * @return a log-safe description
     */
    @Override
    public String toString() {
        return description;
    }
}
