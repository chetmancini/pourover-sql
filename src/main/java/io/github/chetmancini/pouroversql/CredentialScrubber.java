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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Collects configured credentials and removes them from Pour-Over's own diagnostics. */
final class CredentialScrubber {

    static final String REDACTED = "[REDACTED]";

    private static final CredentialScrubber EMPTY = new CredentialScrubber(List.of());
    private static final Pattern URL_PROPERTY = Pattern.compile("[?;&]([^?;&=]+)=([^;&]*)");

    private final List<String> secrets;

    private CredentialScrubber(Collection<String> secrets) {
        List<String> sorted = new ArrayList<>(secrets);
        sorted.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        this.secrets = sorted;
    }

    static CredentialScrubber from(ConnectionParams params) {
        Set<String> secrets = new LinkedHashSet<>();
        addSecret(secrets, params.password());
        Properties properties = params.properties();
        for (String name : properties.stringPropertyNames()) {
            if (isSecretKey(name)) {
                addSecret(secrets, properties.getProperty(name));
            }
        }
        String host = params.host();
        if (host != null && host.startsWith("jdbc:")) {
            Matcher matcher = URL_PROPERTY.matcher(host);
            while (matcher.find()) {
                if (isSecretKey(matcher.group(1))) {
                    addSecret(secrets, matcher.group(2));
                }
            }
        }
        return new CredentialScrubber(secrets);
    }

    static CredentialScrubber empty() {
        return EMPTY;
    }

    static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("token") || lower.contains("secret")
                || lower.contains("passphrase") || lower.contains("credential");
    }

    static String scrubJdbcUrl(String url) {
        if (url == null || !url.startsWith("jdbc:")) {
            return url;
        }
        Matcher matcher = URL_PROPERTY.matcher(url);
        StringBuilder result = null;
        int copiedThrough = 0;
        while (matcher.find()) {
            if (!isSecretKey(matcher.group(1))) {
                continue;
            }
            if (result == null) {
                result = new StringBuilder(url.length());
            }
            result.append(url, copiedThrough, matcher.start(2)).append(REDACTED);
            copiedThrough = matcher.end(2);
        }
        if (result == null) {
            return url;
        }
        return result.append(url, copiedThrough, url.length()).toString();
    }

    String scrub(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (String secret : secrets) {
            result = result.replace(secret, REDACTED);
        }
        return result;
    }

    private static void addSecret(Set<String> secrets, String value) {
        if (value != null && !value.isBlank()) {
            secrets.add(value);
        }
    }
}
