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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.chetmancini.pouroversql.util.ResultSets;
import io.github.chetmancini.pouroversql.writers.RowHandler;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <h1>AbstractSQLClient</h1>
 * Subclass for all different engine types
 */
public abstract class AbstractSQLClient implements SQLClient {

    protected SQLParams params;
    protected QueryParams queryParams;

    private final Logger logger = LoggerFactory.getLogger(SQLClient.class);

    /**
     * Constructor
     * @param params SQLParams to setup the connection.
     */
    AbstractSQLClient(SQLParams params) {
        this.params = params;
        this.queryParams = QueryParams.getDefaultQueryParams();
    }

    /**
     * Constructor with a default set of queryparams for future queries
     * @param params SQLParams to setup the connection.
     * @param queryParams QueryParams for future queries.
     */
    AbstractSQLClient(SQLParams params, QueryParams queryParams) {
        this.params = params;
        this.queryParams = queryParams;
    }

    abstract protected DataSource initDataSource();

    abstract protected String getDriverName();

    @Override
    public void setQueryParams(QueryParams queryParams) {
        this.queryParams = queryParams;
    }

    @FunctionalInterface
    private interface ResultSetFunction<T> {
        T apply(ResultSet rs) throws SQLException;
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

    /**
     * Runs a query with fetch size, max rows and timeout from the current QueryParams,
     * closing the connection, statement and result set.
     */
    private <T> T execute(String queryText, ResultSetFunction<T> mapper) throws SQLException {
        if (queryText == null) {
            throw new SQLException("Null SQL statement");
        }
        this.loadDriver();
        DataSource ds = this.initDataSource();
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(queryText)) {
            if (this.queryParams.getFetchSize() != null) {
                stmt.setFetchSize(this.queryParams.getFetchSize());
            }
            if (this.queryParams.getTimeout() != null) {
                stmt.setQueryTimeout(this.queryParams.getTimeout());
            }
            if (this.queryParams.getMaxRows() != null) {
                stmt.setMaxRows(this.queryParams.getMaxRows());
            }
            try (ResultSet rs = stmt.executeQuery()) {
                return mapper.apply(rs);
            }
        }
    }

    @Override
    public List<Map<String, Object>> queryAsList(String queryText) {
        logger.debug("Querying for: " + queryText);
        try {
            return this.execute(queryText, rs -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(ResultSets.toMap(rs));
                }
                return rows;
            });
        } catch (Exception e) {
            logger.error(e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public int queryWithHandler(String queryText, RowHandler handler) {
        logger.debug("Querying for: " + queryText);
        try {
            return this.execute(queryText, handler::handle);
        } catch (Exception e) {
            logger.error(e.getMessage());
            e.printStackTrace();
            return -1;
        }
    }

}
