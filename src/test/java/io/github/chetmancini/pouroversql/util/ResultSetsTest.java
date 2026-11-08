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

import io.github.chetmancini.pouroversql.FormatOptions;
import io.github.chetmancini.pouroversql.KeyCase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultSetsTest {

    /** A deterministic multi-row result with per-column metadata and value-read counters. */
    private static final class MultiRowResultSet {
        private final String[] labels;
        private final Object[][] values;
        private final ResultSet rs;
        private final int[] columnLabelCalls;
        private final int[] objectCalls;
        private int cursor = -1;
        private int metaDataCalls;
        private int columnCountCalls;

        private MultiRowResultSet(String[] labels, Object[][] values) {
            this.labels = labels;
            this.values = values;
            this.columnLabelCalls = new int[labels.length];
            this.objectCalls = new int[labels.length];
            ResultSetMetaData meta = (ResultSetMetaData) Proxy.newProxyInstance(
                    ResultSetMetaData.class.getClassLoader(), new Class<?>[] {ResultSetMetaData.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "getColumnCount" -> {
                            columnCountCalls++;
                            yield labels.length;
                        }
                        case "getColumnLabel" -> {
                            int index = (Integer) args[0] - 1;
                            columnLabelCalls[index]++;
                            yield labels[index];
                        }
                        case "getColumnName" -> labels[(Integer) args[0] - 1];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            this.rs = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[] {ResultSet.class}, (p, method, args) -> switch (method.getName()) {
                        case "getMetaData" -> {
                            metaDataCalls++;
                            yield meta;
                        }
                        case "next" -> ++cursor < values.length;
                        case "getObject" -> {
                            int index = (Integer) args[0] - 1;
                            objectCalls[index]++;
                            yield values[cursor][index];
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private boolean next() throws Exception {
            return rs.next();
        }
    }

    /** A one-column ResultSet whose value is a java.sql.Array wrapping {@code javaArray}, as pgjdbc returns. */
    private static ResultSet rowWithArray(Object javaArray) {
        Array sqlArray = (Array) Proxy.newProxyInstance(Array.class.getClassLoader(), new Class<?>[] {Array.class},
                (p, method, args) -> method.getName().equals("getArray") ? javaArray : null);
        return rowWithValue(sqlArray);
    }

    private static ResultSet rowWithValue(Object value) {
        ResultSetMetaData meta = (ResultSetMetaData) Proxy.newProxyInstance(
                ResultSetMetaData.class.getClassLoader(), new Class<?>[] {ResultSetMetaData.class},
                (p, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> 1;
                    case "getColumnLabel", "getColumnName" -> "v";
                    default -> null;
                });
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (p, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> meta;
                    case "getObject" -> value;
                    default -> null;
                });
    }

    @Test
    void rowMappingCachesMetadataAcrossRowsAndKeyReads() throws Exception {
        MultiRowResultSet fixture = new MultiRowResultSet(new String[] {"id", "name"},
                new Object[][] {{1, "a"}, {2, "b"}});
        ResultSets.RowMapping mapping = new ResultSets.RowMapping(fixture.rs, FormatOptions.defaults());

        assertThat(mapping.outputKeys()).containsExactly("id", "name");
        assertThat(mapping.outputKeys()).containsExactly("id", "name");
        assertThat(fixture.next()).isTrue();
        Map<String, Object> first = mapping.mapCurrentRow();
        assertThat(first.keySet()).containsExactly("id", "name");
        assertThat(first.values()).containsExactly(1, "a");
        assertThat(fixture.next()).isTrue();
        Map<String, Object> second = mapping.mapCurrentRow();
        assertThat(second.keySet()).containsExactly("id", "name");
        assertThat(second.values()).containsExactly(2, "b");
        assertThat(fixture.metaDataCalls).isEqualTo(1);
        assertThat(fixture.columnCountCalls).isEqualTo(1);
        assertThat(fixture.columnLabelCalls).containsExactly(1, 1);
    }

    @Test
    void duplicateRawLabelsKeepFirstPositionAndReadEveryColumn() throws Exception {
        MultiRowResultSet fixture = new MultiRowResultSet(new String[] {"v", "other", "v"},
                new Object[][] {{1, "x", 3}});
        ResultSets.RowMapping mapping = new ResultSets.RowMapping(fixture.rs, FormatOptions.defaults());

        assertThat(mapping.outputKeys()).containsExactly("v", "other");
        assertThat(fixture.next()).isTrue();
        Map<String, Object> row = mapping.mapCurrentRow();
        assertThat(row.keySet()).containsExactly("v", "other");
        assertThat(row.values()).containsExactly(3, "x");
        assertThat(fixture.objectCalls).containsExactly(1, 1, 1);

        MultiRowResultSet compatible = new MultiRowResultSet(new String[] {"v", "other", "v"},
                new Object[][] {{1, "x", 3}});
        assertThat(compatible.next()).isTrue();
        Map<String, Object> toMapRow = ResultSets.toMap(compatible.rs);
        assertThat(toMapRow.keySet()).containsExactly("v", "other");
        assertThat(toMapRow.values()).containsExactly(3, "x");
        assertThat(compatible.objectCalls).containsExactly(1, 1, 1);
    }

    @Test
    void formattedCollisionFailsBeforeRowsAreRead() {
        MultiRowResultSet fixture = new MultiRowResultSet(new String[] {"USER_ID", "userId"}, new Object[0][]);

        assertThatThrownBy(() -> new ResultSets.RowMapping(fixture.rs, new FormatOptions(KeyCase.SNAKE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Columns 'USER_ID' and 'userId' both map to key 'user_id' under SNAKE key case");
        assertThat(fixture.objectCalls).containsExactly(0, 0);
    }

    @Test
    void formattedKeysAndNullValuesMatchEachKeyCase() throws Exception {
        assertFormattedNullRow(FormatOptions.defaults(), "FirstName");
        assertFormattedNullRow(new FormatOptions(KeyCase.SNAKE), "first_name");
        assertFormattedNullRow(new FormatOptions(KeyCase.CAMEL), "firstName");
    }

    private static void assertFormattedNullRow(FormatOptions options, String expectedKey) throws Exception {
        MultiRowResultSet fixture = new MultiRowResultSet(new String[] {"FirstName"}, new Object[][] {{null}});
        ResultSets.RowMapping mapping = new ResultSets.RowMapping(fixture.rs, options);

        assertThat(mapping.outputKeys()).containsExactly(expectedKey);
        assertThat(fixture.next()).isTrue();
        Map<String, Object> row = mapping.mapCurrentRow();
        assertThat(row).containsOnlyKeys(expectedKey);
        assertThat(row.get(expectedKey)).isNull();
    }

    @Test
    void directObjectArrayBecomesList() throws Exception {
        Object value = ResultSets.toMap(rowWithValue(new String[] {"a", "b"})).get("v");

        assertThat(value).isEqualTo(List.of("a", "b"));
    }

    @Test
    void directPrimitiveArrayBecomesList() throws Exception {
        Object value = ResultSets.toMap(rowWithValue(new int[] {1, 2})).get("v");

        assertThat(value).isEqualTo(List.of(1, 2));
    }

    @Test
    void directNestedArrayPreservesNestingAndNull() throws Exception {
        Object value = ResultSets.toMap(rowWithValue(new Object[] {new String[] {"a"}, null})).get("v");

        assertThat(value).isEqualTo(Arrays.asList(List.of("a"), null));
    }

    @Test
    void directByteArrayRemainsBase64() throws Exception {
        Object value = ResultSets.toMap(rowWithValue(new byte[] {1, 2, 3})).get("v");

        assertThat(value).isEqualTo("AQID");
    }

    @Test
    void nonArrayDriverObjectUsesToString() throws Exception {
        Object driverValue = new Object() {
            @Override
            public String toString() {
                return "{\"kind\":\"placeholder\"}";
            }
        };

        Object value = ResultSets.toMap(rowWithValue(driverValue)).get("v");

        assertThat(value).isEqualTo("{\"kind\":\"placeholder\"}");
    }

    @Test
    void boxedMultiDimensionalArrayBecomesNestedLists() throws Exception {
        Object value = ResultSets.toMap(rowWithArray(new Integer[][] {{1, 2}, {3, 4}})).get("v");
        assertThat(value).isEqualTo(List.of(List.of(1, 2), List.of(3, 4)));
    }

    @Test
    void primitiveMultiDimensionalArrayBecomesNestedLists() throws Exception {
        Object value = ResultSets.toMap(rowWithArray(new int[][] {{1, 2}, {3}})).get("v");
        assertThat(value).isEqualTo(List.of(List.of(1, 2), List.of(3)));
    }

    @Test
    void threeDimensionalArrayNests() throws Exception {
        Object value = ResultSets.toMap(rowWithArray(new String[][][] {{{"a"}, {"b"}}})).get("v");
        assertThat(value).isEqualTo(List.of(List.of(List.of("a"), List.of("b"))));
    }

    @Test
    void arrayOfByteArraysKeepsEachElementAsBase64() throws Exception {
        Object value = ResultSets.toMap(rowWithArray(new byte[][] {{1}, {2, 3}})).get("v");
        assertThat(value).isEqualTo(List.of("AQ==", "AgM="));
    }

    @Test
    void oneDimensionalPrimitiveArrayStillWorks() throws Exception {
        assertThat(ResultSets.toMap(rowWithArray(new int[] {1, 2})).get("v")).isEqualTo(List.of(1, 2));
    }
}
