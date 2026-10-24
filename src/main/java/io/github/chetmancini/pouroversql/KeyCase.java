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

import io.github.chetmancini.pouroversql.util.TextFormat;

import java.util.Locale;

/**
 * How output keys (column labels) are cased.
 */
public enum KeyCase {
    /** Keep column labels exactly as the driver reports them. */
    DEFAULT,
    /** Convert labels to {@code snake_case}. */
    SNAKE,
    /** Convert labels to {@code camelCase}. */
    CAMEL;

    /**
     * Applies this case to one key.
     * @param key the column label
     * @return the converted key
     */
    public String apply(String key) {
        return switch (this) {
            case DEFAULT -> key;
            case SNAKE -> TextFormat.toSnakeCase(key);
            case CAMEL -> TextFormat.toCamelCase(key);
        };
    }

    /**
     * Lenient parse used by the CLI: anything containing "camel" is {@link #CAMEL}, anything
     * containing "snake" is {@link #SNAKE}, and everything else (including null) is {@link #DEFAULT}.
     * @param name the user-supplied name
     * @return the matching key case
     */
    public static KeyCase fromName(String name) {
        if (name == null) {
            return DEFAULT;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("camel")) {
            return CAMEL;
        }
        if (lower.contains("snake")) {
            return SNAKE;
        }
        return DEFAULT;
    }
}
