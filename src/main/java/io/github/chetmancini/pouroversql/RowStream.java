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

import io.github.chetmancini.pouroversql.util.ResultSets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A lazily read query result that holds an open connection, statement and result set until closed.
 * Use it in a try-with-resources block:
 *
 * <pre>{@code
 * try (RowStream rows = pourover.stream("SELECT * FROM events")) {
 *     rows.rows().forEach(row -> ...);
 * }
 * }</pre>
 *
 * <p>Closing the {@link Stream} returned by {@link #rows()} also closes this RowStream, and the
 * resources are released as soon as the last row has been read or a row fails to read.
 * {@link #close()} is idempotent.
 * Read errors surface as {@link QueryException}. Instances are not meant to be shared between threads.
 */
public final class RowStream implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RowStream.class);

    private final Connection connection;
    private final Statement statement;
    private final ResultSet resultSet;
    private final String secret;
    private final int logFrequency;
    private final boolean originalAutoCommit;

    private boolean rowsCalled;
    private boolean exhausted;
    private boolean closed;
    private long count;

    RowStream(Connection connection, Statement statement, ResultSet resultSet, String secret, int logFrequency,
              boolean originalAutoCommit) {
        this.connection = connection;
        this.statement = statement;
        this.resultSet = resultSet;
        this.secret = secret;
        this.logFrequency = logFrequency;
        this.originalAutoCommit = originalAutoCommit;
    }

    /**
     * Returns the rows as a sequential, ordered stream of maps keyed by column label in column order.
     * May be called only once.
     * @return the row stream
     * @throws IllegalStateException if called twice or after {@link #close()}
     */
    public Stream<Map<String, Object>> rows() {
        if (rowsCalled) {
            throw new IllegalStateException("rows() may only be called once");
        }
        if (closed) {
            throw new IllegalStateException("RowStream is closed");
        }
        rowsCalled = true;
        Spliterator<Map<String, Object>> spliterator = new Spliterators.AbstractSpliterator<>(
                Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
            @Override
            public boolean tryAdvance(Consumer<? super Map<String, Object>> action) {
                return advance(action);
            }
        };
        return StreamSupport.stream(spliterator, false).onClose(this::close);
    }

    private boolean advance(Consumer<? super Map<String, Object>> action) {
        if (exhausted) {
            return false;
        }
        if (closed) {
            throw new IllegalStateException("RowStream is closed");
        }
        Map<String, Object> row;
        try {
            if (!resultSet.next()) {
                exhausted = true;
                close();
                return false;
            }
            row = ResultSets.toMap(resultSet);
        } catch (SQLException e) {
            try {
                close();
            } catch (QueryException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw QueryException.wrap(e, secret);
        }
        count++;
        if (count % logFrequency == 0) {
            logger.info("Streamed {} rows...", count);
        }
        action.accept(row);
        return true;
    }

    /**
     * Releases the result set, statement and connection. Safe to call more than once.
     * @throws QueryException if the driver fails to release a resource
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        SQLException failure = null;
        for (AutoCloseable resource : new AutoCloseable[] {resultSet, statement, connection}) {
            try {
                if (resource == connection) {
                    Pourover.endTransaction(connection, originalAutoCommit);
                }
                resource.close();
            } catch (Exception e) {
                SQLException sqlException = e instanceof SQLException s ? s : new SQLException(e);
                if (failure == null) {
                    failure = sqlException;
                } else {
                    failure.addSuppressed(sqlException);
                }
            }
        }
        if (failure != null) {
            throw QueryException.wrap(failure, secret);
        }
    }
}
