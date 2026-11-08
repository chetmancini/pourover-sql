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
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Base class for writers that print rows to a file or stream as UTF-8 text. Outputs opened by this
 * class throw {@link UncheckedIOException} on underlying write, flush and close failures rather
 * than silently recording them.
 */
public abstract class FileRowWriter extends AbstractRowWriter implements RowWriter {

    private final Logger logger = LoggerFactory.getLogger(FileRowWriter.class);

    static final String ENCODING = "UTF-8";

    protected PrintWriter writer = null;

    /**
     * Abstract method.
     * @param row the row data to write out.
     * @throws UncheckedIOException if the output cannot be written.
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
            this.writer = new PrintWriter(new FailFastWriter(
                    Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)));
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
        this.writer = new PrintWriter(new FailFastWriter(
                new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))));
        this.postOpenHook();
    }

    protected void postOpenHook() {

    }

    protected void postCloseHook() {

    }

    /**
     * Flush and close buffered output, clear the writer even on failure, and run the post-close
     * hook only after a successful close.
     * @throws UncheckedIOException if buffered output cannot be written or output cannot be closed.
     */
    @Override
    public void close() {
        PrintWriter current = this.writer;
        try {
            if (current != null) {
                current.close();
            }
        } finally {
            this.writer = null;
        }
        this.postCloseHook();
    }

    private static final class FailFastWriter extends Writer {
        private final Writer delegate;

        private FailFastWriter(Writer delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int c) {
            try {
                delegate.write(c);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            try {
                delegate.write(cbuf, off, len);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void write(String str, int off, int len) {
            try {
                delegate.write(str, off, len);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void flush() {
            try {
                delegate.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            try {
                delegate.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
