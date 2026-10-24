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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Base class for writers that print rows to a file or stream as UTF-8 text.
 */
public abstract class FileRowWriter extends AbstractRowWriter implements RowWriter {

    private final Logger logger = LoggerFactory.getLogger(FileRowWriter.class);

    static final String ENCODING = "UTF-8";

    protected PrintWriter writer = null;

    /**
     * Abstract method.
     * @param row the row data to write out.
     */
    public abstract void writeRow(Map<String, Object> row);

    /**
     * Convenience method.
     * @param outputFilename the file name to write to.
     */
    public void open(String outputFilename) {
        this.open(Path.of(outputFilename));
    }

    /**
     * Open an output file for writing.
     * @param outputFile the output file to open.
     */
    public void open(File outputFile) {
        this.open(outputFile.toPath());
    }

    /**
     * Open an output file for writing, creating or truncating it.
     * @param outputFile the output file to open.
     * @throws UncheckedIOException if the file cannot be opened.
     */
    public void open(Path outputFile) {
        try {
            this.writer = new PrintWriter(Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open " + outputFile + " for writing", e);
        }
        logger.info("Opening file: " + outputFile.getFileName());
        this.postOpenHook();
    }

    /**
     * Open an OutputStream and initialize writing process for a new query.
     * @param outputStream the OutputStream to open.
     */
    public void open(OutputStream outputStream) {
        this.writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)));
        this.postOpenHook();
    }

    protected void postOpenHook() {

    }

    protected void postCloseHook() {

    }

    /**
     * Flush and close the writer safely.
     * @throws UncheckedIOException if buffered output could not be written.
     */
    @Override
    public void close() {
        PrintWriter current = this.writer;
        this.writer = null;
        if (current != null) {
            current.flush();
            boolean failed = current.checkError();
            current.close();
            if (failed) {
                this.postCloseHook();
                throw new UncheckedIOException(new IOException("Error writing output"));
            }
        }
        this.postCloseHook();
    }
}
