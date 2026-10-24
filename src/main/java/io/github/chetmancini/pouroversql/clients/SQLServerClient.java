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

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import io.github.chetmancini.pouroversql.ConnectionParams;

import javax.sql.DataSource;

/**
 * Client for SQLServer. Supported custom properties are listed on {@link ConnectionParams};
 * {@code encrypt} and {@code trustServerCertificate} default to {@code false}.
 */
public class SQLServerClient extends AbstractSQLClient {

    private static final int DEFAULT_PORT = 1433;
    private static final String DEFAULT_HOST = "localhost";

    /**
     * Constructor
     * @param params connection settings.
     */
    public SQLServerClient(ConnectionParams params) {
        super(params);
    }

    private boolean flag(String key) {
        Boolean value = this.booleanProperty(key);
        return value != null && value;
    }

    @Override
    public DataSource initDataSource() {
        SQLServerDataSource ds = new SQLServerDataSource();
        ds.setUser(this.params.user());
        ds.setPassword(this.params.password());
        ds.setServerName(this.host(DEFAULT_HOST));
        ds.setPortNumber(this.port(DEFAULT_PORT));
        ds.setDatabaseName(this.params.database());

        // String overload: setEncrypt(boolean) is deprecated for removal in mssql-jdbc.
        ds.setEncrypt(Boolean.toString(this.flag("encrypt")));
        ds.setTrustServerCertificate(this.flag("trustServerCertificate"));
        if (this.stringProperty("trustStore") != null) {
            ds.setTrustStore(this.stringProperty("trustStore"));
        }
        if (this.stringProperty("trustStorePassword") != null) {
            ds.setTrustStorePassword(this.stringProperty("trustStorePassword"));
        }
        if (this.stringProperty("trustStoreType") != null) {
            ds.setTrustStoreType(this.stringProperty("trustStoreType"));
        }
        if (this.stringProperty("accessToken") != null) {
            ds.setAccessToken(this.stringProperty("accessToken"));
        }
        if (this.stringProperty("authentication") != null) {
            ds.setAuthentication(this.stringProperty("authentication"));
        }
        if (this.stringProperty("authenticationScheme") != null) {
            ds.setAuthenticationScheme(this.stringProperty("authenticationScheme"));
        }
        if (this.stringProperty("columnEncryptionSetting") != null) {
            ds.setColumnEncryptionSetting(this.stringProperty("columnEncryptionSetting"));
        }
        if (this.stringProperty("failoverPartner") != null) {
            ds.setFailoverPartner(this.stringProperty("failoverPartner"));
        }
        Boolean integratedSecurity = this.booleanProperty("integratedSecurity");
        if (integratedSecurity != null) {
            ds.setIntegratedSecurity(integratedSecurity);
        }
        if (this.stringProperty("hostNameInCertificate") != null) {
            ds.setHostNameInCertificate(this.stringProperty("hostNameInCertificate"));
        }
        return ds;
    }

    @Override
    protected String getDriverName() {
        return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    }

}
