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

/**
 * Per-query execution settings.
 *
 * @param fetchSize rows the driver fetches per round trip; must be positive
 * @param maxRows maximum rows to return, or null for no limit; must be positive when set
 * @param timeoutSeconds query timeout in seconds, or null for none; must be positive when set
 * @param logFrequency log progress every this many rows; must be positive
 */
public record QueryOptions(int fetchSize, Integer maxRows, Integer timeoutSeconds, int logFrequency) {

    /** Default rows fetched per round trip. */
    public static final int DEFAULT_FETCH_SIZE = 10_000;

    /** Default progress-logging interval in rows. */
    public static final int DEFAULT_LOG_FREQUENCY = 100_000;

    /**
     * Validates that every value is positive.
     * @throws IllegalArgumentException if a value is zero or negative
     */
    public QueryOptions {
        requirePositive("fetchSize", fetchSize);
        if (maxRows != null) {
            requirePositive("maxRows", maxRows);
        }
        if (timeoutSeconds != null) {
            requirePositive("timeoutSeconds", timeoutSeconds);
        }
        requirePositive("logFrequency", logFrequency);
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
    }

    /**
     * Default options: fetch size 10,000, no row limit, no timeout, log every 100,000 rows.
     * @return the default options
     */
    public static QueryOptions defaults() {
        return new QueryOptions(DEFAULT_FETCH_SIZE, null, null, DEFAULT_LOG_FREQUENCY);
    }

    /**
     * Options with the default log frequency.
     * @param fetchSize rows fetched per round trip
     * @param maxRows maximum rows, or null for no limit
     * @param timeoutSeconds timeout in seconds, or null for none
     * @return the options
     */
    public static QueryOptions of(int fetchSize, Integer maxRows, Integer timeoutSeconds) {
        return new QueryOptions(fetchSize, maxRows, timeoutSeconds, DEFAULT_LOG_FREQUENCY);
    }
}
