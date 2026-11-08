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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class RowStreamTest {
    @Test
    void mappingRuntimeExceptionClosesResourcesAndPropagates() {
        IllegalStateException failure = new IllegalStateException("mapping failed");
        JdbcFixture fixture = new JdbcFixture(failure);

        assertThatThrownBy(() -> fixture.stream.rows().toList()).isSameAs(failure);
        assertThat(fixture.closeEvents).containsExactly(
                "resultSet.close", "statement.close", "connection.close");

        fixture.stream.close();
        assertThat(fixture.closeEvents).containsExactly(
                "resultSet.close", "statement.close", "connection.close");
        assertThatThrownBy(() -> fixture.stream.rows())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rows() may only be called once");
    }

    @Test
    void mappingErrorClosesResourcesAndPropagates() {
        AssertionError failure = new AssertionError("mapping failed");
        JdbcFixture fixture = new JdbcFixture(failure);

        assertThatThrownBy(() -> fixture.stream.rows().toList()).isSameAs(failure);
        assertThat(fixture.closeEvents).containsExactly(
                "resultSet.close", "statement.close", "connection.close");

        fixture.stream.close();
        assertThat(fixture.closeEvents).containsExactly(
                "resultSet.close", "statement.close", "connection.close");
    }

    @Test
    void closeFailureIsSuppressedOnMappingRuntimeFailure() {
        IllegalStateException mappingFailure = new IllegalStateException("mapping failed");
        SQLException closeFailure = new SQLException("close failed");
        JdbcFixture fixture = new JdbcFixture(mappingFailure, closeFailure);

        Throwable thrown = catchThrowable(() -> fixture.stream.rows().toList());

        assertThat(thrown).isSameAs(mappingFailure);
        assertThat(thrown.getSuppressed()).hasSize(1);
        assertThat(thrown.getSuppressed()[0]).isInstanceOf(QueryException.class);
        assertThat(thrown.getSuppressed()[0].getCause()).isSameAs(closeFailure);
        assertThat(fixture.closeEvents).containsExactly(
                "resultSet.close", "statement.close", "connection.close");

        fixture.stream.close();
        assertThat(fixture.closeEvents).containsExactly(
                "resultSet.close", "statement.close", "connection.close");
    }

    @Test
    void successfulStreamReturnsRowsAndClosesOnce() {
        JdbcFixture fixture = new JdbcFixture(null);

        assertThat(fixture.stream.rows().toList()).containsExactly(Map.of("n", 1));
        assertThat(fixture.closeEvents).containsExactly(
                "resultSet.close", "statement.close", "connection.close");

        fixture.stream.close();
        assertThat(fixture.closeEvents).containsExactly(
                "resultSet.close", "statement.close", "connection.close");
    }

    private static final class JdbcFixture {
        private final List<String> closeEvents = new ArrayList<>();
        private final RowStream stream;

        private JdbcFixture(Throwable mappingFailure) {
            this(mappingFailure, null);
        }

        private JdbcFixture(Throwable mappingFailure, SQLException closeFailure) {
            ResultSetMetaData metadata = proxy(ResultSetMetaData.class, (p, method, args) ->
                    switch (method.getName()) {
                        case "getColumnCount" -> 1;
                        case "getColumnLabel" -> "n";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            int[] nextCalls = {0};
            ResultSet resultSet = proxy(ResultSet.class, (p, method, args) ->
                    switch (method.getName()) {
                        case "next" -> nextCalls[0]++ == 0;
                        case "getMetaData" -> metadata;
                        case "getObject" -> {
                            if (mappingFailure != null) {
                                throw mappingFailure;
                            }
                            yield 1;
                        }
                        case "close" -> {
                            closeEvents.add("resultSet.close");
                            if (closeFailure != null) {
                                throw closeFailure;
                            }
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            Statement statement = proxy(Statement.class, (p, method, args) -> {
                if (method.getName().equals("close")) {
                    closeEvents.add("statement.close");
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            });
            Connection connection = proxy(Connection.class, (p, method, args) -> {
                if (method.getName().equals("close")) {
                    closeEvents.add("connection.close");
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            });
            this.stream = new RowStream(
                    connection, statement, resultSet, CredentialScrubber.empty(), 100_000, false);
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
