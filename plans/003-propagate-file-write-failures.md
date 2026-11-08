# Plan 003: Propagate file write and close failures

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
>
> ```sh
> git diff --stat 5b74317..HEAD -- \
>   src/main/java/io/github/chetmancini/pouroversql/writers/FileRowWriter.java \
>   src/test/java/io/github/chetmancini/pouroversql/writers/FileRowWriterTest.java \
>   CHANGELOG.md \
>   plans/README.md
> ```
>
> If an implementation, test, or changelog excerpt below no longer matches the
> live file, treat it as a STOP condition. `plans/README.md` may differ only in
> plan status rows; preserve every other row and all index notes.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `5b74317`, 2026-11-06

## Why this matters

`PrintWriter` records an underlying `IOException` instead of throwing it, so CSV
and JSONL writes can appear to succeed after output has failed. The current
close sequence checks for errors only *before* `PrintWriter.close()`; the audit's
targeted probe demonstrated that an `IOException` raised only by close is
silently swallowed. This change makes write, flush, and close failures propagate
as the already-documented `UncheckedIOException`, without changing the protected
`PrintWriter` extension point or adding a flush to every row.

## Current state

Relevant files:

- `src/main/java/io/github/chetmancini/pouroversql/writers/FileRowWriter.java` —
  owns the protected `PrintWriter`, opens file/stream outputs, and runs close
  hooks.
- `src/main/java/io/github/chetmancini/pouroversql/writers/CSVRowWriter.java` —
  writes the header and each row with `PrintWriter.println`; it must remain
  unchanged.
- `src/main/java/io/github/chetmancini/pouroversql/writers/JsonLRowWriter.java` —
  writes each JSON object with `PrintWriter.println`; it must remain unchanged.
- `src/test/java/io/github/chetmancini/pouroversql/writers/CSVRowWriterTest.java`
  and `JsonLRowWriterTest.java` — existing normal-output regression tests.
- `src/test/java/io/github/chetmancini/pouroversql/writers/FileRowWriterTest.java`
  — new focused failure-lifecycle test class to create.
- `CHANGELOG.md` — user-visible fixes belong under `1.0.0 (unreleased)` /
  `Fixed`.

The extension field is public API for subclasses and must keep its exact type,
name, and visibility (`FileRowWriter.java:32-47`):

```java
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
```

Both open paths currently put an ordinary writer directly inside `PrintWriter`
(`FileRowWriter.java:65-87`):

```java
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
```

The close bug and current hook behavior are visible at
`FileRowWriter.java:89-115`:

```java
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
```

Thus a pre-close error currently runs `postCloseHook()` before throwing, while a
close-only error is caught internally by `PrintWriter.close()` and never seen by
this method. The required new contract is: clear the field in `finally`, run the
hook only after a successful close, and propagate a close failure.

Both concrete formats rely on the same `println` path. From
`CSVRowWriter.java:101-113`:

```java
    @Override
    public void writeRow(Map<String, Object> row) {
        this.writeHeadersOnce(row);
        List<Object> values = new ArrayList<>(this.orderedKeys.size());
        for (String key : this.orderedKeys) {
            values.add(row.get(key));
        }
        this.writer.println(RowFormatter.csvRow(values, this.delimiter));
    }
```

From `JsonLRowWriter.java:36-43`:

```java
    /**
     * Write a row represented as a Map to the printwriter as Json.
     * @param row the row data to write out.
     */
    @Override
    public void writeRow(Map<String, Object> row) {
        this.writer.println(RowFormatter.jsonl(row, FormatOptions.defaults()));
    }
```

The normal-output assertions already pin CSV and JSONL bytes. In particular,
`CSVRowWriterTest.java:40-49` expects:

```java
        FileRowWriter subject = new CSVRowWriter(",", true);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        subject.open(baos);
        int lines = subject.writeRows(input);
        subject.close();
        assertEquals(
                "a,b\nabc,1\njkl,2\n",
                baos.toString()
        );
        assertEquals(2, lines);
```

`JsonLRowWriterTest.java:38-48` expects:

