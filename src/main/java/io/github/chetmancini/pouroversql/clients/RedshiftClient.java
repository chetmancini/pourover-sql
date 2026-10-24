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

/**
 * Provide facilities for querying to Redshift. Custom properties are passed to the driver.
 *
 * @author  Chet Mancini
 * @since   2019-03-31
 */
public class RedshiftClient extends AbstractSQLClient {

    private static final int DEFAULT_PORT = 5439;

    /**
     * Constructor
     * @param params connection settings.
     */
    public RedshiftClient(ConnectionParams params) {
        super(params);
    }

    private String getRedshiftURL() {
        return String.format("jdbc:redshift://%s:%s/%s",
                this.params.host(), this.port(DEFAULT_PORT), this.params.database()
        );
    }

    @Override
    public javax.sql.DataSource initDataSource() {
        return new DriverManagerDataSource(this.getRedshiftURL(), this.driverProperties());
    }

    @Override
    protected String getDriverName() {
        return "com.amazon.redshift.Driver";
    }
}
