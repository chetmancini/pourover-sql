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

package io.github.chetmancini.pouroversql.util;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plain-JDBC helpers for reading result sets.
 */
public final class ResultSets {

    private ResultSets() {
    }

    /**
     * Map the current row to a map keyed by column label, in column order.
     * If two columns share a label the later one wins. SQL NULL maps to null.
     * @param rs a ResultSet positioned on a row
     * @return the row as an ordered map
     * @throws SQLException on JDBC errors
     */
    public static Map<String, Object> toMap(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        Map<String, Object> row = new LinkedHashMap<>(columns * 2);
        for (int i = 1; i <= columns; i++) {
            String label = meta.getColumnLabel(i);
            if (label == null || label.isEmpty()) {
                label = meta.getColumnName(i);
            }
            row.put(label, rs.getObject(i));
        }
        return row;
    }
}