```java
        FileRowWriter subject = new JsonLRowWriter();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        subject.open(baos);

        int lines = subject.writeRows(input);
        subject.close();
        assertEquals(
                "{\"a\":\"abc\",\"b\":1}\n{\"a\":\"jkl\",\"b\":2}\n",
                baos.toString(JsonLRowWriter.ENCODING)
        );
        assertEquals(2, lines);
```

Repository conventions from `CONTRIBUTING.md`: Java 21, four-space indentation,
no wildcard imports, Javadoc on public API, JUnit 6 and AssertJ, no new dependency
for this change. Use AssertJ in the new test even though the two older writer
tests use JUnit assertions. Existing files retain their license headers; the new
test uses the same header as `PouroverTest.java`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| New focused tests | `./gradlew test --tests 'io.github.chetmancini.pouroversql.writers.FileRowWriterTest'` | exit 0; all four new tests pass after the fix |
| All writer regressions | `./gradlew test --tests 'io.github.chetmancini.pouroversql.writers.FileRowWriterTest' --tests 'io.github.chetmancini.pouroversql.writers.CSVRowWriterTest' --tests 'io.github.chetmancini.pouroversql.writers.JsonLRowWriterTest'` | exit 0; four new plus three existing tests pass |
| Full build | `./gradlew build` | exit 0; unit, examples, and demo tests pass |
| Public API docs | `./gradlew javadoc` | exit 0 with no Javadoc warnings |
| Whitespace check | `git diff --check` | exit 0, no output |

The supplied audit baseline at `5b74317` had `./gradlew build` and
`./gradlew javadoc` passing. Do not treat the intentional red regression-test
run in Step 1 as a broken baseline.

## Scope

**In scope** (the only files to modify):

- `src/main/java/io/github/chetmancini/pouroversql/writers/FileRowWriter.java`
- `src/test/java/io/github/chetmancini/pouroversql/writers/FileRowWriterTest.java`
  (create)
- `CHANGELOG.md`
- `plans/README.md` — only this plan's status row, and only if the reviewer is
  not maintaining the index

**Out of scope** (do not touch or implement):

- `CSVRowWriter.java` and `JsonLRowWriter.java`: their `println` calls and normal
  formatting stay unchanged.
- Changing `protected PrintWriter writer` to another type, renaming it, reducing
  its visibility, or changing any public/protected method signature.
- Per-row `checkError()` or `flush()`. **Explicitly forbidden**:
  `PrintWriter.checkError()` itself flushes, so calling it after each row would
  add a per-row flush and defeat buffered output.
- Atomic temporary files, rename-on-success, deleting partial files, or changing
  when output files are created/truncated. Partial files remain in place after a
  failure, exactly as `Pourover.writeTo` currently documents.
- Stdout/`OutputStream` API expansion, output splitting, compression, or CLI
  changes.
- Changes to `Pourover`, `AbstractRowWriter`, or unrelated writer tests.

## Git workflow

- Branch: `advisor/003-propagate-file-write-failures`
- Make one logical commit; suggested message:
  `fix: propagate file output write failures`
- Match the repository's short conventional-commit style, for example
  `ec40641 fix: render multi-dimensional SQL arrays as nested lists`.
- Do not push or open a PR unless the operator instructs it.

## Steps

### Step 1: Add failing writer-lifecycle regression tests

Create `FileRowWriterTest` in package
`io.github.chetmancini.pouroversql.writers`, with these four tests:

1. `rowWriteIOExceptionPropagatesWithoutPostCloseHook`
2. `closeIOExceptionPropagatesClearsWriterAndSkipsPostCloseHook`
3. `closeRunsPostCloseHookAfterSuccessfulClose`
4. `writeRowDoesNotFlush`

Use a small private `FileRowWriter` subclass whose `writeRow` calls
`writer.println(String.valueOf(row.get("value")))` and whose `postCloseHook`
increments a counter. Use a custom `OutputStream` that records `flush()` calls,
can throw a supplied `IOException` from both `write(int)` and
`write(byte[], int, int)`, and independently can throw a supplied `IOException`
only from `close()`. Override both write overloads so the test does not depend on
`OutputStream`'s default loop. No real files or credentials are needed.

