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

import io.github.chetmancini.pouroversql.clients.SqlEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Behavior of the {@link Pourover} API against an in-memory H2 database. Also carries
 * the ported Task 2 characterization pins (CSV quoting, JSONL escaping, nulls, key case,
 * callback count, empty result, default log frequency), now asserting the new behavior.
 */
class PouroverTest {

    private static final String NL = System.lineSeparator();

    @TempDir
    Path tempDir;

    private H2DataSource ds;
    private Pourover pourover;

    @BeforeEach
    void setUp() {
        ds = new H2DataSource();
        pourover = Pourover.of(ds);
    }

    private String writeToString(String sql, OutputFormat format, FormatOptions fo) throws IOException {
        Path out = tempDir.resolve("out." + format.name().toLowerCase());
        Path written = pourover.writeTo(sql, out, format, fo, QueryOptions.defaults());
        assertThat(written).isEqualTo(out);
        return Files.readString(out, StandardCharsets.UTF_8);
    }

    private String writeToString(String sql, OutputFormat format) throws IOException {
        return writeToString(sql, format, FormatOptions.defaults());
    }

    @Test
    void queriesRunWithAutoCommitOffAndRestored() throws Exception {
        ds.exec("CREATE TABLE t (n INT)", "INSERT INTO t VALUES (1), (2)");
        ds.initialAutoCommit(true);

        pourover.list("SELECT n FROM t");
        assertThat(ds.events()).containsExactly(
                "setAutoCommit(false)", "prepareStatement autoCommit=false", "rollback", "setAutoCommit(true)", "close");

        ds.events().clear();
        pourover.forEach("SELECT n FROM t", row -> { });
        assertThat(ds.events()).containsExactly(
                "setAutoCommit(false)", "prepareStatement autoCommit=false", "rollback", "setAutoCommit(true)", "close");

        ds.events().clear();
        pourover.writeTo("SELECT n FROM t", tempDir.resolve("a.jsonl"), OutputFormat.JSONL);
        assertThat(ds.events()).containsExactly(
                "setAutoCommit(false)", "prepareStatement autoCommit=false", "rollback", "setAutoCommit(true)", "close");

        ds.events().clear();
        try (RowStream stream = pourover.stream("SELECT n FROM t")) {
            assertThat(ds.events()).containsExactly("setAutoCommit(false)", "prepareStatement autoCommit=false");
            assertThat(stream.rows().count()).isEqualTo(2);
        }
        assertThat(ds.events()).containsExactly(
                "setAutoCommit(false)", "prepareStatement autoCommit=false", "rollback", "setAutoCommit(true)", "close");
    }

    @Test
    void connectionAlreadyInTransactionIsLeftAlone() throws Exception {
        ds.exec("CREATE TABLE t (n INT)");
        ds.initialAutoCommit(false);
        pourover.list("SELECT n FROM t");
        assertThat(ds.events()).containsExactly("prepareStatement autoCommit=false", "close");
        ds.events().clear();
        try (RowStream stream = pourover.stream("SELECT n FROM t")) {
            assertThat(stream.rows().count()).isZero();
        }
        assertThat(ds.events()).containsExactly("prepareStatement autoCommit=false", "close");
    }

    @Test
    void callersOpenTransactionIsNotRolledBack() throws Exception {
        ds.exec("CREATE TABLE t (n INT)");
        try (Connection caller = DriverManager.getConnection(callerUrl(), H2DataSource.USER, H2DataSource.PASSWORD)) {
            caller.setAutoCommit(false);
            try (Statement s = caller.createStatement()) {
                s.execute("INSERT INTO t VALUES (1)");
            }
            // A DataSource that hands out the caller's own connection and ignores close(),
            // like a transaction-aware proxy or a single-connection DataSource.
            Pourover shared = Pourover.of(sharedConnectionDataSource(caller));
            assertThat(shared.list("SELECT COUNT(*) AS \"c\" FROM t").get(0).get("c")).isEqualTo(1L);
            assertThat(shared.list("SELECT COUNT(*) AS \"c\" FROM t").get(0).get("c")).isEqualTo(1L);
            try (RowStream stream = shared.stream("SELECT n FROM t")) {
                assertThat(stream.rows().count()).isEqualTo(1);
            }
            assertThat(caller.getAutoCommit()).isFalse();
            caller.commit();
        }
        assertThat(pourover.list("SELECT n FROM t")).hasSize(1);
    }

