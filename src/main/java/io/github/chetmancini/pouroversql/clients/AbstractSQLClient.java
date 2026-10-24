/*
Copyright 2019-present, Simon Data, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at:
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package io.github.chetmancini.pouroversql.clients;

import io.github.chetmancini.pouroversql.ConnectionParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Subclass for all different engine types. Each subclass turns {@link ConnectionParams}
 * into an engine-specific {@link DataSource}.
 */
public abstract class AbstractSQLClient implements SQLClient {

    private static final Logger logger = LoggerFactory.getLogger(AbstractSQLClient.class);

    protected final ConnectionParams params;

    /**
     * Constructor
     * @param params connection settings.
     */
    AbstractSQLClient(ConnectionParams params) {
        this.params = Objects.requireNonNull(params, "params");
    }

    abstract protected DataSource initDataSource();

    abstract protected String getDriverName();

    @Override
    public DataSource dataSource() {
        this.loadDriver();
        return this.initDataSource();
    }

    /**
     * Best-effort driver registration. A missing driver is not fatal here: DriverManager
     * may still find it via service discovery, and a real failure surfaces on connect.
     */
    private void loadDriver() {
        try {
            Class.forName(this.getDriverName());
        } catch (ClassNotFoundException | LinkageError e) {
            logger.debug("JDBC driver class not found: " + this.getDriverName());
        }
    }

    protected String host(String defaultHost) {
        return params.host() != null ? params.host() : defaultHost;
    }

    protected int port(int defaultPort) {
        return params.port() != null ? params.port() : defaultPort;
    }

    protected String stringProperty(String key) {
        return params.properties().getProperty(key);
    }

    protected Boolean booleanProperty(String key) {
        return toBooleanObject(params.properties().getProperty(key));
    }

    /**
     * Builds driver connection properties: every custom property, then user and password.
     */
    protected Properties driverProperties() {
        Properties props = params.properties();
        if (params.user() != null) {
            props.setProperty("user", params.user());
        }
        if (params.password() != null) {
            props.setProperty("password", params.password());
        }
        return props;
    }

    private static Boolean toBooleanObject(String value) {
        if (value == null) {
            return null;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true": case "t": case "yes": case "y": case "on": case "1":
                return Boolean.TRUE;
            case "false": case "f": case "no": case "n": case "off": case "0":
                return Boolean.FALSE;
            default:
                return null;
        }
    }
}
