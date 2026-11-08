/*
 * Copyright 2026 Chet Mancini
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.chetmancini.pouroversql.writers;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileRowWriterTest {

    @Test
    void rowWriteIOExceptionPropagatesWithoutPostCloseHook() {
        IOException failure = new IOException("write failed");
        RecordingOutputStream output = new RecordingOutputStream(failure, null);
        TestRowWriter subject = new TestRowWriter();
        subject.open(output);

        assertThatThrownBy(() -> subject.writeRow(Map.of("value", "x".repeat(16_384))))
                .isInstanceOf(UncheckedIOException.class)
                .hasCause(failure);
        assertThat(subject.postCloseCount).isZero();
    }

    @Test
    void closeIOExceptionPropagatesClearsWriterAndSkipsPostCloseHook() {
        IOException failure = new IOException("close failed");
        RecordingOutputStream output = new RecordingOutputStream(null, failure);
        TestRowWriter subject = new TestRowWriter();
        subject.open(output);
        subject.writeRow(Map.of("value", "x"));

        assertThatThrownBy(subject::close)
                .isInstanceOf(UncheckedIOException.class)
                .hasCause(failure);
        assertThat(subject.writer).isNull();
        assertThat(subject.postCloseCount).isZero();
    }

    @Test
    void closeRunsPostCloseHookAfterSuccessfulClose() {
        TestRowWriter subject = new TestRowWriter();
        subject.open(new RecordingOutputStream(null, null));

        subject.close();

        assertThat(subject.writer).isNull();
        assertThat(subject.postCloseCount).isOne();
    }

    @Test
    void writeRowDoesNotFlush() {
        RecordingOutputStream output = new RecordingOutputStream(null, null);
        TestRowWriter subject = new TestRowWriter();
        subject.open(output);

        subject.writeRow(Map.of("value", "x"));

        assertThat(output.flushCount).isZero();
        subject.close();
    }

    private static final class TestRowWriter extends FileRowWriter {
        private int postCloseCount;

        @Override
        public void writeRow(Map<String, Object> row) {
            this.writer.println(String.valueOf(row.get("value")));
        }

        @Override
        protected void postCloseHook() {
            this.postCloseCount++;
        }
    }

    private static final class RecordingOutputStream extends OutputStream {
        private final IOException writeFailure;
        private final IOException closeFailure;
        private int flushCount;

        private RecordingOutputStream(IOException writeFailure, IOException closeFailure) {
            this.writeFailure = writeFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public void write(int b) throws IOException {
            if (this.writeFailure != null) {
                throw this.writeFailure;
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (this.writeFailure != null) {
                throw this.writeFailure;
            }
        }

        @Override
        public void flush() {
            this.flushCount++;
        }

        @Override
        public void close() throws IOException {
            if (this.closeFailure != null) {
                throw this.closeFailure;
            }
        }
    }
}
