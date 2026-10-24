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
package io.github.chetmancini.pouroversql.clients;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import io.github.chetmancini.pouroversql.ConnectionParams;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Engine-specific options formerly carried by *SQLParams now flow through ConnectionParams.properties. */
class ClientFactoryTest {

    @Test
    void postgresPropertiesAreApplied() {
        String sslSecret = "pw-" + UUID.randomUUID();
        Properties props = new Properties();
        props.setProperty("ssl", "true");
        props.setProperty("binaryTransfer", "false");
        props.setProperty("kerberosServerName", "krb");
        props.setProperty("sslCert", "/c.pem");
        props.setProperty("sslKey", "/k.pem");
        props.setProperty("sslMode", "verify-full");
        props.setProperty("sslPassword", sslSecret);
        props.setProperty("sslRootCert", "/root.pem");
        ConnectionParams params = new ConnectionParams(null, null, "u", null, "db", props);

        PGSimpleDataSource ds = (PGSimpleDataSource) ClientFactory.makeSQLClient(SqlEngine.POSTGRESQL, params)
                .dataSource();
        assertThat(ds.getServerNames()).containsExactly("localhost");
        assertThat(ds.getPortNumbers()).containsExactly(5432);
        assertThat(ds.getDatabaseName()).isEqualTo("db");
        assertThat(ds.getSsl()).isTrue();
        assertThat(ds.getBinaryTransfer()).isFalse();
        assertThat(ds.getKerberosServerName()).isEqualTo("krb");
        assertThat(ds.getSslCert()).isEqualTo("/c.pem");
        assertThat(ds.getSslKey()).isEqualTo("/k.pem");
        assertThat(ds.getSslMode()).isEqualTo("verify-full");
        assertThat(ds.getSslPassword()).isEqualTo(sslSecret);
        assertThat(ds.getSslRootCert()).isEqualTo("/root.pem");
        assertThat(ds.getReadOnly()).isTrue();
    }

    @Test
    void postgresReadOnlyCanBeOverridden() {
        Properties props = new Properties();
        props.setProperty("readOnly", "false");
        PGSimpleDataSource ds = (PGSimpleDataSource) ClientFactory.makeSQLClient(SqlEngine.POSTGRESQL,
                new ConnectionParams("h", 1, "u", null, "db", props)).dataSource();
        assertThat(ds.getReadOnly()).isFalse();
    }

    @Test
    void sqlServerPropertiesAreApplied() {
        String storeSecret = "pw-" + UUID.randomUUID();
        Properties props = new Properties();
        props.setProperty("encrypt", "true");
        props.setProperty("trustServerCertificate", "true");
        props.setProperty("trustStore", "/ts.jks");
        props.setProperty("trustStorePassword", storeSecret);
        props.setProperty("trustStoreType", "JKS");
        props.setProperty("authentication", "ActiveDirectoryPassword");
        props.setProperty("authenticationScheme", "NTLM");
        props.setProperty("columnEncryptionSetting", "Enabled");
        props.setProperty("failoverPartner", "backup");
        props.setProperty("integratedSecurity", "false");
        props.setProperty("hostNameInCertificate", "*.example");
        ConnectionParams params = new ConnectionParams("sql", 1500, "u", null, "db", props);

        SQLServerDataSource ds = (SQLServerDataSource) ClientFactory.makeSQLClient(SqlEngine.SQLSERVER, params)
                .dataSource();
        assertThat(ds.getServerName()).isEqualTo("sql");
        assertThat(ds.getPortNumber()).isEqualTo(1500);
        assertThat(ds.getEncrypt()).isEqualToIgnoringCase("true");
        assertThat(ds.getTrustServerCertificate()).isTrue();
        assertThat(ds.getTrustStore()).isEqualTo("/ts.jks");
        assertThat(ds.getTrustStoreType()).isEqualTo("JKS");
        assertThat(ds.getAuthentication()).isEqualToIgnoringCase("ActiveDirectoryPassword");
        assertThat(ds.getColumnEncryptionSetting()).isEqualToIgnoringCase("Enabled");
        assertThat(ds.getFailoverPartner()).isEqualTo("backup");
        assertThat(ds.getHostNameInCertificate()).isEqualTo("*.example");
    }

    @Test
    void sqlServerAccessTokenIsApplied() {
        String token = "tok-" + UUID.randomUUID();
        Properties props = new Properties();
        props.setProperty("accessToken", token);
        SQLServerDataSource ds = (SQLServerDataSource) ClientFactory.makeSQLClient(SqlEngine.SQLSERVER,
                new ConnectionParams(null, null, null, null, "db", props)).dataSource();
        assertThat(ds.getAccessToken()).isEqualTo(token);
    }

    @Test
    void sqlServerEncryptDefaultsToFalseAsBefore() {
        SQLServerDataSource ds = (SQLServerDataSource) ClientFactory.makeSQLClient(SqlEngine.SQLSERVER,
                new ConnectionParams(null, null, "u", null, "db", null)).dataSource();
        assertThat(ds.getServerName()).isEqualTo("localhost");
        assertThat(ds.getPortNumber()).isEqualTo(1433);
        assertThat(ds.getEncrypt()).isEqualToIgnoringCase("false");
        assertThat(ds.getTrustServerCertificate()).isFalse();
    }

    @Test
    void snowflakePassesPropertiesThroughToDriver() {
        String secret = "pw-" + UUID.randomUUID();
        Properties props = new Properties();
        props.setProperty("warehouse", "wh");
        props.setProperty("role", "analyst");
        DriverManagerDataSource ds = (DriverManagerDataSource) ClientFactory.makeSQLClient(SqlEngine.SNOWFLAKE,
                new ConnectionParams("acct.snowflakecomputing.com", null, "u", secret, "db", props)).dataSource();
        assertThat(ds.url()).isEqualTo("jdbc:snowflake://acct.snowflakecomputing.com");
        assertThat(ds.properties().getProperty("warehouse")).isEqualTo("wh");
        assertThat(ds.properties().getProperty("role")).isEqualTo("analyst");
        assertThat(ds.properties().getProperty("db")).isEqualTo("db");
        assertThat(ds.properties().getProperty("user")).isEqualTo("u");
        assertThat(ds.toString()).doesNotContain(secret);
    }

    @Test
    void redshiftBuildsUrlAndPassesProperties() {
        Properties props = new Properties();
        props.setProperty("ssl", "true");
        DriverManagerDataSource ds = (DriverManagerDataSource) ClientFactory.makeSQLClient(SqlEngine.REDSHIFT,
                new ConnectionParams("rs.example", null, "u", null, "dev", props)).dataSource();
        assertThat(ds.url()).isEqualTo("jdbc:redshift://rs.example:5439/dev");
        assertThat(ds.properties().getProperty("ssl")).isEqualTo("true");
    }

    @Test
    void unsupportedEnginesThrow() {
        ConnectionParams params = new ConnectionParams("h", null, "u", null, "d", null);
        assertThatThrownBy(() -> ClientFactory.makeSQLClient(SqlEngine.ORACLE, params))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientFactory.makeSQLClient(SqlEngine.BIGQUERY, params))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientFactory.makeSQLClient(null, params))
                .isInstanceOf(NullPointerException.class);
    }
}
