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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RowFormatterTest {

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    @Test
    void jsonlWritesOneObjectWithoutTrailingNewline() {
        String json = RowFormatter.jsonl(row("a", "abc", "b", 1, "c", null), FormatOptions.defaults());
        assertThat(json).isEqualTo("{\"a\":\"abc\",\"b\":1,\"c\":null}");
    }

    @Test
    void jsonlAppliesKeyCase() {
        Map<String, Object> row = row("FirstName", 1);
        assertThat(RowFormatter.jsonl(row, new FormatOptions(KeyCase.SNAKE))).isEqualTo("{\"first_name\":1}");
        assertThat(RowFormatter.jsonl(row, new FormatOptions(KeyCase.CAMEL))).isEqualTo("{\"firstName\":1}");
    }

    @Test
    void jsonlEscapesNewlinesKeepsUnicodeAndSkipsHtmlEscaping() {
        String json = RowFormatter.jsonl(row("v", "l1\nl2 é中 a=b <c> & 'd'"), FormatOptions.defaults());
        assertThat(json).isEqualTo("{\"v\":\"l1\\nl2 é中 a=b <c> & 'd'\"}");
    }

    @Test
    void jsonlRendersTemporalValuesAsIso8601() {
        Map<String, Object> row = row(
                "ts", java.sql.Timestamp.valueOf("2024-01-02 03:04:05.123"),
                "d", java.sql.Date.valueOf("2024-01-02"),
                "t", java.sql.Time.valueOf("03:04:05"),
                "ldt", LocalDateTime.of(2024, 1, 2, 3, 4),
                "ld", LocalDate.of(2024, 1, 2),
                "odt", OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(-5)),
                "i", Instant.parse("2024-01-02T03:04:05Z"),
                "n", new BigDecimal("1.50"));
        assertThat(RowFormatter.jsonl(row, FormatOptions.defaults())).isEqualTo("{"
                + "\"ts\":\"2024-01-02T03:04:05.123\","
                + "\"d\":\"2024-01-02\","
                + "\"t\":\"03:04:05\","
                + "\"ldt\":\"2024-01-02T03:04:00\","
                + "\"ld\":\"2024-01-02\","
                + "\"odt\":\"2024-01-02T03:04:05-05:00\","
                + "\"i\":\"2024-01-02T03:04:05Z\","
                + "\"n\":1.50}");
    }

    @Test
    void csvHeaderJoinsColumnsAndQuotesSpecialNames() {
        assertThat(RowFormatter.csvHeader(List.of("a", "b"))).isEqualTo("a,b");
        assertThat(RowFormatter.csvHeader(List.of("a,b", "c"))).isEqualTo("\"a,b\",c");
    }

    @Test
    void csvRowQuotesCommasQuotesAndNewlines() {
        List<Object> values = Arrays.asList("abc,def", "\"sarcasm\"", "line1\nline2", "cr\rhere", null, 4, "plain");
        assertThat(RowFormatter.csvRow(values))
                .isEqualTo("\"abc,def\",\"\"\"sarcasm\"\"\",\"line1\nline2\",\"cr\rhere\",,4,plain");
    }

    @Test
    void csvRowQuotesNonStringValues() {
        // e.g. a PGobject json value, an array or a Clob whose toString() needs quoting.
        Object jsonLike = new Object() {
            @Override
            public String toString() {
                return "{\"a\":1,\n\"b\":2}";
            }
        };
        assertThat(RowFormatter.csvRow(Arrays.asList(jsonLike, 5)))
                .isEqualTo("\"{\"\"a\"\":1,\n\"\"b\"\":2}\",5");
    }

    @Test
    void csvRowRendersTemporalValuesAsIso8601() {
        List<Object> values = List.of(java.sql.Timestamp.valueOf("2024-01-02 03:04:05"), LocalDate.of(2024, 1, 2));
        assertThat(RowFormatter.csvRow(values)).isEqualTo("2024-01-02T03:04:05,2024-01-02");
    }

    @Test
    void csvSupportsCustomDelimiter() {
        assertThat(RowFormatter.csvHeader(List.of("a", "b"), "\t")).isEqualTo("a\tb");
        assertThat(RowFormatter.csvRow(List.of("x\ty", 1), "\t")).isEqualTo("\"x\ty\"\t1");
        assertThat(RowFormatter.csvRow(List.of("x,y", 1), "\t")).isEqualTo("x,y\t1");
    }
}
