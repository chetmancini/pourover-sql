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

import io.github.chetmancini.pouroversql.clients.FormattingParams;
import io.github.chetmancini.pouroversql.clients.H2TestClient;
import io.github.chetmancini.pouroversql.clients.QueryParams;
import io.github.chetmancini.pouroversql.writers.CallbackRowWriter;
import io.github.chetmancini.pouroversql.writers.FileOutputFormat;
import io.github.chetmancini.pouroversql.writers.KeyCaseFormat;
import io.github.chetmancini.pouroversql.writers.RowHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins current behavior (including known bugs) against an in-memory H2 database
 * so later refactors are deliberate.
 */
class CharacterizationTest {

    @TempDir
    File tempDir;

    private String url;
    private H2TestClient client;
    private SQLExtractor extractor;

    @BeforeEach
    void setUp() throws ClassNotFoundException {
        // Explicit load: DriverManager service discovery is not reliable before the first client call.
        Class.forName("org.h2.Driver");
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        client = new H2TestClient(url);
        extractor = new SQLExtractor(client);
    }

    private void exec(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    private String extractToString(String sql, FileOutputFormat format) throws IOException {
        File out = new File(tempDir, "out." + format.name().toLowerCase());
        extractor.queryToFile(sql, out, format);
        return Files.readString(out.toPath(), StandardCharsets.UTF_8);
    }

    @Test
    void csvQuotesCommasAndQuotes() throws Exception {
        exec("CREATE TABLE t (v VARCHAR(50))", "INSERT INTO t VALUES ('a,\"b\"')");
        String csv = extractToString("SELECT v AS \"v\" FROM t", FileOutputFormat.CSV);
        // Header row, then the value quoted with embedded quotes doubled.
        assertThat(csv).isEqualTo("v" + System.lineSeparator()
                + "\"a,\"\"b\"\"\"" + System.lineSeparator());
    }

    @Test
    void jsonlEscapesNewlinesAndUnicode() throws Exception {
        exec("CREATE TABLE t (v VARCHAR(50))", "INSERT INTO t VALUES ('line1\nline2 é中')");
        String json = extractToString("SELECT v AS \"v\" FROM t", FileOutputFormat.JSON);
        // Newline is escaped; non-ASCII characters are written raw as UTF-8 (not \\u escaped).
        assertThat(json).isEqualTo("{\"v\":\"line1\\nline2 é中\"}" + System.lineSeparator());
    }

    @Test
    void nullsAreWrittenAsJsonNull() throws Exception {
        exec("CREATE TABLE t (v VARCHAR(50))", "INSERT INTO t VALUES (NULL)");
        String json = extractToString("SELECT v AS \"v\" FROM t", FileOutputFormat.JSON);
        assertThat(json).isEqualTo("{\"v\":null}" + System.lineSeparator());
    }

    @Test
    void snakeAndCamelKeyCase() throws Exception {
        exec("CREATE TABLE t (id INT)", "INSERT INTO t VALUES (1)");
        String sql = "SELECT id AS \"FirstName\" FROM t";
        // SQLExtractor(SQLClient) has no way to set FormattingParams, so drive RowHandler directly.
        assertThat(rowKeys(sql, KeyCaseFormat.SNAKE_CASE)).containsExactly("first_name");
        assertThat(rowKeys(sql, KeyCaseFormat.CAMEL_CASE)).containsExactly("firstName");
        assertThat(rowKeys(sql, KeyCaseFormat.DEFAULT)).containsExactly("FirstName");
    }

    private List<String> rowKeys(String sql, KeyCaseFormat format) {
        List<String> keys = new ArrayList<>();
        RowHandler handler = new RowHandler(
                new CallbackRowWriter(row -> keys.addAll(row.keySet())),
                null,
                new FormattingParams(format));
        assertThat(client.queryWithHandler(sql, handler)).isEqualTo(1);
        return keys;
    }

    @Test
    void callbackReceivesEveryRowAndReturnsCount() throws Exception {
        exec("CREATE TABLE t (n INT)", "INSERT INTO t VALUES (1), (2), (3)");
        List<Object> seen = new ArrayList<>();
        Integer count = extractor.queryWithCallback("SELECT n AS \"n\" FROM t ORDER BY n", row -> seen.add(row.get("n")));
        assertThat(count).isEqualTo(3);
        assertThat(seen).containsExactly(1, 2, 3);
    }

    @Test
    void emptyResultWritesEmptyJsonl() throws Exception {
        exec("CREATE TABLE t (v VARCHAR(50))");
        String json = extractToString("SELECT v FROM t", FileOutputFormat.JSON);
        assertThat(json).isEmpty();
    }

    // KNOWN BUG, replaced in Task 5: errors are swallowed and null is returned instead of thrown.
    @Test
    void queryAsListReturnsNullOnBadSql() {
        assertThat(extractor.queryAsList("SELECT * FROM no_such_table")).isNull();
    }

    // KNOWN BUG, replaced in Task 5: errors are swallowed and -1 is returned instead of thrown.
    @Test
    void queryWithHandlerReturnsMinusOneOnBadSql() {
        RowHandler handler = new RowHandler(new CallbackRowWriter(row -> row));
        assertThat(client.queryWithHandler("SELECT * FROM no_such_table", handler)).isEqualTo(-1);
    }

    // KNOWN BUG, replaced in Task 5: getLogFrequency has an inverted null check, so the
    // 3-arg constructor (which leaves logFrequency null) yields null, while the no-arg one
    // (which sets it) yields the default.
    @Test
    void threeArgQueryParamsLogFrequencyIsNull() {
        assertThat(new QueryParams(100, 10, 5).getLogFrequency()).isNull();
        assertThat(new QueryParams().getLogFrequency()).isEqualTo(100_000);
    }
}
