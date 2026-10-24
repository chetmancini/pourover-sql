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

import javax.sql.DataSource;
import java.sql.DriverManager;

/**
 * Provide facilities for querying AWS Athena
 *
 * The official AWS Athena JDBC driver is not published to Maven Central, so it is
 * user-supplied on the runtime classpath. The connection is made through
 * {@link DriverManager} with the JDBC URL given as the host; custom properties are
 * passed to the driver.
 *
 * @author  Chet Mancini
 * @version 1.0
 * @since   2019-03-31
 */
public class AwsAthenaClient extends AbstractSQLClient {

    /**
     * Constructor
     * @param params the params for the connection; host is the JDBC URL.
     */
    public AwsAthenaClient(ConnectionParams params) {
        super(params);
    }

    @Override
    protected DataSource initDataSource() {
        return new DriverManagerDataSource(this.params.host(), this.driverProperties());
    }

    @Override
    protected String getDriverName() {
        return "com.amazon.athena.jdbc.AthenaDriver";
    }
}
