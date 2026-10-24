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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultSetsTest {

    /** A one-column ResultSet whose value is a java.sql.Array wrapping {@code javaArray}, as pgjdbc returns. */
    private static ResultSet rowWithArray(Object javaArray) {
        Array sqlArray = (Array) Proxy.newProxyInstance(Array.class.getClassLoader(), new Class<?>[] {Array.class},
                (p, method, args) -> method.getName().equals("getArray") ? javaArray : null);
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
                    case "getObject" -> sqlArray;
                    default -> null;
                });
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
