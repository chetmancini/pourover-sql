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
import io.github.chetmancini.pouroversql.FormatOptions;
import io.github.chetmancini.pouroversql.KeyCase;
import io.github.chetmancini.pouroversql.OutputFormat;
import io.github.chetmancini.pouroversql.Pourover;
import io.github.chetmancini.pouroversql.QueryException;
import io.github.chetmancini.pouroversql.QueryOptions;
import io.github.chetmancini.pouroversql.clients.SqlEngine;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Command-line entry point: runs a SQL query and writes the rows to a file.
 *
 * <p>Exit codes: 0 success, 1 query failure, 2 usage error.
 */
@Command(
        name = "pourover",
        description = "Runs a SQL query (from --sql or stdin) and writes the result to a file.",
        versionProvider = PouroverCommand.ManifestVersion.class,
        sortOptions = false)
public class PouroverCommand implements java.util.concurrent.Callable<Integer> {

    static final String ENV_VAR_PASSWORD_KEY = "EXTRACT_DB_PASSWORD";
    static final String DEFAULT_OUTPUT_FILENAME = "query_result.json";
    static final String DEFAULT_ENGINE = "SQLSERVER";

    static final int EXIT_OK = 0;
    static final int EXIT_QUERY_FAILURE = 1;
    static final int EXIT_USAGE = 2;

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-u", "--user"}, required = true, description = "Database user.")
    String user;

    @Option(names = {"-h", "--host"}, defaultValue = "localhost", description = "Host. Default: ${DEFAULT-VALUE}.")
    String host;

    @Option(names = {"-p", "--port"}, description = "Port, defaults to the engine's default port.")
    Integer port;

    @Option(names = {"-d", "--database"}, description = "Database.")
    String database;

    @Option(names = {"-t", "--type"}, description = "Driver type (SQLServer | MySQL | Postgres ...). Default: " + DEFAULT_ENGINE + ".")
    String type;

    @Option(names = {"-s", "--sql"}, description = "SQL file to read. Reads stdin when absent.")
    String sqlFile;

    @Option(names = {"--dry", "-dry"}, description = "Dry run: print the parameters and exit.")
    boolean dry;

    @Option(names = {"--format", "-format"}, defaultValue = "json", description = "Output format (JSON | CSV). Default: ${DEFAULT-VALUE}.")
    String format;

    @Option(names = {"-f", "--file"}, defaultValue = DEFAULT_OUTPUT_FILENAME, description = "File to write to. Default: ${DEFAULT-VALUE}.")
    String file;

    @Option(names = {"-c", "--case"}, description = "Key case format (DEFAULT | Snake | Camel).")
    String keyCase;

    @Option(names = {"--fetchsize", "-fetchsize"}, description = "Fetch size.")
    Integer fetchSize;

    @Option(names = {"--timeout", "-timeout"}, description = "Query timeout in seconds.")
    Integer timeout;

    @Option(names = {"--maxrows", "-maxrows"}, description = "Maximum rows.")
    Integer maxRows;

    @Option(names = {"--custom", "-custom"}, paramLabel = "<property=value>", description = "Custom engine property; repeatable.")
    Map<String, String> custom = new LinkedHashMap<>();

    @Option(names = "--help", usageHelp = true, description = "Show this help and exit.")
    boolean help;

    @Option(names = "--version", versionHelp = true, description = "Print the version and exit.")
    boolean version;

    private final Function<String, String> env;
    private final Supplier<char[]> console;
    private final InputStream stdin;
    private final BiFunction<SqlEngine, ConnectionParams, Pourover> factory;

    /** Wires the real environment, console, stdin and engine clients. */
    public PouroverCommand() {
        this(System::getenv, PouroverCommand::readPasswordFromConsole, System.in, Pourover::connect);
    }

    /**
     * Creates a command with injected collaborators.
     *
     * @param env looks up an environment variable, returning null when unset
     * @param console prompts for a password, returning null when there is no console
     * @param stdin where SQL is read from when --sql is absent
     * @param factory creates a {@link Pourover} for an engine and its connection parameters
     */
    public PouroverCommand(
            Function<String, String> env,
            Supplier<char[]> console,
            InputStream stdin,
            BiFunction<SqlEngine, ConnectionParams, Pourover> factory) {
        this.env = env;
        this.console = console;
        this.stdin = stdin;
        this.factory = factory;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new PouroverCommand()).execute(args));
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        SqlEngine engine = SqlEngine.byName(type == null ? DEFAULT_ENGINE : type);
        if (engine == null) {
            return usageError(err, "Unknown --type: " + type);
        }
        OutputFormat outputFormat;
        try {
            outputFormat = OutputFormat.fromName(format);
        } catch (IllegalArgumentException e) {
            return usageError(err, "Unknown --format: " + format + " (expected JSON or CSV)");
        }
        if (keyCase != null && !isKnownCase(keyCase)) {
            return usageError(err, "Unknown --case: " + keyCase + " (expected DEFAULT, Snake or Camel)");
        }
        QueryOptions queryOptions;
        try {
            queryOptions = QueryOptions.of(
                    fetchSize != null ? fetchSize : QueryOptions.DEFAULT_FETCH_SIZE, maxRows, timeout);
        } catch (IllegalArgumentException e) {
            return usageError(err, e.getMessage());
        }
        FormatOptions formatOptions = new FormatOptions(KeyCase.fromName(keyCase));

        String password = env.apply(ENV_VAR_PASSWORD_KEY);
        if (password == null) {
            char[] typed = console.get();
            if (typed != null) {
                password = new String(typed);
            } else if (!dry) {
                return usageError(err, "No password available: set " + ENV_VAR_PASSWORD_KEY
                        + " or run in an interactive terminal");
            }
        }
        Properties props = new Properties();
        props.putAll(custom);
        ConnectionParams params = new ConnectionParams(host, port, user, password, database, props);

        if (dry) {
            out.println(params);
            out.println(queryOptions);
            out.println(formatOptions);
            out.flush();
            return EXIT_OK;
        }

        String sql;
        try {
            sql = sqlFile != null ? Files.readString(Path.of(sqlFile), StandardCharsets.UTF_8) : readStdin();
        } catch (IOException | RuntimeException e) {
            return usageError(err, "Could not read SQL: " + e.getMessage());
        }

        try {
            Pourover pourover = factory.apply(engine, params);
            pourover.writeTo(sql, Path.of(file), outputFormat, formatOptions, queryOptions);
            return EXIT_OK;
        } catch (QueryException e) {
            err.println("Query failed: " + e.getMessage());
            err.flush();
            return EXIT_QUERY_FAILURE;
        } catch (UncheckedIOException e) {
            err.println("Could not write " + file + ": " + e.getMessage());
            err.flush();
            return EXIT_QUERY_FAILURE;
        } catch (IllegalArgumentException e) {
            return usageError(err, e.getMessage());
        }
    }

    private static int usageError(PrintWriter err, String message) {
        err.println("Error: " + message);
        err.flush();
        return EXIT_USAGE;
    }

    private static boolean isKnownCase(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("default") || lower.contains("snake") || lower.contains("camel");
    }

    private String readStdin() throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append(System.lineSeparator());
        }
        return sb.toString();
    }

    private static char[] readPasswordFromConsole() {
        java.io.Console console = System.console();
        return console == null ? null : console.readPassword("Password: ");
    }

    /** Reads the version from the jar manifest, falling back to "unknown". */
    static final class ManifestVersion implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String v = PouroverCommand.class.getPackage().getImplementationVersion();
            return new String[] {v == null ? "unknown" : v};
        }
    }
}
