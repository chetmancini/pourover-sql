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
import java.util.Properties;
import java.util.TreeSet;

/**
 * Connection settings for {@link Pourover#connect(io.github.chetmancini.pouroversql.clients.SqlEngine,
 * ConnectionParams)}. Any field may be null; each engine applies its own defaults (for example
 * {@code localhost} and the engine's standard port).
 *
 * <p>{@code properties} carries engine-specific options. Supported keys:
 * <ul>
 *   <li><b>POSTGRESQL</b>: {@code ssl}, {@code binaryTransfer} (booleans); {@code kerberosServerName},
 *       {@code sslCert}, {@code sslKey}, {@code sslMode}, {@code sslPassword}, {@code sslRootCert};
 *       {@code readOnly} (boolean, defaults to {@code true}).</li>
 *   <li><b>SQLSERVER</b>: {@code encrypt}, {@code trustServerCertificate} (booleans, default
 *       {@code false}); {@code integratedSecurity} (boolean); {@code hostNameInCertificate},
 *       {@code accessToken}, {@code authentication}, {@code authenticationScheme},
 *       {@code columnEncryptionSetting}, {@code failoverPartner}, {@code trustStore},
 *       {@code trustStorePassword}, {@code trustStoreType}.</li>
 *   <li><b>SNOWFLAKE</b>, <b>REDSHIFT</b>, <b>ATHENA</b>: every property is passed to the JDBC
 *       driver as a connection property (for example Snowflake's {@code warehouse} and
 *       {@code role}).</li>
 *   <li><b>MYSQL</b>, <b>INFORMIX</b>: properties are ignored.</li>
 * </ul>
 * Boolean values accept {@code true/false}, {@code yes/no}, {@code on/off}, {@code t/f},
 * {@code y/n} and {@code 1/0}, case-insensitively.
 *
 * <p>{@link #toString()} never includes the password or the value of any property whose key
 * contains {@code password}, {@code token} or {@code secret}.
 *
 * @param host the server host, or for ATHENA the full JDBC URL
 * @param port the server port, or null for the engine default
 * @param user the user to connect as
 * @param password the user's password
 * @param database the database to use
 * @param properties engine-specific options; copied, and null means none
 */
public record ConnectionParams(
        String host, Integer port, String user, String password, String database, Properties properties) {

    private static final String REDACTED = "[REDACTED]";

    /**
     * Canonical constructor; copies {@code properties} so later changes by the caller have no effect.
     */
    public ConnectionParams {
        properties = copy(properties);
    }

    /**
     * Creates params without engine-specific properties.
     * @param host the server host
     * @param port the server port, or null for the engine default
     * @param user the user to connect as
     * @param password the user's password
     * @param database the database to use
     */
    public ConnectionParams(String host, Integer port, String user, String password, String database) {
        this(host, port, user, password, database, null);
    }

    /**
     * Returns a copy of the engine-specific properties; never null.
     * @return a copy of the properties
     */
    @Override
    public Properties properties() {
        return copy(properties);
    }

    private static Properties copy(Properties source) {
        Properties result = new Properties();
        if (source != null) {
            for (String name : source.stringPropertyNames()) {
                result.setProperty(name, source.getProperty(name));
            }
        }
        return result;
    }

    /**
     * True when a property key names a credential whose value must not be logged.
     * @param key the property key
     * @return whether the value should be redacted
     */
    static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("token") || lower.contains("secret");
    }

    /**
     * Describes these params with the password and secret-looking property values redacted.
     * @return a log-safe description
     */
    @Override
    public String toString() {
        StringBuilder props = new StringBuilder("{");
        for (String name : new TreeSet<>(properties.stringPropertyNames())) {
            if (props.length() > 1) {
                props.append(", ");
            }
            props.append(name).append('=').append(isSecretKey(name) ? REDACTED : properties.getProperty(name));
        }
        props.append('}');
        return "ConnectionParams[host=" + host
                + ", port=" + port
                + ", user=" + user
                + ", password=" + (password == null ? "null" : REDACTED)
                + ", database=" + database
                + ", properties=" + props + "]";
    }
}
