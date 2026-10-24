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

import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plain-JDBC helpers for reading result sets.
 */
public final class ResultSets {

    private ResultSets() {
    }

    /**
     * Map the current row to a map keyed by column label, in column order.
     * If two columns share a label the later one wins. SQL NULL maps to null.
     *
     * <p>Values are materialized while the row is current, so the map stays usable after the
     * connection closes:
     * <ul>
     *   <li>{@link Array} becomes a {@link List} (elements materialized the same way);</li>
     *   <li>{@link Clob} and {@link SQLXML} become a {@link String};</li>
     *   <li>{@link Blob} and {@code byte[]} become a Base64 string (RFC 4648, no line breaks);</li>
     *   <li>any other value whose class is not from {@code java.*} or {@code javax.*} (for example
     *       a driver's {@code PGobject}) becomes its {@code toString()}, unless it is a
     *       {@link Number}, {@link java.util.Date} or {@link TemporalAccessor};</li>
     *   <li>everything else (strings, numbers, booleans, dates and times) is returned as is.</li>
     * </ul>
     * Array, Clob, SQLXML and Blob locators are freed after reading.
     * @param rs a ResultSet positioned on a row
     * @return the row as an ordered map
     * @throws SQLException on JDBC errors
     */
    public static Map<String, Object> toMap(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        Map<String, Object> row = new LinkedHashMap<>(columns * 2);
        for (int i = 1; i <= columns; i++) {
            row.put(label(meta, i), materialize(rs.getObject(i)));
        }
        return row;
    }

    private static Object materialize(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof Array array) {
            try {
                return toList(array.getArray());
            } finally {
                free(array::free);
            }
        }
        if (value instanceof Clob clob) {
            try {
                long length = clob.length();
                return length == 0 ? "" : clob.getSubString(1, Math.toIntExact(length));
            } finally {
                free(clob::free);
            }
        }
        if (value instanceof SQLXML xml) {
            try {
                return xml.getString();
            } finally {
                free(xml::free);
            }
        }
        if (value instanceof Blob blob) {
            try {
                long length = blob.length();
                return base64(length == 0 ? new byte[0] : blob.getBytes(1, Math.toIntExact(length)));
            } finally {
                free(blob::free);
            }
        }
        if (value instanceof byte[] bytes) {
            return base64(bytes);
        }
        if (value instanceof Number || value instanceof java.util.Date || value instanceof TemporalAccessor
                || isPlatformType(value.getClass())) {
            return value;
        }
        return value.toString();
    }

    private static List<Object> toList(Object array) throws SQLException {
        if (array == null) {
            return null;
        }
        if (!array.getClass().isArray()) {
            List<Object> single = new ArrayList<>(1);
            single.add(materialize(array));
            return single;
        }
        int length = java.lang.reflect.Array.getLength(array);
        List<Object> list = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            Object element = java.lang.reflect.Array.get(array, i);
            // Multi-dimensional arrays (pgjdbc returns Integer[][]) nest; byte[] stays a Base64 leaf.
            boolean nested = element != null && element.getClass().isArray() && !(element instanceof byte[]);
            list.add(nested ? toList(element) : materialize(element));
        }
        return list;
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static boolean isPlatformType(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.");
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    /** Frees a locator; drivers that do not support free() are ignored. */
    private static void free(SqlAction action) {
        try {
            action.run();
        } catch (SQLException | UnsupportedOperationException | AbstractMethodError e) {
            // Best effort: the value has already been read.
        }
    }

    /**
     * The distinct column labels of a result, in column order, using the same naming as
     * {@link #toMap(ResultSet)} (a repeated label appears once, at its first position).
     * @param rs a ResultSet
     * @return the column labels
     * @throws SQLException on JDBC errors
     */
    public static List<String> columnLabels(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Set<String> labels = new LinkedHashSet<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            labels.add(label(meta, i));
        }
        return new ArrayList<>(labels);
    }

    private static String label(ResultSetMetaData meta, int column) throws SQLException {
        String label = meta.getColumnLabel(column);
        if (label == null || label.isEmpty()) {
            label = meta.getColumnName(column);
        }
        return label;
    }
}