For the row-write case, make the value `"x".repeat(16_384)` so it exceeds the
buffer and reaches the failing stream during `writeRow`, rather than only during
test cleanup. Assert that `writeRow` throws `UncheckedIOException` with the exact
supplied `IOException` as its cause and that the close-hook count remains zero.
Do not silently move this assertion to `close()`.

For the close-only case, writes succeed and only `close()` throws. Assert that
`FileRowWriter.close()` throws `UncheckedIOException` with that exact cause,
that the protected `writer` field is null afterward (the test is in the same
package), and that the hook count remains zero. For the successful-close case,
assert the field is null and the hook count is one. For the no-flush case, write
one short row and assert the stream's flush count is still zero *before* close;
this specifically prevents a per-row `checkError()` implementation.

**Verify the tests reproduce the bug before changing production code**:

```sh
./gradlew test --tests 'io.github.chetmancini.pouroversql.writers.FileRowWriterTest'
```

Expected on the unmodified implementation: a nonzero exit, with
`rowWriteIOExceptionPropagatesWithoutPostCloseHook` and
`closeIOExceptionPropagatesClearsWriterAndSkipsPostCloseHook` failing because no
`UncheckedIOException` is thrown. The successful-close and no-per-row-flush tests
should pass. If the first two pass, the fixture is not exercising the bug; see
STOP conditions rather than proceeding with an unproven regression test.

### Step 2: Put a fail-fast `Writer` adapter below `PrintWriter`

In `FileRowWriter.java`, add `java.io.Writer` to the explicit imports and add a
private static nested adapter. Its only job is to delegate and convert every
underlying `IOException` from write, flush, and close into
`UncheckedIOException` before `PrintWriter` can catch and record it. Use this
shape (the name is intentionally private and not API):

```java
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
```

Keep all three write overloads: in particular, delegating `write(String, int,
int)` avoids the temporary character array that `Writer`'s default implementation
would allocate for each `println(String)`. Do not add synchronization, buffering,
error flags, or another public wrapper.

Wrap the existing writers at both open sites, preserving the existing buffering,
UTF-8 charset, file-open exception message, and hooks:

```java
this.writer = new PrintWriter(new FailFastWriter(
        Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)));
```

```java
this.writer = new PrintWriter(new FailFastWriter(
        new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))));
```

Replace `close()` with this exact lifecycle:

```java
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
```

`PrintWriter.close()` closes the adapter, which closes and flushes the existing
buffered writer. There must be no `flush()`, `checkError()`, or per-row error
polling left in `FileRowWriter`. If close throws, the `finally` clears the field
and control never reaches `postCloseHook()`; the adapter's
`UncheckedIOException` propagates with the original cause.

**Verify**:

```sh
./gradlew test --tests 'io.github.chetmancini.pouroversql.writers.FileRowWriterTest' --tests 'io.github.chetmancini.pouroversql.writers.CSVRowWriterTest' --tests 'io.github.chetmancini.pouroversql.writers.JsonLRowWriterTest'
```

Expected: exit 0; all four new tests and all three existing CSV/JSONL tests pass.

### Step 3: Document the failure contract and changelog entry

Update only `FileRowWriter` writer documentation:

- Class Javadoc: outputs opened by this class throw `UncheckedIOException` on
  underlying write, flush, and close failures rather than silently recording
  them.
- Abstract `writeRow` Javadoc: add an `@throws UncheckedIOException` entry for an
  output write failure.
- `close()` Javadoc: say it flushes and closes buffered output, clears the writer
  even on failure, runs the post-close hook only after success, and throws
  `UncheckedIOException` when buffered output cannot be written or the output
  cannot be closed.
- Do not claim partial files are removed or that arbitrary subclasses which
  replace the protected `writer` themselves gain the adapter automatically.

Add one bullet under `CHANGELOG.md` → `1.0.0 (unreleased)` → `Fixed`:

```markdown
- File output no longer silently succeeds when the underlying writer fails during a row write or only while closing; these failures now propagate as `UncheckedIOException`, and post-close hooks run only after a successful close.
```

**Verify**:

```sh
./gradlew javadoc
```

Expected: exit 0 and no Javadoc warnings.

### Step 4: Run the full gate and finish bookkeeping