    private String callerUrl() throws SQLException {
        try (Connection c = ds.getConnection()) {
            return c.getMetaData().getURL();
        }
    }

    private static DataSource sharedConnectionDataSource(Connection shared) {
        Connection nonClosing = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (p, method, args) -> {
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    try {
                        return method.invoke(shared, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (p, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        return nonClosing;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void queriesStillRunWhenDriverRejectsAutoCommitOff() throws Exception {
        ds.exec("CREATE TABLE t (n INT)", "INSERT INTO t VALUES (1), (2)");
        ds.rejectAutoCommitOff(true);
        List<String> unmanaged = List.of("setAutoCommit(false)", "prepareStatement autoCommit=true", "close");

        assertThat(pourover.list("SELECT n FROM t")).hasSize(2);
        assertThat(ds.events()).containsExactlyElementsOf(unmanaged);

        ds.events().clear();
        try (RowStream stream = pourover.stream("SELECT n FROM t")) {
            assertThat(stream.rows().count()).isEqualTo(2);
        }
        assertThat(ds.events()).containsExactlyElementsOf(unmanaged);

        ds.events().clear();
        Path out = tempDir.resolve("rejected.jsonl");
        pourover.writeTo("SELECT n AS \"n\" FROM t ORDER BY n", out, OutputFormat.JSONL);
        assertThat(Files.readString(out)).isEqualTo("{\"n\":1}" + NL + "{\"n\":2}" + NL);
        assertThat(ds.events()).containsExactlyElementsOf(unmanaged);
        assertThat(ds.allConnectionsClosed()).isTrue();
    }

    @Test
    void autoCommitIsRestoredEvenWhenRollbackFails() throws Exception {
        ds.exec("CREATE TABLE t (n INT)", "INSERT INTO t VALUES (1)");
        ds.failRollback(true);
        assertThat(pourover.list("SELECT n FROM t")).hasSize(1);
        assertThat(ds.events()).endsWith("rollback", "setAutoCommit(true)", "close");

        // Neither a rollback failure nor the restore masks the original query error.
        ds.events().clear();
        assertThatThrownBy(() -> pourover.list("SELECT * FROM missing_table"))
                .isInstanceOf(QueryException.class).hasMessageContaining("MISSING_TABLE");
        assertThat(ds.events()).endsWith("rollback", "setAutoCommit(true)", "close");

        ds.events().clear();
        try (RowStream stream = pourover.stream("SELECT n FROM t")) {
            assertThat(stream.rows().count()).isEqualTo(1);
        }
        assertThat(ds.events()).endsWith("rollback", "setAutoCommit(true)", "close");
    }

    @Test
    void failedQueryStillRollsBackAndRestoresAutoCommit() {
        assertThatThrownBy(() -> pourover.list("SELECT * FROM missing_table")).isInstanceOf(QueryException.class);
        assertThat(ds.events()).endsWith("rollback", "setAutoCommit(true)", "close");
    }

    @Test
    void listReturnsRowsInColumnOrder() throws Exception {
        ds.exec("CREATE TABLE t (z INT, a VARCHAR(10), m INT)",
                "INSERT INTO t VALUES (1, 'x', 10), (2, 'y', 20)");
        List<Map<String, Object>> rows = pourover.list("SELECT z AS \"z\", a AS \"a\", m AS \"m\" FROM t ORDER BY z");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).keySet()).containsExactly("z", "a", "m");
        assertThat(rows.get(0).values()).containsExactly(1, "x", 10);
        assertThat(rows.get(1).values()).containsExactly(2, "y", 20);
        assertThat(ds.allConnectionsClosed()).isTrue();
    }

    @Test
    void listHonorsMaxRows() throws Exception {
        ds.exec("CREATE TABLE t (n INT)", "INSERT INTO t VALUES (1), (2), (3)");
        List<Map<String, Object>> rows = pourover.list("SELECT n FROM t ORDER BY n", QueryOptions.of(10, 2, 30));
        assertThat(rows).hasSize(2);
    }

    @Test
    void streamClosesConnection() throws Exception {
        ds.exec("CREATE TABLE t (n INT)", "INSERT INTO t VALUES (1), (2), (3)");
        List<Object> seen;
        try (RowStream stream = pourover.stream("SELECT n AS \"n\" FROM t ORDER BY n")) {
            // The connection stays open while rows are pending.
            assertThat(ds.connections()).hasSize(1);
            assertThat(ds.connections().get(0).isClosed()).isFalse();
            seen = stream.rows().map(row -> row.get("n")).collect(Collectors.toList());
        }
        assertThat(seen).containsExactly(1, 2, 3);
        assertThat(ds.allConnectionsClosed()).isTrue();
    }

    @Test
    void closingRowsStreamClosesConnection() throws Exception {
        ds.exec("CREATE TABLE t (n INT)", "INSERT INTO t VALUES (1), (2), (3)");
        RowStream stream = pourover.stream("SELECT n FROM t");
        try (Stream<Map<String, Object>> rows = stream.rows()) {
            assertThat(rows.findFirst()).isPresent();
            // Partially read: still open until the stream is closed.
            assertThat(ds.connections().get(0).isClosed()).isFalse();
        }
        assertThat(ds.allConnectionsClosed()).isTrue();
        stream.close(); // idempotent
    }

    @Test
    void fullyReadStreamReleasesConnectionEvenWithoutClose() throws Exception {
        ds.exec("CREATE TABLE t (n INT)", "INSERT INTO t VALUES (1), (2)");
        RowStream stream = pourover.stream("SELECT n FROM t");
        assertThat(stream.rows().count()).isEqualTo(2);
        assertThat(ds.allConnectionsClosed()).isTrue();
    }

    @Test
    void rowsCanOnlyBeCalledOnce() throws Exception {
        ds.exec("CREATE TABLE t (n INT)");
        try (RowStream stream = pourover.stream("SELECT n FROM t")) {
            assertThat(stream.rows().count()).isZero();
            assertThatThrownBy(stream::rows).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void badSqlThrowsQueryExceptionWithoutPassword() throws Exception {
        String sql = "SELECT * FROM no_such_table";
        QueryException e = catchThrowableOfType(QueryException.class, () -> pourover.list(sql));
        assertThat(e).isNotNull();
        assertThat(e.getCause()).isInstanceOf(SQLException.class);
        assertThat(e.getMessage()).doesNotContain(H2DataSource.PASSWORD).containsIgnoringCase("no_such_table");
        assertThat(e.getSqlState()).isNotBlank();
        assertThat(ds.allConnectionsClosed()).isTrue();

        // Every entry point throws instead of swallowing (old API returned null / -1).
        assertThatThrownBy(() -> pourover.forEach(sql, row -> { })).isInstanceOf(QueryException.class);
        assertThatThrownBy(() -> pourover.stream(sql)).isInstanceOf(QueryException.class);
        assertThatThrownBy(() -> pourover.writeTo(sql, tempDir.resolve("x.jsonl"), OutputFormat.JSONL))
                .isInstanceOf(QueryException.class);
        assertThat(ds.allConnectionsClosed()).isTrue();
    }

    @Test
    void connectFailureMessageOmitsPassword() {
        String secret = "pw-" + UUID.randomUUID();
        ConnectionParams params = new ConnectionParams("127.0.0.1", 1, "nobody", secret, "nodb", null);
        Pourover remote = Pourover.connect(SqlEngine.POSTGRESQL, params);
        QueryException e = catchThrowableOfType(QueryException.class, () -> remote.list("SELECT 1"));
        assertThat(e).isNotNull();
        assertThat(e.getMessage()).doesNotContain(secret);
        assertThat(remote.toString()).doesNotContain(secret);
    }

    @Test
    void connectRejectsUnsupportedEngine() {
        ConnectionParams params = new ConnectionParams("h", null, "u", null, "d", null);
        assertThatThrownBy(() -> Pourover.connect(SqlEngine.ORACLE, params))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pourover.connect(SqlEngine.BIGQUERY, params))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consumerExceptionPropagatesAndClosesConnection() throws Exception {
        ds.exec("CREATE TABLE t (n INT)", "INSERT INTO t VALUES (1), (2)");
        assertThatThrownBy(() -> pourover.forEach("SELECT n FROM t", row -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(ds.allConnectionsClosed()).isTrue();
    }

    @Test
    void ofDoesNotCloseCallersDataSource() throws Exception {
        ds.exec("CREATE TABLE t (n INT)", "INSERT INTO t VALUES (1)");
        assertThat(pourover.list("SELECT n FROM t")).hasSize(1);
        assertThat(pourover.list("SELECT n FROM t")).hasSize(1);
        assertThat(ds.connections()).hasSize(2);
        assertThat(ds.allConnectionsClosed()).isTrue();
        // The caller's DataSource still works after Pourover is done with it.
        try (Connection c = ds.getConnection()) {
            assertThat(c.isValid(1)).isTrue();
        }
    }

    @Test
    void optionsFactoryAppliesDefaultLogFrequency() {
        assertThat(QueryOptions.of(500, null, null).logFrequency()).isEqualTo(100_000);
        assertThat(QueryOptions.defaults().logFrequency()).isEqualTo(100_000);
        assertThat(QueryOptions.defaults().fetchSize()).isEqualTo(10_000);
        assertThatThrownBy(() -> new QueryOptions(500, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forEachReceivesEveryRowAndReturnsCount() throws Exception {
        ds.exec("CREATE TABLE t (n INT)", "INSERT INTO t VALUES (1), (2), (3)");
        List<Object> seen = new ArrayList<>();
        long count = pourover.forEach("SELECT n AS \"n\" FROM t ORDER BY n", row -> seen.add(row.get("n")));
        assertThat(count).isEqualTo(3);
        assertThat(seen).containsExactly(1, 2, 3);
        assertThat(ds.allConnectionsClosed()).isTrue();
    }

    @Test
    void emptyResultWritesEmptyJsonl() throws Exception {
        ds.exec("CREATE TABLE t (v VARCHAR(50))");
        assertThat(writeToString("SELECT v FROM t", OutputFormat.JSONL)).isEmpty();
        assertThat(ds.allConnectionsClosed()).isTrue();
    }

    @Test
    void emptyResultWritesCsvHeaderOnly() throws Exception {
        ds.exec("CREATE TABLE t (id INT, v VARCHAR(50))");
        String sql = "SELECT id AS \"FirstName\", v AS \"v\" FROM t";
        assertThat(writeToString(sql, OutputFormat.CSV, new FormatOptions(KeyCase.SNAKE)))
                .isEqualTo("first_name,v" + NL);
        assertThat(writeToString(sql, OutputFormat.JSONL)).isEmpty();
        assertThat(ds.allConnectionsClosed()).isTrue();
    }

    @Test
    void keyCaseCollisionThrows() throws Exception {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("userId", 1);
        row.put("user_id", 2);
        assertThatThrownBy(() -> new FormatOptions(KeyCase.SNAKE).formatKeys(row))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("userId").hasMessageContaining("user_id");
        Map<String, Object> upper = new java.util.LinkedHashMap<>();
        upper.put("USER_ID", 1);
        upper.put("userId", 2);
        assertThatThrownBy(() -> new FormatOptions(KeyCase.SNAKE).formatKeys(upper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Columns 'USER_ID' and 'userId' both map to key 'user_id' under SNAKE key case");

        ds.exec("CREATE TABLE t (a INT, b INT)", "INSERT INTO t VALUES (1, 2)");
        String sql = "SELECT a AS \"userId\", b AS \"user_id\" FROM t";
        assertThatThrownBy(() -> pourover.writeTo(sql, tempDir.resolve("c.jsonl"), OutputFormat.JSONL,
                new FormatOptions(KeyCase.SNAKE))).isInstanceOf(IllegalStateException.class);
        assertThat(ds.allConnectionsClosed()).isTrue();
    }

    @Test
    void streamClosesConnectionWhenReadFails() throws Exception {
        // Lazy execution makes H2 evaluate each row on next(), so row 2 fails mid-stream.
        H2DataSource lazy = new H2DataSource(";LAZY_QUERY_EXECUTION=1");
        lazy.exec("CREATE TABLE t (n INT)", "INSERT INTO t VALUES (1), (2), (3)");
        List<Object> seen = new ArrayList<>();
        RowStream stream = Pourover.of(lazy).stream("SELECT 10 / (n - 2) AS \"q\" FROM t");
        assertThatThrownBy(() -> stream.rows().forEach(row -> seen.add(row.get("q"))))
                .isInstanceOf(QueryException.class);
        assertThat(seen).containsExactly(-10);
        assertThat(lazy.allConnectionsClosed()).isTrue();
    }

    @Test
    void csvSpecialCharactersRoundTrip() throws Exception {
        ds.exec("CREATE TABLE t (n INT, v VARCHAR(50))",
                "INSERT INTO t VALUES (1, 'a,\"b\"'), (2, 'line1\nline2'), (3, NULL), (4, 'plain')");
        String csv = writeToString("SELECT n AS \"n\", v AS \"v\" FROM t ORDER BY n", OutputFormat.CSV);
        // Header row; commas/quotes/newlines quoted with embedded quotes doubled; null is empty.
        assertThat(csv).isEqualTo("n,v" + NL
                + "1,\"a,\"\"b\"\"\"" + NL
                + "2,\"line1\nline2\"" + NL
                + "3," + NL
                + "4,plain" + NL);
    }

    @Test
    void jsonlEscapesNewlinesAndUnicode() throws Exception {
        ds.exec("CREATE TABLE t (v VARCHAR(50))", "INSERT INTO t VALUES ('line1\nline2 é中')");
        String json = writeToString("SELECT v AS \"v\" FROM t", OutputFormat.JSONL);
        // Newline is escaped; non-ASCII characters are written raw as UTF-8 (not \\u escaped).
        assertThat(json).isEqualTo("{\"v\":\"line1\\nline2 é中\"}" + NL);
    }

    @Test
    void jsonlDoesNotHtmlEscape() throws Exception {
        ds.exec("CREATE TABLE t (v VARCHAR(50))", "INSERT INTO t VALUES ('a=b <c> & ''d''')");
        String json = writeToString("SELECT v AS \"v\" FROM t", OutputFormat.JSONL);
        assertThat(json).isEqualTo("{\"v\":\"a=b <c> & 'd'\"}" + NL);
    }

    @Test
    void timestampsRenderAsIso8601() throws Exception {
        ds.exec("CREATE TABLE t (ts TIMESTAMP, d DATE, tm TIME, tz TIMESTAMP WITH TIME ZONE)",
                "INSERT INTO t VALUES (TIMESTAMP '2024-01-02 03:04:05', DATE '2024-01-02', TIME '03:04:05', "
                        + "TIMESTAMP WITH TIME ZONE '2024-01-02 03:04:05+02:00')");
        String sql = "SELECT ts AS \"ts\", d AS \"d\", tm AS \"tm\", tz AS \"tz\" FROM t";
        assertThat(writeToString(sql, OutputFormat.JSONL)).isEqualTo(
                "{\"ts\":\"2024-01-02T03:04:05\",\"d\":\"2024-01-02\",\"tm\":\"03:04:05\","
                        + "\"tz\":\"2024-01-02T03:04:05+02:00\"}" + NL);
        assertThat(writeToString(sql, OutputFormat.CSV)).isEqualTo(
                "ts,d,tm,tz" + NL + "2024-01-02T03:04:05,2024-01-02,03:04:05,2024-01-02T03:04:05+02:00" + NL);
    }

    private static final String LOB_SQL =
            "SELECT ARRAY[1, 2] AS \"a\", CAST('x' AS CLOB) AS \"c\", CAST(X'01' AS BLOB) AS \"b\"";

    @Test
    void listMaterializesArraysClobsAndBlobs() {
        Map<String, Object> row = pourover.list(LOB_SQL).get(0);
        // Read after list() returned, i.e. after the connection closed: no live locators.
        assertThat(row.get("a")).isEqualTo(List.of(1, 2));
        assertThat(row.get("c")).isEqualTo("x");
        assertThat(row.get("b")).isEqualTo("AQ==");
    }

    @Test
    void jsonlRendersArraysClobsBlobs() throws Exception {
        assertThat(writeToString(LOB_SQL, OutputFormat.JSONL)).isEqualTo("{\"a\":[1,2],\"c\":\"x\",\"b\":\"AQ==\"}" + NL);
    }

    @Test
    void csvRendersBlobAsBase64() throws Exception {
        assertThat(writeToString(LOB_SQL, OutputFormat.CSV)).isEqualTo("a,c,b" + NL + "\"[1,2]\",x,AQ==" + NL);
    }

    @Test
    void byteArrayIsBase64InJsonlAndCsv() throws Exception {
        String sql = "SELECT X'010203' AS \"v\"";
        assertThat(pourover.list(sql).get(0).get("v")).isEqualTo("AQID");
        assertThat(writeToString(sql, OutputFormat.JSONL)).isEqualTo("{\"v\":\"AQID\"}" + NL);
        assertThat(writeToString(sql, OutputFormat.CSV)).isEqualTo("v" + NL + "AQID" + NL);
        // Rows built by hand (not from a ResultSet) render the same way.
        Map<String, Object> row = Map.of("v", new byte[] {1, 2, 3});
        assertThat(RowFormatter.jsonl(row, FormatOptions.defaults())).isEqualTo("{\"v\":\"AQID\"}");
        assertThat(RowFormatter.csvRow(List.of(new byte[] {1, 2, 3}))).isEqualTo("AQID");
    }

    @Test
    void nullsAreWrittenAsJsonNull() throws Exception {
        ds.exec("CREATE TABLE t (v VARCHAR(50))", "INSERT INTO t VALUES (NULL)");
        assertThat(writeToString("SELECT v AS \"v\" FROM t", OutputFormat.JSONL)).isEqualTo("{\"v\":null}" + NL);
        assertThat(pourover.list("SELECT v AS \"v\" FROM t").get(0)).containsEntry("v", null);
    }

    @Test
    void snakeAndCamelKeyCase() throws Exception {
        ds.exec("CREATE TABLE t (id INT)", "INSERT INTO t VALUES (1)");
        Map<String, Object> row = pourover.list("SELECT id AS \"FirstName\" FROM t").get(0);
        // list/forEach/stream return raw column labels; FormatOptions applies key case on demand.
        assertThat(row.keySet()).containsExactly("FirstName");
        assertThat(new FormatOptions(KeyCase.SNAKE).formatKeys(row).keySet()).containsExactly("first_name");
        assertThat(new FormatOptions(KeyCase.CAMEL).formatKeys(row).keySet()).containsExactly("firstName");
        assertThat(new FormatOptions(KeyCase.DEFAULT).formatKeys(row).keySet()).containsExactly("FirstName");
    }

    @Test
    void writeToHonorsKeyCase() throws Exception {
        ds.exec("CREATE TABLE t (id INT)", "INSERT INTO t VALUES (1)");
        String sql = "SELECT id AS \"FirstName\" FROM t";
        assertThat(writeToString(sql, OutputFormat.JSONL, new FormatOptions(KeyCase.SNAKE)))
                .isEqualTo("{\"first_name\":1}" + NL);
        assertThat(writeToString(sql, OutputFormat.JSONL, new FormatOptions(KeyCase.CAMEL)))
                .isEqualTo("{\"firstName\":1}" + NL);
        assertThat(writeToString(sql, OutputFormat.JSONL, FormatOptions.defaults()))
                .isEqualTo("{\"FirstName\":1}" + NL);
        assertThat(writeToString(sql, OutputFormat.CSV, new FormatOptions(KeyCase.SNAKE)))
                .isEqualTo("first_name" + NL + "1" + NL);
    }

    @Test
    void writeToDefaultOverloadWritesJsonl() throws Exception {
        ds.exec("CREATE TABLE t (n INT)", "INSERT INTO t VALUES (7)");
        Path out = tempDir.resolve("default.jsonl");
        pourover.writeTo("SELECT n AS \"n\" FROM t", out, OutputFormat.JSONL);
        assertThat(Files.readString(out)).isEqualTo("{\"n\":7}" + NL);
        assertThat(ds.allConnectionsClosed()).isTrue();
    }

    @Test
    void writeToUnwritablePathThrowsUncheckedIo() throws Exception {
        ds.exec("CREATE TABLE t (n INT)");
        Path out = tempDir.resolve("missing-dir").resolve("out.csv");
        assertThatThrownBy(() -> pourover.writeTo("SELECT n FROM t", out, OutputFormat.CSV))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(ds.connections()).isEmpty();
    }
}
