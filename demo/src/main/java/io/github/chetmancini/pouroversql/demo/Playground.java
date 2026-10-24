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
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.chetmancini.pouroversql.FormatOptions;
import io.github.chetmancini.pouroversql.KeyCase;
import io.github.chetmancini.pouroversql.Pourover;
import io.github.chetmancini.pouroversql.QueryException;
import io.github.chetmancini.pouroversql.QueryOptions;
import io.github.chetmancini.pouroversql.RowFormatter;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * A browser playground for Pour-Over SQL on an in-memory H2 database. Type a SELECT, see the JSONL,
 * the CSV and the Java call side by side. It needs no Docker and no CLI:
 * {@code ./gradlew :demo:run}, then open http://localhost:8080.
 *
 * <p>It executes user-typed SQL, so it is locked down: it binds to the loopback interface only,
 * accepts one SELECT/WITH statement, rejects file and code capable H2 functions, runs queries as a
 * database user that can only read the sample schema, and checks Host, Origin and Content-Type.
 */
public final class Playground {

    static final int MAX_ROWS = 1_000;
    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final Gson GSON = new Gson();

    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(FILE_READ|FILE_WRITE|CSVREAD|CSVWRITE|LINK_SCHEMA|RUNSCRIPT|SCRIPT|ALIAS|CALL|EXECUTE)\\b",
            Pattern.CASE_INSENSITIVE);

    private Playground() {
    }

    public static void main(String[] args) {
        String env = System.getenv("PORT");
        int port = env == null || env.isBlank() ? 8080 : Integer.parseInt(env.trim());
        Running running = start(port);
        System.out.println("Pour-Over SQL playground: http://localhost:" + running.port());
        Runtime.getRuntime().addShutdownHook(new Thread(running::close));
    }

    /** Starts the playground on the loopback interface. Use port 0 for an ephemeral port. */
    public static Running start(int port) {
        try {
            Database database = Database.seed();
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            server.setExecutor(executor);
            Running running = new Running(server, executor, database);
            server.createContext("/", running::handle);
            server.start();
            return running;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A running playground. Closing it stops the server and drops the database. */
    public static final class Running implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final Database database;
        private final Pourover pourover;
        private final QueryOptions options = QueryOptions.of(500, MAX_ROWS + 1, QUERY_TIMEOUT_SECONDS);

        private Running(HttpServer server, ExecutorService executor, Database database) {
            this.server = server;
            this.executor = executor;
            this.database = database;
            this.pourover = Pourover.of(database.reader());
        }

        public int port() {
            return server.getAddress().getPort();
        }

        public InetSocketAddress address() {
            return server.getAddress();
        }

        /** The read-only data source the queries run on; exposed for tests. */
        DataSource readerDataSource() {
            return database.reader();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            database.shutdown();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!hostAllowed(exchange)) {
                    sendText(exchange, 403, "text/plain; charset=utf-8", "Forbidden: unexpected Host header");
                    return;
                }
                if (!originAllowed(exchange)) {
                    sendText(exchange, 403, "text/plain; charset=utf-8", "Forbidden: cross-origin request");
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if (path.equals("/health") && method.equals("GET")) {
                    sendText(exchange, 200, "text/plain; charset=utf-8", "ok");
                } else if (path.equals("/") && method.equals("GET")) {
                    sendText(exchange, 200, "text/html; charset=utf-8", page());
                } else if (path.equals("/query")) {
                    if (!method.equals("POST")) {
                        exchange.getResponseHeaders().set("Allow", "POST");
                        sendError(exchange, 405, "Use POST for /query.");
                    } else {
                        query(exchange);
                    }
                } else {
                    sendText(exchange, 404, "text/plain; charset=utf-8", "Not found");
                }
            }
        }

        private boolean hostAllowed(HttpExchange exchange) {
            String host = exchange.getRequestHeaders().getFirst("Host");
            return host != null && (host.equalsIgnoreCase("localhost:" + port()) || host.equals("127.0.0.1:" + port()));
        }

        private boolean originAllowed(HttpExchange exchange) {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            return origin == null || origin.equals("http://localhost:" + port())
                    || origin.equals("http://127.0.0.1:" + port());
        }

        private void query(HttpExchange exchange) throws IOException {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!mediaType.equals("application/json")) {
                sendError(exchange, 415, "Content-Type must be application/json.");
                return;
            }
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readNBytes(MAX_BODY_BYTES + 1);
            }
            if (body.length > MAX_BODY_BYTES) {
                sendError(exchange, 413, "Request body is larger than " + MAX_BODY_BYTES / 1024 + " KB.");
                return;
            }
            String sql;
            KeyCase keyCase;
            try {
                JsonObject json = GSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
                sql = json.get("sql").getAsString();
                keyCase = json.has("keyCase") && !json.get("keyCase").isJsonNull()
                        ? KeyCase.fromName(json.get("keyCase").getAsString()) : KeyCase.DEFAULT;
            } catch (JsonParseException | NullPointerException | ClassCastException | IllegalStateException
                     | UnsupportedOperationException e) {
                sendError(exchange, 400, "Send JSON like {\"sql\": \"SELECT ...\", \"keyCase\": \"default\"}.");
                return;
            }
            String rejection = SqlGuard.check(sql);
            if (rejection != null) {
                sendError(exchange, 400, rejection);
                return;
            }
            try {
                sendJson(exchange, 200, run(sql.strip(), keyCase));
            } catch (QueryException e) {
                sendError(exchange, 400, driverMessage(e));
            } catch (IllegalStateException e) {
                sendError(exchange, 400, e.getMessage());
            }
        }

        private Map<String, Object> run(String sql, KeyCase keyCase) {
            FormatOptions format = new FormatOptions(keyCase);
            List<Map<String, Object>> rows = pourover.list(sql, options);
            boolean truncated = rows.size() > MAX_ROWS;
            if (truncated) {
                rows = rows.subList(0, MAX_ROWS);
            }
            StringBuilder jsonl = new StringBuilder();
            StringBuilder csv = new StringBuilder();
            for (Map<String, Object> row : rows) {
                jsonl.append(RowFormatter.jsonl(row, format)).append('\n');
                Map<String, Object> keyed = format.formatKeys(row);
                if (csv.length() == 0) {
                    csv.append(RowFormatter.csvHeader(new ArrayList<>(keyed.keySet()))).append('\n');
                }
                csv.append(RowFormatter.csvRow(new ArrayList<>(keyed.values()))).append('\n');
            }
            return Map.of(
                    "jsonl", jsonl.toString(),
                    "csv", csv.toString(),
                    "javaSnippet", JavaSnippet.render(sql, keyCase),
                    "rowCount", rows.size(),
                    "truncated", truncated);
        }

        private static String driverMessage(QueryException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String message = cause.getMessage() == null ? e.getMessage() : cause.getMessage();
            // Keep the first lines of the driver message; never a stack trace.
            return message.length() > 600 ? message.substring(0, 600) + "..." : message;
        }

        private static String page() throws IOException {
            try (InputStream in = Playground.class.getResourceAsStream("/web/index.html")) {
                if (in == null) {
                    throw new IOException("web/index.html is missing from the classpath");
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
            sendJson(exchange, status, Map.of("error", message));
        }

        private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
            sendText(exchange, status, "application/json; charset=utf-8", GSON.toJson(payload));
        }

        private static void sendText(HttpExchange exchange, int status, String contentType, String text)
                throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        }
    }

    /** Accepts one SELECT/WITH statement and rejects file and code capable H2 features. */
    static final class SqlGuard {
        private SqlGuard() {
        }

        /** Returns a rejection message, or null when the statement is allowed. */
        static String check(String sql) {
            if (sql == null) {
                return "Enter a SELECT statement.";
            }
            String clean = blankCommentsAndLiterals(sql).strip();
            while (clean.endsWith(";")) {
                clean = clean.substring(0, clean.length() - 1).stripTrailing();
            }
            if (clean.isEmpty()) {
                return "Enter a SELECT statement.";
            }
            if (clean.indexOf(';') >= 0) {
                return "Only one statement is allowed per request.";
            }
            String upper = clean.toUpperCase(Locale.ROOT);
            if (!(startsWithWord(upper, "SELECT") || startsWithWord(upper, "WITH"))) {
                return "Only SELECT queries (or WITH ... SELECT) are allowed in the playground.";
            }
            var forbidden = FORBIDDEN.matcher(clean);
            if (forbidden.find()) {
                return forbidden.group().toUpperCase(Locale.ROOT) + " is not allowed in the playground.";
            }
            return null;
        }

        private static boolean startsWithWord(String upper, String word) {
            return upper.startsWith(word) && (upper.length() == word.length()
                    || !(Character.isLetterOrDigit(upper.charAt(word.length())) || upper.charAt(word.length()) == '_'));
        }

        /** Replaces comments with a space and the inside of '...' literals with nothing; keeps "..." identifiers. */
        static String blankCommentsAndLiterals(String sql) {
            StringBuilder out = new StringBuilder(sql.length());
            int i = 0;
            int n = sql.length();
            while (i < n) {
                char c = sql.charAt(i);
                if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                    while (i < n && sql.charAt(i) != '\n') {
                        i++;
                    }
                    out.append(' ');
                } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                    int end = sql.indexOf("*/", i + 2);
                    i = end < 0 ? n : end + 2;
                    out.append(' ');
                } else if (c == '\'') {
                    out.append("''");
                    i++;
                    while (i < n) {
                        if (sql.charAt(i) == '\'') {
                            if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                                i += 2;
                                continue;
                            }
                            break;
                        }
                        i++;
                    }
                    i++;
                } else if (c == '"') {
                    int start = i;
                    i++;
                    while (i < n) {
                        if (sql.charAt(i) == '"') {
                            if (i + 1 < n && sql.charAt(i + 1) == '"') {
                                i += 2;
                                continue;
                            }
                            break;
                        }
                        i++;
                    }
                    i = Math.min(i + 1, n);
                    out.append(sql, start, i);
                } else {
                    out.append(c);
                    i++;
                }
            }
            return out.toString();
        }
    }

    /** Builds the compilable Java snippet shown in the UI. */
    static final class JavaSnippet {
        private JavaSnippet() {
        }

        static String render(String sql, KeyCase keyCase) {
            String body = sql.strip();
            while (body.endsWith(";")) {
                body = body.substring(0, body.length() - 1).stripTrailing();
            }
            String escaped = body.replace("\\", "\\\\").replace("\"\"\"", "\\\"\"\"").replace("\r", "");
            if (escaped.endsWith("\"")) {
                escaped = escaped.substring(0, escaped.length() - 1) + "\\\"";
            }
            StringBuilder indented = new StringBuilder();
            for (String line : escaped.split("\n", -1)) {
                indented.append(line.isEmpty() ? "" : "        " + line.stripTrailing()).append('\n');
            }
            String sqlBlock = "\"\"\"\n" + indented + "        \"\"\"";
            StringBuilder out = new StringBuilder();
            out.append("Pourover db = Pourover.of(dataSource);\n");
            out.append("String sql = ").append(sqlBlock).append(";\n\n");
            out.append("// Rows as maps, keys exactly as the database names the columns.\n");
            out.append("List<Map<String, Object>> rows = db.list(sql);\n");
            if (keyCase != KeyCase.DEFAULT) {
                out.append("\n// Write to a file with ").append(keyCase.name().toLowerCase(Locale.ROOT))
                        .append("-case keys.\n");
                out.append("db.writeTo(sql, Path.of(\"orders.jsonl\"), OutputFormat.JSONL,\n");
                out.append("        new FormatOptions(KeyCase.").append(keyCase.name()).append("));\n");
            } else {
                out.append("\n// Or write straight to a file.\n");
                out.append("db.writeTo(sql, Path.of(\"orders.csv\"), OutputFormat.CSV);\n");
            }
            return out.toString();
        }
    }

    /** The in-memory H2 database: seeded by an admin user, queried by a read-only user. */
    private static final class Database {
        private final String url;
        private final String adminUser;
        private final String adminPassword;
        private final DataSource reader;

        private Database(String url, String adminUser, String adminPassword, DataSource reader) {
            this.url = url;
            this.adminUser = adminUser;
            this.adminPassword = adminPassword;
            this.reader = reader;
        }

        static Database seed() {
            String name = "playground-" + UUID.randomUUID();
            String url = "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1";
            String adminUser = "SEED_ADMIN";
            String readerUser = "PLAYGROUND_READER";
            // Generated per run: nothing credential-like is stored in the source.
            String adminPassword = randomSecret();
            String readerPassword = randomSecret();
            try {
                Class.forName("org.h2.Driver");
                JdbcDataSource admin = dataSource(url, adminUser, adminPassword);
                try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
                    for (String sql : seedStatements()) {
                        statement.execute(sql);
                    }
                    statement.execute("CREATE USER " + readerUser + " PASSWORD '" + readerPassword + "'");
                    statement.execute("GRANT SELECT ON SCHEMA SHOP TO " + readerUser);
                }
            } catch (SQLException | ClassNotFoundException | IOException e) {
                throw new IllegalStateException("Could not seed the playground database", e);
            }
            return new Database(url, adminUser, adminPassword,
                    dataSource("jdbc:h2:mem:" + name + ";SCHEMA=SHOP", readerUser, readerPassword));
        }

        DataSource reader() {
            return reader;
        }

        void shutdown() {
            try (Connection connection = dataSource(url, adminUser, adminPassword).getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (SQLException e) {
                // Already gone; nothing to clean up.
            }
        }

        private static JdbcDataSource dataSource(String url, String user, String password) {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL(url);
            ds.setUser(user);
            ds.setPassword(password);
            return ds;
        }

        private static String randomSecret() {
            byte[] bytes = new byte[18];
            new SecureRandom().nextBytes(bytes);
            return HexFormat.of().formatHex(bytes);
        }

        private static List<String> seedStatements() throws IOException {
            try (InputStream in = Playground.class.getResourceAsStream("/seed.sql")) {
                if (in == null) {
                    throw new IOException("seed.sql is missing from the classpath");
                }
                // The licence header contains semicolons, so drop it before splitting.
                String script = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .replaceFirst("(?s)^\\s*/\\*.*?\\*/", "");
                List<String> statements = new ArrayList<>();
                for (String part : script.split(";[ \\t]*\\r?\\n")) {
                    if (!SqlGuard.blankCommentsAndLiterals(part).isBlank()) {
                        statements.add(part);
                    }
                }
                return statements;
            }
        }
    }
}
