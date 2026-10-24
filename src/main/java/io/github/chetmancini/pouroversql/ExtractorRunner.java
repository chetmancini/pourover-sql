/**
 * Copyright 2019-present, Simon Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.chetmancini.pouroversql;

import io.github.chetmancini.pouroversql.clients.SqlEngine;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static io.github.chetmancini.pouroversql.util.TextFormat.parseInteger;

/**
 * <h1>ExtractorRunner</h1>
 * Legacy command-line entry point, minimally ported to the {@link Pourover} API.
 * Replaced by the picocli CLI in Task 6.
 */
public class ExtractorRunner {

    private final static Logger logger = LoggerFactory.getLogger(ExtractorRunner.class);

    private static final String ENV_VAR_PASSWORD_KEY = "EXTRACT_DB_PASSWORD";
    private static final String DEFAULT_OUTPUT_FILENAME = "query_result.json";

    private static Options getOptions() {
        Options options = new Options();
        options.addRequiredOption("u", "user", true, "user");
        options.addOption("h", "host", true, "host");
        options.addOption(
                "p",
                "port",
                true,
                "Port, defaults to engine's default port."
        );
        options.addOption("d", "database", true, "database");
        options.addOption("t", "type", true, "Driver type (SQLServer | MySQL | Postgres )");
        options.addOption("s", "sql", true,
                "SQL file to read.");
        options.addOption("dry", "dry", false, "Dry run");
        options.addOption("format", "format", true, "The output format, defaults to json (JSON | CSV)");
        options.addOption(
                "f",
                "file",
                true,
                "File to write to. Defaults to " + DEFAULT_OUTPUT_FILENAME + '.'
        );
        options.addOption("c", "case", true, "Key case format (DEFAULT | Snake | Camel)");
        options.addOption("fetchsize", "fetchsize", true, "Fetch size");
        options.addOption("timeout", "timeout", true, "Query Timeout in seconds");
        options.addOption("maxrows", "maxrows", true, "Maximum rows");

        Option customParams = Option.builder("custom")
                .longOpt("custom")
                .hasArgs()
                .valueSeparator()
                .argName("property=value")
                .desc("Custom params")
                .numberOfArgs(2)
                .optionalArg(true)
                .build();

        options.addOption(customParams);
        return options;
    }

    private static String getPassword() {
        /*
          Try to get password from environment var.
          If that doesn't exist prompt on console.
         */
        String value = System.getenv(ENV_VAR_PASSWORD_KEY);
        if (value != null) {
            return value;
        } else {
            Console console = System.console();
            return new String(console.readPassword("Password: "));
        }
    }

    private static void configureLogging() {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace");
    }

    private static String readSqlFromFile(String filename) throws IOException {
        logger.debug("Reading " + filename);
        return new String(Files.readAllBytes(Paths.get(filename)), StandardCharsets.UTF_8);
    }

    private static String readSqlFromStdIn() throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        String s;
        while ((s = br.readLine()) != null) {
            sb.append(s);
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

    private static ConnectionParams getConnectionParams(CommandLine commandLine) {
        String user = commandLine.getOptionValue("user");
        String host = commandLine.getOptionValue("host", "localhost");
        Integer port = parseInteger(commandLine.getOptionValue("port"));
        String database = commandLine.getOptionValue("database");
        String password = getPassword();
        Properties props = commandLine.getOptionProperties("custom");
        return new ConnectionParams(host, port, user, password, database, props);
    }

    private static QueryOptions getQueryOptions(CommandLine commandLine) {
        Integer fetchSize = parseInteger(commandLine.getOptionValue("fetchsize"));
        // KNOWN BUG kept for Task 6: the option is registered as "maxrows", so this is always null.
        Integer maxRows = parseInteger(commandLine.getOptionValue("maxRows"));
        Integer timeout = parseInteger(commandLine.getOptionValue("timeout"));
        return QueryOptions.of(
                fetchSize != null ? fetchSize : QueryOptions.DEFAULT_FETCH_SIZE, maxRows, timeout);
    }

    private static FormatOptions getFormatOptions(CommandLine commandLine) {
        return new FormatOptions(KeyCase.fromName(commandLine.getOptionValue("case")));
    }

    public static void main(String[] args) {
        configureLogging();

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine line = parser.parse(getOptions(), args);
            ConnectionParams connectionParams = getConnectionParams(line);
            FormatOptions formatOptions = getFormatOptions(line);
            QueryOptions queryOptions = getQueryOptions(line);
            SqlEngine engine = SqlEngine.byName(line.getOptionValue("type", "SQLSERVER"));
            OutputFormat outputFormat = OutputFormat.fromName(line.getOptionValue("format", "json"));

            if (line.hasOption("dry")) {
                logger.info(connectionParams.toString());
                logger.info(queryOptions.toString());
                logger.info(formatOptions.toString());
                System.exit(0);
            }
            Pourover pourover = Pourover.connect(engine, connectionParams);
            try {
                String inputFilename = line.getOptionValue("sql");
                String inputSql;
                if (inputFilename != null) {
                    inputSql = readSqlFromFile(inputFilename);
                } else {
                    inputSql = readSqlFromStdIn();
                }
                String outputFile = line.getOptionValue("file", DEFAULT_OUTPUT_FILENAME);
                pourover.writeTo(inputSql, Path.of(outputFile), outputFormat, formatOptions, queryOptions);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (ParseException exp) {
            logger.error("Parsing failed.  Reason: " + exp.getMessage());
        }
    }
}
