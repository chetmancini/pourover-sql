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

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Value types: ConnectionParams, QueryOptions, FormatOptions, KeyCase, OutputFormat, QueryException. */
class OptionsTest {

    @Test
    void connectionParamsToStringRedactsSecrets() {
        String secret = "pw-" + UUID.randomUUID();
        String sslSecret = "pw-" + UUID.randomUUID();
        String token = "tok-" + UUID.randomUUID();
        Properties props = new Properties();
        props.setProperty("sslPassword", sslSecret);
        props.setProperty("accessToken", token);
        props.setProperty("sslMode", "require");
        ConnectionParams params = new ConnectionParams("db.example", 5432, "app", secret, "main", props);

        String text = params.toString();
        assertThat(text).doesNotContain(secret, sslSecret, token)
                .contains("db.example", "5432", "app", "main", "sslMode=require", "[REDACTED]");
        assertThat(params.password()).isEqualTo(secret);
    }

    @Test
    void connectionParamsCopiesPropertiesDefensively() {
        Properties props = new Properties();
        props.setProperty("ssl", "true");
        ConnectionParams params = new ConnectionParams("h", null, "u", null, "d", props);
        props.setProperty("ssl", "false");
        assertThat(params.properties().getProperty("ssl")).isEqualTo("true");
        params.properties().setProperty("ssl", "false");
        assertThat(params.properties().getProperty("ssl")).isEqualTo("true");
        assertThat(new ConnectionParams("h", null, "u", null, "d", null).properties()).isEmpty();
        assertThat(new ConnectionParams("h", 1, "u", null, "d").properties()).isEmpty();
    }

    @Test
    void queryOptionsValidatesPositiveValues() {
        assertThatThrownBy(() -> new QueryOptions(0, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueryOptions(1, 0, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueryOptions(1, null, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueryOptions(1, null, null, -5)).isInstanceOf(IllegalArgumentException.class);
        QueryOptions o = QueryOptions.of(100, 10, 5);
        assertThat(o.fetchSize()).isEqualTo(100);
        assertThat(o.maxRows()).isEqualTo(10);
        assertThat(o.timeoutSeconds()).isEqualTo(5);
        assertThat(o.logFrequency()).isEqualTo(100_000);
    }

    @Test
    void formatOptionsDefaultsAndNullKeyCase() {
        assertThat(FormatOptions.defaults().keyCase()).isEqualTo(KeyCase.DEFAULT);
        assertThat(new FormatOptions(null).keyCase()).isEqualTo(KeyCase.DEFAULT);
    }

    @Test
    void keyCaseFromNameIsLenient() {
        assertThat(KeyCase.fromName(null)).isEqualTo(KeyCase.DEFAULT);
        assertThat(KeyCase.fromName("Camel")).isEqualTo(KeyCase.CAMEL);
        assertThat(KeyCase.fromName("camelCase")).isEqualTo(KeyCase.CAMEL);
        assertThat(KeyCase.fromName("SNAKE_CASE")).isEqualTo(KeyCase.SNAKE);
        assertThat(KeyCase.fromName("whatever")).isEqualTo(KeyCase.DEFAULT);
        assertThat(KeyCase.SNAKE.apply("theRainInSpain")).isEqualTo("the_rain_in_spain");
        assertThat(KeyCase.CAMEL.apply("the_rain")).isEqualTo("theRain");
        assertThat(KeyCase.DEFAULT.apply("The Rain")).isEqualTo("The Rain");
    }

    @Test
    void outputFormatFromNameAcceptsJson() {
        assertThat(OutputFormat.fromName("json")).isEqualTo(OutputFormat.JSONL);
        assertThat(OutputFormat.fromName("JSONL")).isEqualTo(OutputFormat.JSONL);
        assertThat(OutputFormat.fromName("csv")).isEqualTo(OutputFormat.CSV);
        assertThatThrownBy(() -> OutputFormat.fromName("xml")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryExceptionScrubsSecretFromMessageAndCause() {
        String secret = "pw-" + UUID.randomUUID();
        SQLException original = new SQLException("login failed for url?password=" + secret, "28000", 18456);
        QueryException e = QueryException.wrap(original, secret);
        assertThat(e.getMessage()).doesNotContain(secret).contains("28000", "[REDACTED]");
        assertThat(e.getSqlState()).isEqualTo("28000");
        assertThat(e.getCause()).isInstanceOf(SQLException.class);
        assertThat(e.getCause().getMessage()).doesNotContain(secret);
        assertThat(((SQLException) e.getCause()).getErrorCode()).isEqualTo(18456);
    }

    @Test
    void queryExceptionKeepsCauseWhenNothingToScrub() {
        SQLException original = new SQLException("syntax error", "42000");
        QueryException e = QueryException.wrap(original, null);
        assertThat(e.getCause()).isSameAs(original);
        assertThat(e.getMessage()).contains("syntax error");
    }
}
