/*
  Copyright 2019-present, Simon Data, Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */

package io.github.chetmancini.pouroversql.writers;

import io.github.chetmancini.pouroversql.RowFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <h1>CSVRowWriter</h1>
 * CSVRowWriter is a type of writer that outputs to CSV. Quoting, null and temporal
 * rendering follow {@link RowFormatter}.
 */
public class CSVRowWriter extends FileRowWriter {

    private List<String> orderedKeys;

    private final CharSequence delimiter;

    private boolean headersWritten;

    private boolean shouldWriteHeaders = true;

    /**
     * Constructor using default delimiter.
     */
    public CSVRowWriter() {
        this.delimiter = ",";
    }

    /**
     * Constructor with custom delimiter.
     * @param delimiter the delimiter to separate the columns.
     */
    public CSVRowWriter(CharSequence delimiter) {
        this.delimiter = delimiter;
    }

    /**
     * Constructor with custom delimiter and
     * flag to turn off headers.
     * @param delimiter The delimiter for the CSV. defaults to comma.
     * @param shouldWriteHeaders Whether to write the headers as the first row of the CSV.
     */
    public CSVRowWriter(CharSequence delimiter, boolean shouldWriteHeaders) {
        this.delimiter = delimiter;
        this.shouldWriteHeaders = shouldWriteHeaders;
    }

    @Override
    protected void postCloseHook() {
        this.headersWritten = false;
        this.orderedKeys = null;
    }

    @Override
    protected void postOpenHook() {
        this.headersWritten = false;
        this.orderedKeys = null;
    }

    /**
     * Fixes the column order and writes the header row (if enabled) before any data row.
     * @param columns the column keys, in order
     */
    @Override
    public void begin(List<String> columns) {
        this.orderedKeys = new ArrayList<>(columns);
        this.writeHeaderIfNeeded();
    }

    private void writeHeaderIfNeeded() {
        if (!this.headersWritten && this.shouldWriteHeaders) {
            this.writer.println(RowFormatter.csvHeader(this.orderedKeys, this.delimiter));
            this.headersWritten = true;
        }
    }

    private void writeHeadersOnce(Map<String, Object> row) {
        if (this.orderedKeys == null) {
            this.orderedKeys = new ArrayList<>(row.keySet());
        }
        this.writeHeaderIfNeeded();
    }

    /**
     * Write a row represented as a Map to the printwriter as CSV.
     * @param row the row data to write out.
     */
    @Override
    public void writeRow(Map<String, Object> row) {
        this.writeHeadersOnce(row);
        List<Object> values = new ArrayList<>(this.orderedKeys.size());
        for (String key : this.orderedKeys) {
            values.add(row.get(key));
        }
        this.writer.println(RowFormatter.csvRow(values, this.delimiter));
    }

}