Run:

```sh
./gradlew build
git diff --check
git status --short
```

Expected: `build` and `git diff --check` exit 0. Status shows only
`FileRowWriter.java`, new `FileRowWriterTest.java`, `CHANGELOG.md`, and optionally
`plans/README.md` when this executor owns the index. Set plan 003 to `DONE` only
after all checks pass, or to `BLOCKED` with a one-line reason if stopped. Create
the single logical commit with the message above; never push or open a PR without
operator instruction.

## Test plan

- New `FileRowWriterTest.rowWriteIOExceptionPropagatesWithoutPostCloseHook`:
  fail-fast row write, original `IOException` retained, no silent success, no
  post-close hook on failure.
- New `FileRowWriterTest.closeIOExceptionPropagatesClearsWriterAndSkipsPostCloseHook`:
  regression for the probe's close-only failure, field cleared in `finally`, no
  hook on failed close.
- New `FileRowWriterTest.closeRunsPostCloseHookAfterSuccessfulClose`: ordinary
  lifecycle still runs the hook exactly once and clears the field.
- New `FileRowWriterTest.writeRowDoesNotFlush`: no per-row flush or
  `checkError()` was introduced.
- Existing `CSVRowWriterTest.writeQuery`, `writeQueryQuotesValues`, and
  `JsonLRowWriterTest.writeQuery`: normal CSV/JSONL content and row counts remain
  byte-for-byte unchanged. Do not weaken or rewrite these assertions.
- Verification: the three-class Gradle command in Step 2 exits 0 with all seven
  tests passing; `./gradlew build` then covers the rest of the suite.

## Done criteria

- [ ] Both `open` paths place the private fail-fast `Writer` below the same
  protected `PrintWriter writer`; no public/protected signature changed.
- [ ] All underlying adapter write/flush/close `IOException`s propagate as
  `UncheckedIOException` with their original cause.
- [ ] `close()` nulls `writer` in `finally`, calls `postCloseHook()` only after
  successful close, and contains no `checkError()` or explicit `flush()`.
- [ ] No row path calls `checkError()` or `flush()`.
- [ ] The four named regression tests pass; the three existing CSV/JSONL tests
  still pass unchanged.
- [ ] `./gradlew build` exits 0.
- [ ] `./gradlew javadoc` exits 0 with no warnings.
- [ ] `git diff --check` exits 0.
- [ ] No files outside the in-scope list are modified (`git status --short`).
- [ ] `plans/README.md` plan 003 status is updated unless the reviewer maintains
  the index.

## STOP conditions

Stop and report back (do not improvise) if:

- Any current-state production, test, or changelog excerpt does not match the
  live source at `5b74317` after accounting for later drift.
- Either failure-regression test passes on the unmodified implementation. First
  confirm the large row actually reaches the custom stream during `writeRow`; if
  the fixture still does not reproduce the reported failure, stop rather than
  writing an untested fix.
- The change appears to require editing `CSVRowWriter.java`,
  `JsonLRowWriter.java`, `Pourover.java`, or the protected `writer` declaration.
- Existing code or tests demonstrate a requirement to run `postCloseHook()` after
  a failed close; that conflicts with this plan and needs maintainer review.
- Preserving behavior seems to require atomic files, partial-file deletion,
  stdout support, or a per-row flush/check. Those are explicitly out of scope.
- A verification command fails twice after a reasonable, in-scope fix attempt.

## Maintenance notes

- Any future `open` overload must put the same fail-fast adapter beneath its
  `PrintWriter`; otherwise that path will silently regain `PrintWriter`'s error
  swallowing.
- Subclasses remain source and binary compatible, but a subclass that replaces
  the protected `writer` with an unrelated `PrintWriter` owns that writer's error
  semantics. Review subclass changes with that distinction in mind.
- Reviewers should scrutinize cause preservation, close-only failures, the
  `finally` field clear, hook suppression on failure, and the absence of per-row
  flushing. Normal formatting should need no production diff outside
  `FileRowWriter`.
- Partial-file cleanup/atomic output and future stdout or split-output APIs are
  deliberately deferred; they should build on these failure semantics rather
  than be bundled into this bug fix.
