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
package io.github.chetmancini.pouroversql.e2e;

import io.github.chetmancini.pouroversql.ConnectionParams;
import io.github.chetmancini.pouroversql.OutputFormat;
import io.github.chetmancini.pouroversql.Pourover;
import io.github.chetmancini.pouroversql.QueryException;
import io.github.chetmancini.pouroversql.QueryOptions;
import io.github.chetmancini.pouroversql.RowStream;
import io.github.chetmancini.pouroversql.clients.SqlEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * End-to-end tests of the MySQL client against a real MySQL in Testcontainers.
 * Skipped, not failed, when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlE2ETest {

    /** Generated per run so no credential-looking literal lives in the repository. */
    private static final String PASSWORD = "pw-" + UUID.randomUUID();

    @Container
    private static final MySQLContainer DB = new MySQLContainer("mysql:8.4")
            .withDatabaseName("pourover")
            .withUsername("pourover")
            .withPassword(PASSWORD)
            .withInitScript("seed-mysql.sql");

    @TempDir
    Path tempDir;

    private static Pourover pourover() {
        return Pourover.connect(SqlEngine.MYSQL, params(DB.getHost(), DB.getMappedPort(3306)));
    }

    private static ConnectionParams params(String host, int port) {
        return new ConnectionParams(host, port, "pourover", PASSWORD, "pourover");
    }

    @Test
    void listsRowsWithTypes() {
        List<Map<String, Object>> rows = pourover().list("SELECT id, name, created, note FROM items ORDER BY id");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).keySet()).containsExactly("id", "name", "created", "note");
        assertThat(rows.get(0)).containsEntry("id", 1).containsEntry("name", "alpha").containsEntry("note", null);
        assertThat(rows.get(0).get("created")).isInstanceOf(java.time.LocalDateTime.class);
        assertThat(rows.get(0).get("created").toString()).startsWith("2024-01-02").contains("03:04:05");
        assertThat(rows.get(1)).containsEntry("id", 2).containsEntry("name", "say \"hi\", ok\nbye")
                .containsEntry("note", "second");
    }

    @Test
    void writesJsonlAndCsvFiles() throws IOException {
        Pourover db = pourover();
        String sql = "SELECT id, name, created, note FROM items ORDER BY id";

        Path jsonl = db.writeTo(sql, tempDir.resolve("items.jsonl"), OutputFormat.JSONL);
        List<String> jsonLines = Files.readAllLines(jsonl, StandardCharsets.UTF_8);
        assertThat(jsonLines).containsExactly(
                "{\"id\":1,\"name\":\"alpha\",\"created\":\"2024-01-02T03:04:05\",\"note\":null}",
                "{\"id\":2,\"name\":\"say \\\"hi\\\", ok\\nbye\",\"created\":\"2024-06-07T08:09:10\",\"note\":\"second\"}");

        Path csv = db.writeTo(sql, tempDir.resolve("items.csv"), OutputFormat.CSV);
        String text = Files.readString(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("id,name,created,note");
        assertThat(text).contains("1,alpha,2024-01-02T03:04:05,");
        // The comma, quote and newline force quoting; the quote is doubled.
        assertThat(text).contains("2,\"say \"\"hi\"\", ok\nbye\",2024-06-07T08:09:10,second");
    }

    @Test
    void streamsLargeResultWithSmallFetchSize() throws Exception {
        QueryOptions options = QueryOptions.of(100, null, null);
        AtomicLong count = new AtomicLong();
        try (RowStream stream = pourover().stream("SELECT n FROM big ORDER BY n", options)) {
            stream.rows().forEach(row -> count.incrementAndGet());
        }
        assertThat(count).hasValue(10_000);

        // MySQL caveat: Connector/J ignores fetchSize unless useCursorFetch=true is set (or the
        // statement uses fetchSize Integer.MIN_VALUE for row-by-row streaming), so by default the
        // driver buffers the whole result. This test asserts the count only; MySQL behavior is
        // unchanged here.
    }

    @Test
    void connectionFailureThrowsQueryException() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Pourover db = Pourover.connect(SqlEngine.MYSQL, params("127.0.0.1", closedPort));

        QueryException e = catchThrowableOfType(QueryException.class, () -> db.list("SELECT 1"));

        assertThat(e).isNotNull();
        assertThat(e.getMessage()).doesNotContain(PASSWORD);
        assertThat(db.toString()).doesNotContain(PASSWORD);
    }
}
