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
package io.github.chetmancini.pouroversql.writers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.github.chetmancini.pouroversql.clients.FormattingParams;
import io.github.chetmancini.pouroversql.util.ResultSets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.chetmancini.pouroversql.util.TextFormat.getFunctionByKeyFormat;

/**
 * Process rows, apply formatting, and send to output.
 */
public class RowHandler {
    private final Logger logger = LoggerFactory.getLogger(RowHandler.class);

    private RowWriter writer;
    private int logFrequency;
    private FormattingParams formattingParams;

    /**
     * Convenience constructor that uses default parameters.
     * @param writer the writer to use
     */
    public RowHandler(RowWriter writer) {
        this.writer = writer;
        this.logFrequency = -1;
        this.formattingParams = new FormattingParams();
    }

    /**
     * Constructor
     * @param writer the writer to use
     * @param logFrequency how often to log status updates
     * @param formattingParams what formatting to apply
     */
    public RowHandler(RowWriter writer, Integer logFrequency, FormattingParams formattingParams) {
        this(writer);
        if (logFrequency != null) {
            this.logFrequency = logFrequency;
        }
        this.formattingParams = formattingParams;
    }

    /**
     * Main input
     * @param rs the ResultSet
     * @return the number of rows handled.
     * @throws SQLException
     */
    public int handle(ResultSet rs) throws SQLException {
        AtomicInteger counter = new AtomicInteger();
        Function<String, String> keyTransform = getFunctionByKeyFormat(this.formattingParams.getKeyCaseFormat());
        while (rs.next()) {
            writer.writeRow(this.handleRow(rs, keyTransform));
            counter.getAndIncrement();
            if (this.logFrequency > 0 && counter.get() % this.logFrequency == 0) {
                logger.info("Handling " + counter.get() + " rows...");
            }
        }
        return counter.intValue();
    }

    protected Map<String, Object> handleRow(ResultSet rs, Function<String, String> keyTransform) throws SQLException {
        Map<String, Object> row = ResultSets.toMap(rs);
        if (this.formattingParams.getKeyCaseFormat() == KeyCaseFormat.DEFAULT) {
            return row;
        }
        Map<String, Object> transformed = new LinkedHashMap<>(row.size() * 2);
        row.forEach((key, value) -> transformed.put(keyTransform.apply(key), value));
        return transformed;
    }

}
