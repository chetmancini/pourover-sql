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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Output formatting settings.
 *
 * @param keyCase how keys are cased; null means {@link KeyCase#DEFAULT}
 */
public record FormatOptions(KeyCase keyCase) {

    /**
     * Normalizes a null key case to {@link KeyCase#DEFAULT}.
     */
    public FormatOptions {
        if (keyCase == null) {
            keyCase = KeyCase.DEFAULT;
        }
    }

    /**
     * Default formatting: keys unchanged.
     * @return the default options
     */
    public static FormatOptions defaults() {
        return new FormatOptions(KeyCase.DEFAULT);
    }

    /**
     * Returns the row with its keys converted by {@link #keyCase()}, preserving column order.
     * With {@link KeyCase#DEFAULT} the same map is returned.
     * @param row a row keyed by column label
     * @return the row with converted keys
     */
    public Map<String, Object> formatKeys(Map<String, Object> row) {
        if (keyCase == KeyCase.DEFAULT) {
            return row;
        }
        Map<String, Object> result = new LinkedHashMap<>(row.size() * 2);
        row.forEach((key, value) -> result.put(keyCase.apply(key), value));
        return result;
    }
}
