/**
* Copyright 2019-present, Simon Data, Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
 */

package io.github.chetmancini.pouroversql.util;

/**
 * Textformat
 *
 * Utility class for formatting text.
 */
public class TextFormat {

    /**
     * Convert a string to CamelCase
     * Is idempotent
     * @param input string to convert to camelcase.
     * @return string formatted as camelcase.
     */
    public static String toCamelCase(String input) {
        String snake = toSnakeCase(input);
        if (snake == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(snake.length());
        for (String token : snake.split("_")) {
            if (token.isEmpty()) {
                continue;
            }
            result.append(result.length() == 0 ? token : Character.toUpperCase(token.charAt(0)) + token.substring(1));
        }
        return result.toString();
    }

    /**
     * Convert a string to snake case.
     * Slightly more flexible than other libraries that require specific formats.
     * Has the advantage of being idempotent.
     * @param value string to convert to snake case.
     * @return String formatted as snake case.
     */
    public static String toSnakeCase(String value) {
        char underscore = '_';
        if (value == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(value.length() + 10);
        boolean underscoreWritten = true;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch >= 'A') && (ch <= 'Z')) {
                if (!underscoreWritten) {
                    result.append(underscore);
                }
            }
            if (ch == ' ') {
                result.append(underscore);
                underscoreWritten = true;
            } else {
                result.append(Character.toLowerCase(ch));
                underscoreWritten = (ch == underscore);
            }
        }
        return result.toString();
    }

    /**
     * Safely parse a string as an integer
     * @param nullOrValue input string
     * @return Integer representation of the string.
     */
    public static Integer parseInteger(String nullOrValue) {
        if (nullOrValue == null) {
            return null;
        } else {
            return Integer.parseInt(nullOrValue);
        }
    }

}
