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

import com.informix.jdbcx.IfxDataSource;
import io.github.chetmancini.pouroversql.ConnectionParams;

import javax.sql.DataSource;

/**
 * Informix SQL Client. Custom properties are ignored.
 */
public class InformixSQLClient extends AbstractSQLClient {

    private static final int DEFAULT_PORT = 1526;

    private static final String DEFAULT_HOST = "localhost";

    /**
     * Constructor
     * @param params connection settings.
     */
    InformixSQLClient(ConnectionParams params) {
        super(params);
    }

    @Override
    protected DataSource initDataSource() {
        IfxDataSource ifxDataSource = new IfxDataSource();
        ifxDataSource.setDatabaseName(this.params.database());
        ifxDataSource.setUser(this.params.user());
        ifxDataSource.setPassword(this.params.password());
        ifxDataSource.setIfxIFXHOST(this.host(DEFAULT_HOST));
        ifxDataSource.setPortNumber(this.port(DEFAULT_PORT));
        return ifxDataSource;
    }

    @Override
    protected String getDriverName() {
        return "com.informix.jdbc.IfxDriver";
    }
}
