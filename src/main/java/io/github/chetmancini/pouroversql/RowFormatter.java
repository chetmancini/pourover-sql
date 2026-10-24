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
package io.github.chetmancini.pouroversql;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders single rows as JSONL or CSV text. The file writers behind {@link Pourover#writeTo} use
 * these same methods, so in-memory and file output are identical.
 *
 * <p>Conventions shared by both formats:
 * <ul>
 *   <li>{@code java.sql.Timestamp}, {@code Date}, {@code Time} and {@code java.time} values are
 *       rendered as ISO-8601 strings (for example {@code 2024-01-02T03:04:05}).</li>
 *   <li>JSON: SQL NULL is {@code null}; non-ASCII is written raw; HTML characters such as
 *       {@code <}, {@code =} and {@code &} are not escaped.</li>
 *   <li>CSV: a value containing the delimiter, a double quote, {@code \n} or {@code \r} is wrapped
 *       in double quotes with embedded quotes doubled; SQL NULL is an empty field.</li>
 * </ul>
 */
public final class RowFormatter {

    private static final String QUOTE = "\"";
    private static final String DEFAULT_DELIMITER = ",";
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private RowFormatter() {
    }

    /**
     * Renders one row as a single JSON object with no trailing newline, applying the key case.
     * @param row the row keyed by column label
     * @param fo formatting options
     * @return the JSON text
     */
    public static String jsonl(Map<String, Object> row, FormatOptions fo) {
        Map<String, Object> keyed = fo.formatKeys(row);
        Map<String, Object> normalized = new LinkedHashMap<>(keyed.size() * 2);
        keyed.forEach((key, value) -> normalized.put(key, normalize(value)));
        return GSON.toJson(normalized);
    }

    /**
     * Renders a comma-separated header line (no trailing newline).
     * @param columns the column names, in order
     * @return the header line
     */
    public static String csvHeader(List<String> columns) {
        return csvHeader(columns, DEFAULT_DELIMITER);
    }

    /**
     * Renders a header line with a custom delimiter (no trailing newline).
     * @param columns the column names, in order
     * @param delimiter the field delimiter
     * @return the header line
     */
    public static String csvHeader(List<String> columns, CharSequence delimiter) {
        return columns.stream()
                .map(column -> csvField(column, delimiter))
                .collect(Collectors.joining(delimiter));
    }

    /**
     * Renders one comma-separated data line (no trailing newline).
     * @param values the values, in column order; nulls allowed
     * @return the data line
     */
    public static String csvRow(List<Object> values) {
        return csvRow(values, DEFAULT_DELIMITER);
    }

    /**
     * Renders one data line with a custom delimiter (no trailing newline).
     * @param values the values, in column order; nulls allowed
     * @param delimiter the field delimiter
     * @return the data line
     */
    public static String csvRow(List<Object> values, CharSequence delimiter) {
        return values.stream()
                .map(value -> csvField(value, delimiter))
                .collect(Collectors.joining(delimiter));
    }

    private static String csvField(Object value, CharSequence delimiter) {
        Object normalized = normalize(value);
        if (normalized == null) {
            return "";
        }
        String text = normalized.toString();
        if (normalized instanceof String
                && (text.contains(delimiter) || text.contains(QUOTE) || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0)) {
            return QUOTE + text.replace(QUOTE, QUOTE + QUOTE) + QUOTE;
        }
        return text;
    }

    /**
     * Converts temporal values to ISO-8601 strings; other values are returned unchanged.
     */
    private static Object normalize(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(ts.toLocalDateTime());
        }
        if (value instanceof java.sql.Date d) {
            return DateTimeFormatter.ISO_LOCAL_DATE.format(d.toLocalDate());
        }
        if (value instanceof java.sql.Time t) {
            return DateTimeFormatter.ISO_LOCAL_TIME.format(t.toLocalTime());
        }
        if (value instanceof java.util.Date d) {
            return DateTimeFormatter.ISO_INSTANT.format(d.toInstant());
        }
        if (value instanceof TemporalAccessor temporal) {
            return formatTemporal(temporal);
        }
        return value;
    }

    private static String formatTemporal(TemporalAccessor temporal) {
        if (temporal instanceof LocalDateTime) {
            return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(temporal);
        }
        if (temporal instanceof LocalDate) {
            return DateTimeFormatter.ISO_LOCAL_DATE.format(temporal);
        }
        if (temporal instanceof LocalTime) {
            return DateTimeFormatter.ISO_LOCAL_TIME.format(temporal);
        }
        if (temporal instanceof OffsetDateTime) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(temporal);
        }
        if (temporal instanceof ZonedDateTime) {
            return DateTimeFormatter.ISO_ZONED_DATE_TIME.format(temporal);
        }
        if (temporal instanceof OffsetTime) {
            return DateTimeFormatter.ISO_OFFSET_TIME.format(temporal);
        }
        if (temporal instanceof Instant) {
            return DateTimeFormatter.ISO_INSTANT.format(temporal);
        }
        // Year, YearMonth, MonthDay, DayOfWeek, ...: their toString() is ISO-8601 or a plain name.
        return temporal.toString();
    }
}
