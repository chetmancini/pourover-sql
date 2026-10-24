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
package io.github.chetmancini.pouroversql.cli;

import io.github.chetmancini.pouroversql.ConnectionParams;
import io.github.chetmancini.pouroversql.Pourover;
import io.github.chetmancini.pouroversql.clients.SqlEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class PouroverCommandTest {

    @TempDir
    Path dir;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private final String password = "pw-" + UUID.randomUUID();
    private DataSource ds;
    private String url;
    private ConnectionParams seenParams;
    private SqlEngine seenEngine;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.h2.Driver");
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ds = (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        return DriverManager.getConnection(url);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE users (id INT, first_name VARCHAR(20))");
            s.execute("INSERT INTO users VALUES (1, 'ann'), (2, 'bob'), (3, 'cy')");
        }
    }

    private int run(String stdin, String envPassword, String... args) {
        return run(stdin, envPassword, () -> null, args);
    }

    private int run(String stdin, String envPassword, Supplier<char[]> console, String... args) {
        InputStream in = new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
        BiFunction<SqlEngine, ConnectionParams, Pourover> factory = (engine, params) -> {
            seenEngine = engine;
            seenParams = params;
            return Pourover.of(ds);
        };
        PouroverCommand command = new PouroverCommand(
                name -> PouroverCommand.ENV_VAR_PASSWORD_KEY.equals(name) ? envPassword : null,
                console, in, factory);
        CommandLine cmd = new CommandLine(command);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        return cmd.execute(args);
    }

    private String file() {
        return dir.resolve("out.txt").toString();
    }

    private List<String> lines() throws Exception {
        return Files.readAllLines(dir.resolve("out.txt"));
    }

    @Test
    void maxrowsFlagIsHonored() throws Exception {
        int code = run("SELECT * FROM users ORDER BY id", password,
                "-u", "me", "-f", file(), "--maxrows", "2");
        assertThat(code).isZero();
        assertThat(lines()).hasSize(2);
    }

    @Test
    void badSqlExitsOneWithoutPassword() {
        int code = run("SELECT * FROM missing_table", password, "-u", "me", "-f", file());
        assertThat(code).isEqualTo(1);
        assertThat(err.toString()).isNotBlank().doesNotContain(password).doesNotContain("\tat ");
        assertThat(out.toString()).doesNotContain(password);
        // The prefix appears once (QueryException's message already starts with it).
        assertThat(err.toString()).startsWith("Query failed").doesNotContain("Query failed: Query failed");
        assertThat(err.toString().split("Query failed", -1)).hasSize(2);
    }

    @Test
    void unexpectedRuntimeExceptionExitsOneWithoutStackTrace() {
        // Two labels that collide under snake case raise IllegalStateException inside writeTo.
        int code = run("SELECT id AS \"userId\", first_name AS \"user_id\" FROM users", password,
                "-u", "me", "-f", file(), "--case", "snake");
        assertThat(code).isEqualTo(1);
        assertThat(err.toString()).startsWith("Error: ").contains("user_id")
                .doesNotContain("\tat ").doesNotContain("Exception");
    }

    @Test
    void dryRunNeverPromptsForPassword() {
        Supplier<char[]> failingConsole = () -> {
            throw new AssertionError("--dry must not prompt for a password");
        };
        int code = run("", null, failingConsole, "--dry", "-u", "me");
        assertThat(code).isZero();
        assertThat(out.toString()).contains("password=null");
        assertThat(err.toString()).isEmpty();

        out.getBuffer().setLength(0);
        code = run("", password, failingConsole, "--dry", "-u", "me");
        assertThat(code).isZero();
        assertThat(out.toString()).contains("password=[REDACTED]").doesNotContain(password);
    }

    @Test
    void noConsoleAndNoEnvPasswordExitsTwoWithMessage() {
        int code = run("SELECT 1", null, "-u", "me", "-f", file());
        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("No password available").contains("EXTRACT_DB_PASSWORD");
    }

    @Test
    void dryRunPrintsParamsWithRedactedPassword() {
        int code = run("", password, "--dry", "-u", "me", "-h", "db.example", "-d", "main",
                "--maxrows", "5", "--custom", "k=v");
        assertThat(code).isZero();
        assertThat(out.toString())
                .contains("host=db.example", "user=me", "database=main", "[REDACTED]", "k=v", "maxRows=5")
                .doesNotContain(password);
        assertThat(err.toString()).doesNotContain(password);
        assertThat(seenParams).isNull();
        assertThat(dir.resolve("out.txt")).doesNotExist();
    }

    @Test
    void zeroTimeoutIsUsageError() {
        int code = run("SELECT 1", password, "-u", "me", "--timeout", "0");
        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("timeoutSeconds");
    }

    @Test
    void unknownTypeIsUsageError() {
        int code = run("SELECT 1", password, "-u", "me", "-t", "nosuchdb");
        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("Unknown --type");
    }

    @Test
    void unknownFormatAndCaseAreUsageErrors() {
        assertThat(run("SELECT 1", password, "-u", "me", "--format", "xml")).isEqualTo(2);
        assertThat(run("SELECT 1", password, "-u", "me", "--case", "kebab")).isEqualTo(2);
    }

    @Test
    void missingUserIsUsageError() {
        assertThat(run("SELECT 1", password)).isEqualTo(2);
    }

    @Test
    void csvFormatWritesCsvFile() throws Exception {
        int code = run("SELECT id, first_name FROM users ORDER BY id", password,
                "-u", "me", "-f", file(), "--format", "csv");
        assertThat(code).isZero();
        assertThat(lines()).hasSize(4);
        assertThat(lines().get(0)).isEqualToIgnoringCase("id,first_name");
    }

    @Test
    void sqlIsReadFromStdinAndEngineDefaultsToSqlServer() throws Exception {
        int code = run("SELECT id FROM users WHERE id = 3", password, "-u", "me", "-f", file());
        assertThat(code).isZero();
        assertThat(lines()).hasSize(1);
        assertThat(lines().get(0)).contains("3");
        assertThat(seenEngine).isEqualTo(SqlEngine.SQLSERVER);
        assertThat(seenParams.host()).isEqualTo("localhost");
    }

    @Test
    void sqlIsReadFromFileWhenGiven() throws Exception {
        Path sql = dir.resolve("q.sql");
        Files.writeString(sql, "SELECT id FROM users WHERE id = 1");
        int code = run("SELECT 999", password, "-u", "me", "-f", file(), "-s", sql.toString());
        assertThat(code).isZero();
        assertThat(lines()).hasSize(1);
    }

    @Test
    void snakeCaseConvertsKeys() throws Exception {
        int code = run("SELECT first_name AS \"firstName\" FROM users WHERE id = 1", password,
                "-u", "me", "-f", file(), "--case", "snake");
        assertThat(code).isZero();
        assertThat(lines().get(0)).contains("first_name").doesNotContain("firstName");
    }
}
