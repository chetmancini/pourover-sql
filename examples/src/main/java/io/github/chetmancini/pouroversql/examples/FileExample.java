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

package io.github.chetmancini.pouroversql.examples;

import io.github.chetmancini.pouroversql.FormatOptions;
import io.github.chetmancini.pouroversql.KeyCase;
import io.github.chetmancini.pouroversql.OutputFormat;
import io.github.chetmancini.pouroversql.Pourover;
import org.h2.jdbcx.JdbcDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shows {@code Pourover.writeTo}: write a query result to a JSONL file and a CSV file, with
 * snake_case keys. The files go to a temporary directory and are printed, then removed.
 *
 * <p>Run: {@code ./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.FileExample}
 * or run {@code main} from your IDE.
 */
public final class FileExample {

    private FileExample() {
    }

    public static void main(String[] args) throws SQLException, IOException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:file-example;DB_CLOSE_DELAY=-1");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE cups (\"CupId\" INT, \"BrewMethod\" VARCHAR(30), \"TastingNote\" VARCHAR(60))");
            s.execute("INSERT INTO cups VALUES (1, 'Pour-over', 'Blueberry, jasmine'), (2, 'French press', NULL)");
        }

        Pourover db = Pourover.of(dataSource);
        FormatOptions snake = new FormatOptions(KeyCase.SNAKE);
        Path dir = Files.createTempDirectory("pourover-example");
        try {
            Path jsonl = db.writeTo("SELECT * FROM cups", dir.resolve("cups.jsonl"), OutputFormat.JSONL, snake);
            Path csv = db.writeTo("SELECT * FROM cups", dir.resolve("cups.csv"), OutputFormat.CSV, snake);
            System.out.println("--- cups.jsonl");
            System.out.print(Files.readString(jsonl));
            System.out.println("--- cups.csv");
            System.out.print(Files.readString(csv));
        } finally {
            Files.deleteIfExists(dir.resolve("cups.jsonl"));
            Files.deleteIfExists(dir.resolve("cups.csv"));
            Files.deleteIfExists(dir);
        }
    }
}
