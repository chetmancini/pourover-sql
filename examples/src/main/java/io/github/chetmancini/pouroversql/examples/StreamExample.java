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

import io.github.chetmancini.pouroversql.Pourover;
import io.github.chetmancini.pouroversql.RowStream;
import org.h2.jdbcx.JdbcDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shows {@code Pourover.stream}: read rows one at a time without loading them all into memory.
 * Close the stream (here with try-with-resources) to release the connection.
 *
 * <p>Run: {@code ./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.StreamExample}
 * or run {@code main} from your IDE.
 */
public final class StreamExample {

    private StreamExample() {
    }

    public static void main(String[] args) throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:stream-example;DB_CLOSE_DELAY=-1");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE numbers AS SELECT X AS \"N\" FROM SYSTEM_RANGE(1, 100000)");
        }

        Pourover db = Pourover.of(dataSource);
        try (RowStream stream = db.stream("SELECT \"N\" FROM numbers")) {
            long evens = stream.rows()
                    .filter(row -> ((Number) row.get("N")).longValue() % 2 == 0)
                    .count();
            System.out.println("Even numbers between 1 and 100000: " + evens);
        }
    }
}
