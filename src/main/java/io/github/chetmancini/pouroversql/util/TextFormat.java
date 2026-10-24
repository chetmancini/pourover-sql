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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Textformat
 *
 * Utility class for formatting text.
 */
public class TextFormat {

    /**
     * Convert a string to camelCase: the first word lower case, later words capitalized.
     * Words are split as described in {@link #toSnakeCase(String)}. Idempotent.
     * @param input string to convert to camelcase.
     * @return string formatted as camelcase, or null for null input.
     */
    public static String toCamelCase(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(input.length());
        for (String word : words(input)) {
            String lower = word.toLowerCase(Locale.ROOT);
            if (result.length() == 0) {
                result.append(lower);
            } else {
                result.appendCodePoint(Character.toUpperCase(lower.codePointAt(0)))
                        .append(lower, Character.charCount(lower.codePointAt(0)), lower.length());
            }
        }
        return result.toString();
    }

    /**
     * Convert a string to snake_case: lower-case words joined by underscores. Idempotent.
     *
     * <p>Words are separated by {@code _}, {@code -} and whitespace (repeated separators collapse,
     * leading and trailing ones are dropped), and within a word a new word starts at an upper-case
     * letter that follows a lower-case letter or digit ({@code userId}, {@code has3Dig}). A run of
     * capitals stays one word ({@code USER_ID} gives {@code user_id}, {@code ORDERID} gives
     * {@code orderid}); when the run is followed by a lower-case letter its last capital starts
     * the next word ({@code HTTPStatus} gives {@code http_status}). Digits never start a word.
     * @param value string to convert to snake case.
     * @return String formatted as snake case, or null for null input.
     */
    public static String toSnakeCase(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (String word : words(value)) {
            if (result.length() > 0) {
                result.append('_');
            }
            result.append(word.toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    private static boolean isSeparator(char ch) {
        return ch == '_' || ch == '-' || Character.isWhitespace(ch);
    }

    /** Splits an identifier into words, keeping the original letter case. */
    static List<String> words(String input) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int length = input.length();
        for (int i = 0; i < length; i++) {
            char ch = input.charAt(i);
            if (isSeparator(ch)) {
                flush(current, words);
                continue;
            }
            if (Character.isUpperCase(ch) && current.length() > 0) {
                char prev = current.charAt(current.length() - 1);
                boolean afterLowerOrDigit = Character.isLowerCase(prev) || Character.isDigit(prev);
                boolean endsCapitalRun = Character.isUpperCase(prev)
                        && i + 1 < length && Character.isLowerCase(input.charAt(i + 1));
                if (afterLowerOrDigit || endsCapitalRun) {
                    flush(current, words);
                }
            }
            current.append(ch);
        }
        flush(current, words);
        return words;
    }

    private static void flush(StringBuilder current, List<String> words) {
        if (current.length() > 0) {
            words.add(current.toString());
            current.setLength(0);
        }
    }

}
