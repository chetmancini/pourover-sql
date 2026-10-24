/*
Copyright 2019-present, Simon Data, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at:
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package io.github.chetmancini.pouroversql.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.github.chetmancini.pouroversql.util.TextFormat.toCamelCase;
import static io.github.chetmancini.pouroversql.util.TextFormat.toSnakeCase;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TextFormatTest {

    @Test
    public void testToCamelCase() throws Exception {
        assertEquals("theRainInSpain", toCamelCase("the rain in spain"));
        assertEquals("theRainInSpain", toCamelCase("theRainInSpain"));
        assertEquals("theRainInSpain", toCamelCase("the_rain_in_spain"));
        assertEquals("thisOneHas3Dig1tsInI7", toCamelCase("this_one_has_3_dig1tsInI7"));
    }

    @Test
    public void testToSnakeCase() throws Exception {
        assertEquals("the_rain_in_spain", toSnakeCase("the rain in spain"));
        assertEquals("the_rain_in_spain", toSnakeCase("the_rain_in_spain"));
        assertEquals("the_rain_in_spain", toSnakeCase("theRainInSpain"));
        assertEquals("this_one_has3_dig1ts_in_i7", toSnakeCase("thisOneHas3Dig1tsInI7"));
    }

    @ParameterizedTest(name = "snake({0}) = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            USER_ID           | user_id
            ORDERID           | orderid
            HTTPStatus        | http_status
            Order ID          | order_id
            FirstName         | first_name
            first_name        | first_name
            userId            | user_id
            ORDER_TOTAL_USD   | order_total_usd
            getHTTPResponse   | get_http_response
            user-id           | user_id
            __leading__x__    | leading_x
            a  b              | a_b
            ID                | id
            """)
    void snakeCaseTable(String input, String expected) {
        assertEquals(expected, toSnakeCase(input));
        assertEquals(expected, toSnakeCase(expected), "idempotent");
    }

    @ParameterizedTest(name = "camel({0}) = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            USER_ID           | userId
            ORDERID           | orderid
            HTTPStatus        | httpStatus
            Order ID          | orderId
            first_name        | firstName
            FirstName         | firstName
            userId            | userId
            ORDER_TOTAL_USD   | orderTotalUsd
            getHTTPResponse   | getHttpResponse
            user-id           | userId
            ID                | id
            """)
    void camelCaseTable(String input, String expected) {
        assertEquals(expected, toCamelCase(input));
        assertEquals(expected, toCamelCase(expected), "idempotent");
    }

    @Test
    public void nullAndEmptyPassThrough() {
        assertEquals(null, toSnakeCase(null));
        assertEquals(null, toCamelCase(null));
        assertEquals("", toSnakeCase(""));
        assertEquals("", toCamelCase(""));
    }

}
