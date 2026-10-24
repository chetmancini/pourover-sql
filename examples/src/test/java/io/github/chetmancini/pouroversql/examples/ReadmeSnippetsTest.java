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
package io.github.chetmancini.pouroversql.examples;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps README.md honest: every region in the example sources marked with
 * {@code snippet:start <name>} and {@code snippet:end <name>} must appear verbatim in the README.
 */
class ReadmeSnippetsTest {

    private static final String START = "// snippet:start ";
    private static final String END = "// snippet:end ";

    @Test
    void everyMarkedSnippetAppearsVerbatimInReadme() throws IOException {
        String readme = normalize(Files.readString(Path.of(System.getProperty("readme.path"))));
        Path sources = Path.of(System.getProperty("examples.sources"));

        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                collect(Files.readAllLines(file), names, readme, file);
            }
        }

        assertThat(names).as("snippet regions found in the examples").hasSizeGreaterThanOrEqualTo(4);
    }

    private static void collect(List<String> lines, List<String> names, String readme, Path file) {
        String name = null;
        List<String> body = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith(START)) {
                name = trimmed.substring(START.length()).strip();
                body.clear();
            } else if (trimmed.startsWith(END)) {
                assertThat(trimmed.substring(END.length()).strip()).as("end marker in %s", file).isEqualTo(name);
                names.add(name);
                String snippet = normalize(String.join("\n", body));
                assertThat(readme).as("README is missing snippet '%s' from %s", name, file.getFileName())
                        .contains(snippet);
                name = null;
            } else if (name != null) {
                body.add(line);
            }
        }
        assertThat(name).as("unterminated snippet in %s", file).isNull();
    }

    /** Strips the common indentation and trailing whitespace so a snippet can be indented in source. */
    private static String normalize(String text) {
        List<String> lines = text.lines().map(String::stripTrailing).toList();
        int indent = lines.stream().filter(l -> !l.isBlank()).mapToInt(ReadmeSnippetsTest::indentOf).min().orElse(0);
        return lines.stream().map(l -> l.isBlank() ? "" : l.substring(indent)).reduce((a, b) -> a + "\n" + b)
                .orElse("").strip();
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }
}
