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
import org.h2.jdbcx.JdbcDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Shows {@code Pourover.list}: run a query and get every row back as a map.
 *
 * <p>Run: {@code ./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.ListExample}
 * or run {@code main} from your IDE.
 */
public final class ListExample {

    private ListExample() {
    }

    public static void main(String[] args) throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:list-example;DB_CLOSE_DELAY=-1");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE beans (\"Name\" VARCHAR(30), \"Origin\" VARCHAR(30), \"Score\" INT)");
            s.execute("INSERT INTO beans VALUES ('Sunrise Blend', 'Ethiopia', 88), ('Dusk Roast', 'Colombia', 84)");
        }

        // snippet:start list
        Pourover db = Pourover.of(dataSource);
        List<Map<String, Object>> rows = db.list("SELECT * FROM beans ORDER BY \"Score\" DESC");
        // snippet:end list

        for (Map<String, Object> row : rows) {
            System.out.println(row);
        }
    }
}
