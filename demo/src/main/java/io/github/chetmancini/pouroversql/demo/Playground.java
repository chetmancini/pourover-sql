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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    static final int MAX_RESULT_CHARS = 4 * 1024 * 1024;
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
        return start(port, Limits.DEFAULT);
    }

    /** Query limits: the JDBC timeout the driver enforces, and the server-side wall-clock deadline. */
    record Limits(int jdbcTimeoutSeconds, long deadlineMillis) {
        static final Limits DEFAULT = new Limits(QUERY_TIMEOUT_SECONDS, QUERY_TIMEOUT_SECONDS * 1000L + 2000L);
    }

    static Running start(int port, Limits limits) {
        try {
            Database database = Database.seed();
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            server.setExecutor(executor);
            Running running = new Running(server, executor, database, limits);
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
        private final ExecutorService queryExecutor;
        private final Limits limits;
        private final QueryOptions options;
        private final ThreadLocal<QueryRun> currentRun = new ThreadLocal<>();
        private volatile RuntimeException failure;

        private Running(HttpServer server, ExecutorService executor, Database database, Limits limits) {
            this.server = server;
            this.executor = executor;
            this.database = database;
            this.limits = limits;
            this.options = QueryOptions.of(500, MAX_ROWS + 1, limits.jdbcTimeoutSeconds());
            this.queryExecutor = Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "playground-query");
                thread.setDaemon(true);
                return thread;
            });
            this.pourover = Pourover.of(trackQueries(database.reader(), currentRun));
        }

        /** Makes the next query fail with this exception, so tests can reach the unexpected-error path. */
        void failNextQueryForTest(RuntimeException failure) {
            this.failure = failure;
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
            queryExecutor.shutdownNow();
            database.shutdown();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                try {
                    dispatch(exchange);
                } catch (RuntimeException e) {
                    // Unexpected: log it here, tell the caller nothing about it.
                    System.getLogger(Playground.class.getName()).log(System.Logger.Level.ERROR, "Unexpected error", e);
                    try {
                        sendError(exchange, 500, "internal error");
                    } catch (IOException | RuntimeException ignored) {
                        // Response already started or connection gone.
                    }
                }
            }
        }

        private void dispatch(HttpExchange exchange) throws IOException {
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
            QueryRun queryRun = new QueryRun();
            Future<Map<String, Object>> future = queryExecutor.submit(() -> collect(sql.strip(), keyCase, queryRun));
            try {
                sendJson(exchange, 200, future.get(limits.deadlineMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                // The wall-clock limit holds even if the session disabled its own JDBC timeout.
                queryRun.cancel();
                future.cancel(true);
                sendError(exchange, 503, "The query hit the time limit of " + Math.max(1, limits.deadlineMillis() / 1000)
                        + " seconds and was cancelled. Try a smaller query.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                queryRun.cancel();
                future.cancel(true);
                sendError(exchange, 503, "The query was interrupted.");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof QueryException q) {
                    sendError(exchange, 400, driverMessage(q));
                } else if (cause instanceof IllegalStateException ise && ise.getMessage() != null) {
                    sendError(exchange, 400, ise.getMessage());
                } else if (cause instanceof RuntimeException re) {
                    throw re;
                } else if (cause instanceof Error err) {
                    throw err;
                } else {
                    throw new IllegalStateException(cause);
                }
            }
        }

        /** Runs on a query thread: collects at most MAX_ROWS rows and MAX_RESULT_CHARS of output. */
        private Map<String, Object> collect(String sql, KeyCase keyCase, QueryRun queryRun) {
            RuntimeException injected = failure;
            if (injected != null) {
                failure = null;
                throw injected;
            }
            FormatOptions format = new FormatOptions(keyCase);
            ResultBuffer buffer = new ResultBuffer(format);
            currentRun.set(queryRun);
            try {
                pourover.forEach(sql, buffer, options);
            } catch (ResultBuffer.Full full) {
                // Deliberate early stop: the rest of the result is never read.
            } finally {
                currentRun.remove();
            }
            List<String> columns = queryRun.labels();
            Map<String, Object> labelMap = new LinkedHashMap<>();
            columns.forEach(label -> labelMap.put(label, null));
            String header = RowFormatter.csvHeader(new ArrayList<>(format.formatKeys(labelMap).keySet()));
            return Map.of(
                    "jsonl", buffer.jsonl.toString(),
                    "csv", columns.isEmpty() ? "" : header + "\n" + buffer.csv,
                    "javaSnippet", JavaSnippet.render(sql, keyCase),
                    "rowCount", buffer.rows,
                    "truncated", buffer.truncated);
        }

        private static String driverMessage(QueryException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String message = cause.getMessage() != null ? cause.getMessage()
                    : e.getMessage() != null ? e.getMessage() : "The query failed.";
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

    /** Collects rendered rows until the row cap or the character budget is reached. */
    private static final class ResultBuffer implements java.util.function.Consumer<Map<String, Object>> {
        /** Thrown to stop reading once the result is full. */
        static final class Full extends RuntimeException {
            Full() {
                super(null, null, false, false);
            }
        }

        private final FormatOptions format;
        final StringBuilder jsonl = new StringBuilder();
        final StringBuilder csv = new StringBuilder();
        int rows;
        boolean truncated;

        ResultBuffer(FormatOptions format) {
            this.format = format;
        }

        @Override
        public void accept(Map<String, Object> row) {
            // Check the raw size first so one huge value is never rendered into more strings.
            long estimate = 0;
            for (Object value : row.values()) {
                estimate += value instanceof CharSequence text ? text.length() : 24;
            }
            if (rows >= MAX_ROWS || (long) jsonl.length() + csv.length() + 2 * estimate > MAX_RESULT_CHARS) {
                truncated = true;
                throw new Full();
            }
            jsonl.append(RowFormatter.jsonl(row, format)).append('\n');
            csv.append(RowFormatter.csvRow(new ArrayList<>(format.formatKeys(row).values()))).append('\n');
            rows++;
        }
    }

    /** What one query has handed out, so the server can cancel it and read its column labels. */
    private static final class QueryRun {
        private volatile Statement statement;
        private volatile List<String> labels = List.of();

        List<String> labels() {
            return labels;
        }

        void cancel() {
            Statement current = statement;
            if (current != null) {
                try {
                    current.cancel();
                } catch (SQLException e) {
                    // Already finished or closed.
                }
            }
        }
    }

    /** Wraps the data source so each query's statement can be cancelled and its columns read. */
    private static DataSource trackQueries(DataSource raw, ThreadLocal<QueryRun> current) {
        return (DataSource) Proxy.newProxyInstance(Playground.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    Object result = call(raw, method, args);
                    QueryRun run = current.get();
                    if (run != null && method.getName().equals("getConnection") && result instanceof Connection c) {
                        return Proxy.newProxyInstance(Playground.class.getClassLoader(),
                                new Class<?>[]{Connection.class}, (p, m, a) -> {
                                    Object r = call(c, m, a);
                                    if (m.getName().equals("prepareStatement") && r instanceof PreparedStatement ps) {
                                        run.statement = ps;
                                        return Proxy.newProxyInstance(Playground.class.getClassLoader(),
                                                new Class<?>[]{PreparedStatement.class}, (q, sm, sa) -> {
                                                    Object sr = call(ps, sm, sa);
                                                    if (sm.getName().equals("executeQuery") && sr instanceof ResultSet rs) {
                                                        ResultSetMetaData md = rs.getMetaData();
                                                        List<String> labels = new ArrayList<>();
                                                        for (int i = 1; i <= md.getColumnCount(); i++) {
                                                            labels.add(md.getColumnLabel(i));
                                                        }
                                                        run.labels = labels;
                                                    }
                                                    return sr;
                                                });
                                    }
                                    return r;
                                });
                    }
                    return result;
                });
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
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
            String clean;
            try {
                clean = blankLiterals(sql).strip();
            } catch (UnsupportedSyntaxException e) {
                return e.getMessage();
            }
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

        /** Thrown for text this scanner cannot classify with the same result as H2's own lexer. */
        static final class UnsupportedSyntaxException extends RuntimeException {
            UnsupportedSyntaxException(String message) {
                super(message);
            }
        }

        /**
         * Blanks the inside of '...' literals and keeps "..." identifiers. Comments (which H2 nests),
         * dollar-quoted strings, // and other forms this scanner does not model are rejected, as is any
         * unterminated quote, rather than guessed at: a scanner that disagrees with H2 about where a literal ends can be used to
         * hide a second statement or a forbidden function.
         */
        static String blankLiterals(String sql) {
            StringBuilder out = new StringBuilder(sql.length());
            int i = 0;
            int n = sql.length();
            while (i < n) {
                char c = sql.charAt(i);
                char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
                if (c == '-' && next == '-' || c == '/' && next == '*') {
                    throw new UnsupportedSyntaxException("Comments are not supported in the playground.");
                } else if (c == '/' && next == '/') {
                    throw new UnsupportedSyntaxException("Unsupported syntax: // comments are not supported.");
                } else if (c == '$' && next == '$') {
                    throw new UnsupportedSyntaxException("Unsupported syntax: $$ quoting is not supported.");
                } else if (c == '`' || c == '\\' || c == '\0') {
                    throw new UnsupportedSyntaxException("Unsupported syntax: the character "
                            + (c == '\0' ? "NUL" : String.valueOf(c)) + " is not supported.");
                } else if (c == '\'') {
                    out.append("''");
                    i = endOfQuoted(sql, i, '\'') + 1;
                } else if (c == '"') {
                    int end = endOfQuoted(sql, i, '"');
                    out.append(sql, i, end + 1);
                    i = end + 1;
                } else {
                    out.append(c);
                    i++;
                }
            }
            return out.toString();
        }

        /** Index of the closing quote of the literal opening at {@code start}; doubled quotes are escapes. */
        private static int endOfQuoted(String sql, int start, char quote) {
            int n = sql.length();
            int i = start + 1;
            while (i < n) {
                if (sql.charAt(i) == quote) {
                    if (i + 1 < n && sql.charAt(i + 1) == quote) {
                        i += 2;
                        continue;
                    }
                    return i;
                }
                i++;
            }
            throw new UnsupportedSyntaxException("Unsupported syntax: unterminated " + quote + " quote.");
        }
    }

    /** Builds the compilable Java snippet shown in the UI. */
    static final class JavaSnippet {
        private JavaSnippet() {
        }

        /** Escapes backslashes and the third quote of every run of quotes, so no run can close the block. */
        static String escapeTextBlock(String text) {
            StringBuilder out = new StringBuilder(text.length() + 8);
            int run = 0;
            for (char c : text.toCharArray()) {
                if (c == '\\') {
                    out.append("\\\\");
                    run = 0;
                } else if (c == '"') {
                    if (++run == 3) {
                        out.append("\\\"");
                        run = 0;
                    } else {
                        out.append(c);
                    }
                } else {
                    out.append(c);
                    run = 0;
                }
            }
            return out.toString();
        }

        static String render(String sql, KeyCase keyCase) {
            String body = sql.strip();
            while (body.endsWith(";")) {
                body = body.substring(0, body.length() - 1).stripTrailing();
            }
            String escaped = escapeTextBlock(body.replace("\r", ""));
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
                // The licence header and the -- notes may contain semicolons, so drop them before splitting.
                String script = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .replaceFirst("(?s)^\\s*/\\*.*?\\*/", "")
                        .replaceAll("(?m)^--.*$", "");
                List<String> statements = new ArrayList<>();
                for (String part : script.split(";[ \\t]*\\r?\\n")) {
                    if (!part.isBlank()) {
                        statements.add(part);
                    }
                }
                return statements;
            }
        }
    }
}
