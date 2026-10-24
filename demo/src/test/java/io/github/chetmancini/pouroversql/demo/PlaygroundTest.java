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

package io.github.chetmancini.pouroversql.demo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PlaygroundTest {

    private static final Gson GSON = new Gson();

    private Playground.Running playground;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() {
        playground = Playground.start(0);
    }

    @AfterEach
    void stop() {
        playground.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + playground.port() + path;
    }

    private HttpResponse<String> post(String sql, String keyCase, String contentType, String origin) throws Exception {
        String body = GSON.toJson(Map.of("sql", sql, "keyCase", keyCase));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url("/query")))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String sql) throws Exception {
        return post(sql, "default", "application/json", null);
    }

    private static JsonObject json(HttpResponse<String> response) {
        return GSON.fromJson(response.body(), JsonObject.class);
    }

    @Test
    void healthReturnsOk() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/health"))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ok");
    }

    @Test
    void rootServesThePage() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/"))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/html"));
        assertThat(response.body()).contains("Pour-Over SQL");
    }

    @Test
    void selectReturnsJsonlAndCsv() throws Exception {
        HttpResponse<String> response = post("SELECT \"OrderId\", \"CustomerName\" FROM orders ORDER BY \"OrderId\"");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonObject body = json(response);
        assertThat(body.get("rowCount").getAsInt()).isGreaterThan(0);
        assertThat(body.get("truncated").getAsBoolean()).isFalse();
        assertThat(body.get("jsonl").getAsString()).startsWith("{\"OrderId\":1,");
        assertThat(body.get("csv").getAsString()).startsWith("OrderId,CustomerName\n1,");
        assertThat(body.get("javaSnippet").getAsString()).contains("Pourover.of(dataSource)").contains("db.list(");
    }

    @Test
    void csvQuotesCommasAndQuotesAndNullsAreEmpty() throws Exception {
        JsonObject body = json(post("SELECT \"CustomerName\", \"Note\" FROM orders ORDER BY \"OrderId\""));
        assertThat(body.get("csv").getAsString()).contains("\"Pepper, Penny\"").contains("\"\"extra hot\"\"");
        assertThat(body.get("jsonl").getAsString()).contains("\"Note\":null");
    }

    @Test
    void keyCaseSnakeIsApplied() throws Exception {
        JsonObject body = json(post("SELECT \"OrderId\", \"CustomerName\" FROM orders", "snake",
                "application/json", null));
        assertThat(body.get("jsonl").getAsString()).contains("\"order_id\":").doesNotContain("OrderId");
        assertThat(body.get("csv").getAsString()).startsWith("order_id,customer_name\n");
        assertThat(body.get("javaSnippet").getAsString()).contains("KeyCase.SNAKE");
    }

    @Test
    void keyCaseCamelIsApplied() throws Exception {
        JsonObject body = json(post("SELECT \"OrderId\", \"CustomerName\" FROM orders", "camel",
                "application/json", null));
        assertThat(body.get("csv").getAsString()).startsWith("orderId,customerName\n");
    }

    @Test
    void resultsAreTruncatedAtTheRowCap() throws Exception {
        JsonObject body = json(post("SELECT X FROM SYSTEM_RANGE(1, 5000)"));
        assertThat(body.get("rowCount").getAsInt()).isEqualTo(Playground.MAX_ROWS);
        assertThat(body.get("truncated").getAsBoolean()).isTrue();
    }

    @Test
    void nonSelectIsRejectedWith400() throws Exception {
        for (String sql : List.of("DROP TABLE orders", "DELETE FROM orders", "INSERT INTO orders VALUES (1)",
                "UPDATE orders SET \"Note\" = 'x'", "CREATE TABLE t (a INT)", "")) {
            HttpResponse<String> response = post(sql);
            assertThat(response.statusCode()).as(sql).isEqualTo(400);
            assertThat(json(response).get("error").getAsString()).as(sql).isNotBlank();
        }
        assertThat(json(post("SELECT COUNT(*) AS N FROM orders")).get("rowCount").getAsInt()).isEqualTo(1);
    }

    @Test
    void commentsBeforeSelectAreAllowed() throws Exception {
        assertThat(post("-- orders\n/* all */ SELECT 1 AS One;").statusCode()).isEqualTo(200);
        assertThat(post("WITH t AS (SELECT 1 AS A) SELECT * FROM t").statusCode()).isEqualTo(200);
    }

    @Test
    void badSqlReturns400WithMessage() throws Exception {
        HttpResponse<String> response = post("SELECT nope FROM orders");
        assertThat(response.statusCode()).isEqualTo(400);
        String error = json(response).get("error").getAsString();
        assertThat(error).containsIgnoringCase("NOPE");
        assertThat(error).doesNotContain("\tat ").doesNotContain("Exception");
    }

    @Test
    void fileReadFunctionIsRejected() throws Exception {
        for (String sql : List.of("SELECT FILE_READ('/etc/hosts')", "select file_read('/etc/hosts')",
                "SELECT * FROM CSVREAD('/etc/hosts')", "SELECT FILE_WRITE('x', '/tmp/x')",
                "SELECT * FROM LINK_SCHEMA('A','','jdbc:h2:mem:','sa','','PUBLIC')",
                "WITH t AS (SELECT 1) SELECT * FROM t WHERE 1 IN (SELECT 1 FROM CSVREAD('x'))")) {
            HttpResponse<String> response = post(sql);
            assertThat(response.statusCode()).as(sql).isEqualTo(400);
            assertThat(json(response).get("error").getAsString()).as(sql).contains("not allowed");
        }
    }

    @Test
    void informationSchemaIsAllowed() throws Exception {
        assertThat(post("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'SHOP'").statusCode())
                .isEqualTo(200);
    }

    @Test
    void secondStatementIsRejected() throws Exception {
        HttpResponse<String> response = post("SELECT 1; DROP TABLE orders");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("error").getAsString()).containsIgnoringCase("one statement");
        assertThat(post("SELECT 1; -- done").statusCode()).isEqualTo(200);
        assertThat(post("SELECT 1; SELECT 2").statusCode()).isEqualTo(400);
    }

    @Test
    void foreignOriginIsRejected() throws Exception {
        assertThat(post("SELECT 1", "default", "application/json", "http://evil.example").statusCode()).isEqualTo(403);
        assertThat(post("SELECT 1", "default", "application/json", "http://localhost:" + (playground.port() + 1))
                .statusCode()).isEqualTo(403);
        assertThat(post("SELECT 1", "default", "application/json", "http://localhost:" + playground.port())
                .statusCode()).isEqualTo(200);
        assertThat(post("SELECT 1", "default", "application/json", "http://127.0.0.1:" + playground.port())
                .statusCode()).isEqualTo(200);
    }

    @Test
    void wrongContentTypeIsRejected() throws Exception {
        assertThat(post("SELECT 1", "default", "text/plain", null).statusCode()).isEqualTo(415);
        assertThat(post("SELECT 1", "default", null, null).statusCode()).isEqualTo(415);
    }

    @Test
    void oversizedBodyIsRejected() throws Exception {
        HttpResponse<String> response = post("SELECT '" + "a".repeat(70_000) + "'");
        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void wrongHostHeaderIsRejected() throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), playground.port())) {
            socket.getOutputStream().write(("GET /health HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n")
                    .getBytes());
            String reply = new String(socket.getInputStream().readAllBytes());
            assertThat(reply).startsWith("HTTP/1.1 403");
        }
    }

    @Test
    void readOnlyUserCannotWrite() throws Exception {
        try (Connection c = playground.readerDataSource().getConnection(); Statement s = c.createStatement()) {
            assertThat(s.executeQuery("SELECT COUNT(*) FROM shop.orders").next()).isTrue();
            for (String sql : List.of("INSERT INTO shop.orders (\"OrderId\") VALUES (999)", "UPDATE shop.orders SET \"Note\" = 'x'",
                    "DELETE FROM shop.orders", "DROP TABLE shop.orders", "CREATE TABLE shop.t (a INT)",
                    "CREATE USER hacker PASSWORD 'x'", "SELECT FILE_READ('/etc/hosts')")) {
                assertThatThrownBy(() -> s.execute(sql)).as(sql).isInstanceOf(SQLException.class);
            }
        }
        assertThat(json(post("SELECT COUNT(*) AS N FROM orders")).get("rowCount").getAsInt()).isEqualTo(1);
    }

    @Test
    void serverBindsLoopbackOnly() throws Exception {
        assertThat(playground.address().getAddress().isLoopbackAddress()).isTrue();
        InetAddress external = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                .flatMap(n -> Collections.list(n.getInetAddresses()).stream())
                .filter(a -> !a.isLoopbackAddress() && !a.isLinkLocalAddress() && a instanceof java.net.Inet4Address)
                .findFirst().orElse(null);
        assumeTrue(external != null, "no non-loopback IPv4 address on this machine");
        assertThatThrownBy(() -> new Socket(external, playground.port()).close()).isInstanceOf(IOException.class);
    }

    @Test
    void dollarQuoteDesyncCannotHideSecondStatement() throws Exception {
        for (String sql : List.of("SELECT $$'$$; DROP TABLE SHOP.T -- '", "SELECT $$ ; SELECT 2")) {
            HttpResponse<String> response = post(sql);
            assertThat(response.statusCode()).as(sql).isEqualTo(400);
            assertThat(json(response).get("error").getAsString()).as(sql).containsIgnoringCase("unsupported syntax");
        }
    }

    @Test
    void dollarQuoteDesyncCannotHideFileRead() throws Exception {
        HttpResponse<String> response = post("SELECT $$'$$, FILE_READ('/etc/hosts') -- '");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("error").getAsString()).containsIgnoringCase("unsupported syntax");
    }

    @Test
    void slashSlashCommentDesyncIsRejected() throws Exception {
        HttpResponse<String> response = post("SELECT 1 // '\n; CREATE TABLE PWN(A INT) -- '");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("error").getAsString()).containsIgnoringCase("unsupported syntax");
    }

    @Test
    void carriageReturnEndsALineCommentSoItCannotHideASecondStatement() throws Exception {
        HttpResponse<String> response = post("SELECT 1 --x\r; DROP TABLE orders");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("error").getAsString()).containsIgnoringCase("one statement");
    }

    @Test
    void unterminatedQuotesAndCommentsAreRejected() throws Exception {
        for (String sql : List.of("SELECT 'abc", "SELECT \"abc", "SELECT 1 /* never closed")) {
            HttpResponse<String> response = post(sql);
            assertThat(response.statusCode()).as(sql).isEqualTo(400);
            assertThat(json(response).get("error").getAsString()).as(sql).containsIgnoringCase("unsupported syntax");
        }
    }

    @Test
    void setStatementsAreRejected() throws Exception {
        assertThat(post("SET QUERY_TIMEOUT 0").statusCode()).isEqualTo(400);
        assertThat(post("SELECT 1; SET QUERY_TIMEOUT 0").statusCode()).isEqualTo(400);
    }

    @Test
    void normalQueriesWithQuotedDollarsAndSlashesStillWork() throws Exception {
        HttpResponse<String> response = post(
                "SELECT '$$' AS A, 'a//b' AS B, 'it''s' AS C /* $$ and // are fine in comments */\n-- // and $$ too\n");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("jsonl").getAsString()).contains("\"A\":\"$$\"").contains("a//b");
    }

    @Test
    void queryTimeoutCannotBeDisabledBySet() throws Exception {
        // The JDBC timeout is set far out of reach, as if a session SET had disabled it: only the
        // server-side wall-clock deadline can stop this query.
        try (Playground.Running slow = Playground.start(0, new Playground.Limits(600, 1_000))) {
            long started = System.nanoTime();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + slow.port() + "/query"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(Map.of("sql",
                            "SELECT SUM(X * X) FROM SYSTEM_RANGE(1, 4000000000)", "keyCase", "default"))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            long seconds = (System.nanoTime() - started) / 1_000_000_000L;
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(json(response).get("error").getAsString()).containsIgnoringCase("time");
            assertThat(seconds).isLessThan(8);
            HttpResponse<String> health = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + slow.port() + "/health")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void hugeResultIsTruncatedByBytes() throws Exception {
        HttpResponse<String> response = post("SELECT REPEAT('x', 100000000) FROM SYSTEM_RANGE(1, 1000)");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("truncated").getAsBoolean()).isTrue();
        assertThat(response.body().length()).isLessThan(Playground.MAX_RESULT_CHARS * 2 + 4096);

        HttpResponse<String> many = post("SELECT REPEAT('x', 20000) AS V FROM SYSTEM_RANGE(1, 1000)");
        JsonObject body = json(many);
        assertThat(body.get("truncated").getAsBoolean()).isTrue();
        assertThat(body.get("rowCount").getAsInt()).isBetween(1, 999);
        assertThat(many.body().length()).isLessThan(Playground.MAX_RESULT_CHARS * 2 + 4096);
        assertThat(post("SELECT 1 AS One").statusCode()).isEqualTo(200);
    }

    @Test
    void exactly1000RowsIsNotTruncated() throws Exception {
        JsonObject body = json(post("SELECT X FROM SYSTEM_RANGE(1, 1000)"));
        assertThat(body.get("rowCount").getAsInt()).isEqualTo(1000);
        assertThat(body.get("truncated").getAsBoolean()).isFalse();
    }

    @Test
    void row1001IsTruncated() throws Exception {
        JsonObject body = json(post("SELECT X FROM SYSTEM_RANGE(1, 1001)"));
        assertThat(body.get("rowCount").getAsInt()).isEqualTo(1000);
        assertThat(body.get("truncated").getAsBoolean()).isTrue();
    }

    @Test
    void javaSnippetEscapesQuoteRuns() throws Exception {
        for (String quotes : List.of("\"\"\"", "\"\"\"\"", "\"\"\"\"\"\"\"")) {
            JsonObject body = json(post("SELECT '" + quotes + "' AS A"));
            String snippet = body.get("javaSnippet").getAsString();
            assertThat(unescapedDelimiters(snippet)).as(quotes).isEqualTo(2); // only the opening and closing
            assertThat(snippet).contains("\\\"");
        }
    }

    /** Counts runs of three unescaped quotes the way the Java lexer would see them in a text block. */
    private static int unescapedDelimiters(String source) {
        int delimiters = 0;
        int run = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
                run = 0;
            } else if (c == '"') {
                if (++run == 3) {
                    delimiters++;
                    run = 0;
                }
            } else {
                run = 0;
            }
        }
        return delimiters;
    }

    @Test
    void emptyResultCsvHasHeader() throws Exception {
        JsonObject body = json(post("SELECT \"OrderId\", \"CustomerName\" FROM orders WHERE 1 = 0", "snake",
                "application/json", null));
        assertThat(body.get("rowCount").getAsInt()).isZero();
        assertThat(body.get("jsonl").getAsString()).isEmpty();
        assertThat(body.get("csv").getAsString()).isEqualTo("order_id,customer_name\n");
    }

    @Test
    void unexpectedExceptionReturnsGeneric500() throws Exception {
        playground.failNextQueryForTest(new IllegalArgumentException("secret detail at com.example.Internal"));
        HttpResponse<String> response = post("SELECT 1");
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo("{\"error\":\"internal error\"}");
    }
}
