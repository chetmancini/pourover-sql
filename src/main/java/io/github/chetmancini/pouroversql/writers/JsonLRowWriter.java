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

import io.github.chetmancini.pouroversql.FormatOptions;
import io.github.chetmancini.pouroversql.RowFormatter;

import java.util.Map;

/**
 * JsonLRowWriter
 * A file writer that outputs jsonl files, one {@link RowFormatter#jsonl} object per line.
 * Keys are written as given; apply key case before handing rows to this writer.
 */
public class JsonLRowWriter extends FileRowWriter {

    /**
     * Base Constructor
     * Requires open() be called after.
     */
    public JsonLRowWriter() {
    }

    /**
     * Write a row represented as a Map to the printwriter as Json.
     * @param row the row data to write out.
     */
    @Override
    public void writeRow(Map<String, Object> row) {
        this.writer.println(RowFormatter.jsonl(row, FormatOptions.defaults()));
    }

}
