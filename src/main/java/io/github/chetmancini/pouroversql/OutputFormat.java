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

import java.util.Locale;

/**
 * File formats supported by {@link Pourover#writeTo}.
 */
public enum OutputFormat {
    /** One JSON object per line (JSON Lines). */
    JSONL,
    /** Comma-separated values with a header row. */
    CSV;

    /**
     * Case-insensitive parse that also accepts {@code json} for {@link #JSONL}.
     * @param name the format name
     * @return the matching format
     * @throws IllegalArgumentException if the name is null or unknown
     */
    public static OutputFormat fromName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Output format is required");
        }
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "json", "jsonl" -> JSONL;
            case "csv" -> CSV;
            default -> throw new IllegalArgumentException("Unknown output format: " + name);
        };
    }
}
