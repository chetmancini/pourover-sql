# Plan 004: Close row streams on mapping runtime failures

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index. This plan assumes plan 001 has already landed; apply its
> `CredentialScrubber` constructor and `QueryException.wrap` changes first.
>
> **Drift check (run first)**:
>
> ```sh
> git diff --stat 5b74317..HEAD -- \
>   src/main/java/io/github/chetmancini/pouroversql/RowStream.java \
>   src/test/java/io/github/chetmancini/pouroversql/RowStreamTest.java \
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
- **Depends on**: 001
- **Category**: bug
- **Planned at**: commit `5b74317`, 2026-11-06

## Why this matters

`RowStream.advance` releases JDBC resources after a `SQLException`, but a runtime
exception or error from row mapping bypasses `close()` entirely. The audit's JDBC
dynamic-proxy probe observed no result-set, statement, or connection close after
such a failure, which can leak a pooled connection and cursor. Calling the
existing idempotent `close()` while preserving and rethrowing the original
throwable fixes the leak without changing the public streaming API or turning
programming failures into `QueryException`.

## Current state

Relevant files (the `RowStream` excerpts below show the required post-001 state):

- `src/main/java/io/github/chetmancini/pouroversql/RowStream.java` — lazy JDBC
  cursor owner; `advance` maps rows and `close` releases all three resources.
- `src/main/java/io/github/chetmancini/pouroversql/Pourover.java` — contains the
  initialization-failure cleanup pattern to mirror; it must not be edited.
- `src/main/java/io/github/chetmancini/pouroversql/util/ResultSets.java` — row
  mapper whose driver calls can throw runtime exceptions or errors; it must not
  be edited.
- `src/test/java/io/github/chetmancini/pouroversql/RowStreamTest.java` — new
  dynamic-proxy regression test class to create.
- `src/test/java/io/github/chetmancini/pouroversql/PouroverTest.java` — existing
  H2 stream lifecycle and `SQLException` behavior to keep passing unchanged.
- `CHANGELOG.md` — user-visible fixes belong under `1.0.0 (unreleased)` /
  `Fixed`.

The public contract and one-shot state are at `RowStream.java:33-96`:

```java
/**
 * A lazily read query result that holds an open connection, statement and result set until closed.
 * Use it in a try-with-resources block:
 *
 * <pre>{@code
 * try (RowStream rows = pourover.stream("SELECT * FROM events")) {
 *     rows.rows().forEach(row -> ...);
 * }
 * }</pre>
 *
 * <p>Closing the {@link Stream} returned by {@link #rows()} also closes this RowStream, and the
 * resources are released as soon as the last row has been read or a row fails to read.
 * {@link #close()} is idempotent.
 * Read errors surface as {@link QueryException}. Instances are not meant to be shared between threads.
 */
public final class RowStream implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RowStream.class);

    private final Connection connection;
    private final Statement statement;
    private final ResultSet resultSet;
    private final CredentialScrubber scrubber;
    private final int logFrequency;
    private final boolean managedTransaction;

    private boolean rowsCalled;
    private boolean exhausted;
    private boolean closed;
    private long count;

    RowStream(Connection connection, Statement statement, ResultSet resultSet, CredentialScrubber scrubber, int logFrequency,
              boolean managedTransaction) {
        this.connection = connection;
        this.statement = statement;
        this.resultSet = resultSet;
        this.scrubber = scrubber;
        this.logFrequency = logFrequency;
        this.managedTransaction = managedTransaction;
    }

    /**
     * Returns the rows as a sequential, ordered stream of maps keyed by column label in column order.
     * May be called only once.
     * @return the row stream
     * @throws IllegalStateException if called twice or after {@link #close()}
     */
    public Stream<Map<String, Object>> rows() {
        if (rowsCalled) {
            throw new IllegalStateException("rows() may only be called once");
        }
        if (closed) {
            throw new IllegalStateException("RowStream is closed");
        }
        rowsCalled = true;
        Spliterator<Map<String, Object>> spliterator = new Spliterators.AbstractSpliterator<>(
                Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
            @Override
            public boolean tryAdvance(Consumer<? super Map<String, Object>> action) {
                return advance(action);
            }
        };
        return StreamSupport.stream(spliterator, false).onClose(this::close);
    }
```

The defect is the single `catch (SQLException)` around `next()` and `toMap()` at
`RowStream.java:98-127`:

```java
    private boolean advance(Consumer<? super Map<String, Object>> action) {
        if (exhausted) {
            return false;
        }
        if (closed) {
            throw new IllegalStateException("RowStream is closed");
        }
        Map<String, Object> row;
        try {
            if (!resultSet.next()) {
                exhausted = true;
                close();
                return false;
            }
            row = ResultSets.toMap(resultSet);
        } catch (SQLException e) {
            try {
                close();
            } catch (QueryException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw QueryException.wrap(e, scrubber);
        }
        count++;
        if (count % logFrequency == 0) {
            logger.info("Streamed {} rows...", count);
        }
        action.accept(row);
        return true;
    }
```

`action.accept(row)` is deliberately outside this `try`. Keep it there: consumer
exceptions are not the mapping-failure bug in this plan.

`ResultSets.toMap` calls both metadata and driver value methods
(`ResultSets.java:63-71`), any of which can throw an unchecked driver failure:

```java
    public static Map<String, Object> toMap(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        Map<String, Object> row = new LinkedHashMap<>(columns * 2);
        for (int i = 1; i <= columns; i++) {
            row.put(label(meta, i), materialize(rs.getObject(i)));
        }
        return row;
    }
```

The existing close method already has the required ordering and idempotence.
Reuse it rather than adding another resource loop (`RowStream.java:129-158`):

```java
    /**
     * Releases the result set, statement and connection. Safe to call more than once.
     * @throws QueryException if the driver fails to release a resource
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        SQLException failure = null;
        for (AutoCloseable resource : new AutoCloseable[] {resultSet, statement, connection}) {
            try {
                if (resource == connection) {
                    Pourover.endTransaction(connection, managedTransaction);
                }
                resource.close();
            } catch (Exception e) {
                SQLException sqlException = e instanceof SQLException s ? s : new SQLException(e);
                if (failure == null) {
                    failure = sqlException;
                } else {
                    failure.addSuppressed(sqlException);
                }
            }
        }
        if (failure != null) {
            throw QueryException.wrap(failure, scrubber);
        }
    }
```

The pattern to mirror is `Pourover.stream` initialization at
`Pourover.java:182-192`: SQL failures are wrapped, while runtime failures and
errors are cleaned up and rethrown unchanged.

```java
        } catch (SQLException e) {
            closeAfterFailure(e, statement);
            endTransaction(connection, managedTransaction);
            closeAfterFailure(e, connection);
            throw QueryException.wrap(e, scrubber);
        } catch (RuntimeException | Error e) {
            closeAfterFailure(e, statement);
            endTransaction(connection, managedTransaction);
            closeAfterFailure(e, connection);
            throw e;
        }
```

`Pourover.closeAfterFailure` (`Pourover.java:352-363`) also demonstrates that
cleanup failure belongs on the original throwable as suppressed, not in place of
it:

```java
    private static void closeAfterFailure(Throwable failure, AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception e) {
                failure.addSuppressed(e);
            }
        }
    }
```

Existing SQL-read behavior is pinned by `PouroverTest.java:407-418` and must stay
unchanged:

```java
    @Test
    void streamClosesConnectionWhenReadFails() throws Exception {
        // Lazy execution makes H2 evaluate each row on next(), so row 2 fails mid-stream.
        H2DataSource lazy = new H2DataSource(";LAZY_QUERY_EXECUTION=1");
        lazy.exec("CREATE TABLE t (n INT)", "INSERT INTO t VALUES (1), (2), (3)");
        List<Object> seen = new ArrayList<>();
        RowStream stream = Pourover.of(lazy).stream("SELECT 10 / (n - 2) AS \"q\" FROM t");
        assertThatThrownBy(() -> stream.rows().forEach(row -> seen.add(row.get("q"))))
                .isInstanceOf(QueryException.class);
        assertThat(seen).containsExactly(-10);
        assertThat(lazy.allConnectionsClosed()).isTrue();
    }
```

Repository conventions from `CONTRIBUTING.md`: Java 21, four-space indentation,
no wildcard imports, Javadoc on public API, JUnit 6 and AssertJ, and no new
dependency for this change. `H2DataSource.java:127-155` and
`PouroverTest.java:147-166` are existing dynamic-proxy exemplars. The new test
uses direct JDBC proxies and the package-private `RowStream` constructor, with
`managedTransaction=false` so no transaction calls are involved.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| New focused tests | `./gradlew test --tests 'io.github.chetmancini.pouroversql.RowStreamTest'` | exit 0; all four new tests pass after the fix |
| Existing stream/API regressions | `./gradlew test --tests 'io.github.chetmancini.pouroversql.PouroverTest'` | exit 0; existing H2 behavior unchanged |
| Full build | `./gradlew build` | exit 0; unit, examples, and demo tests pass |
| Public API docs | `./gradlew javadoc` | exit 0 with no Javadoc warnings |
| Whitespace check | `git diff --check` | exit 0, no output |

The supplied audit baseline at `5b74317` had `./gradlew build` and
`./gradlew javadoc` passing. The intentional red regression-test run in Step 1 is
expected and is not a new baseline failure.

## Scope

**In scope** (the only files to modify):

- `src/main/java/io/github/chetmancini/pouroversql/RowStream.java`
- `src/test/java/io/github/chetmancini/pouroversql/RowStreamTest.java` (create)
- `CHANGELOG.md`
- `plans/README.md` — only this plan's status row, and only if the reviewer is
  not maintaining the index

**Out of scope** (do not touch or implement):

- Consumer exceptions from `action.accept`: do not move that call into the
  mapping `try`, wrap those exceptions, or add new consumer-failure semantics.
- Automatic closure when a caller obtains or discards a stream without consuming
  or closing it, a cancellation API, cleaner/finalizer fallback, or a timeout.
- Converting runtime mapping failures or `Error`s to `QueryException`, or
  changing the existing `SQLException` wrapping and secret-scrubbing path.
- `Pourover.java`, `ResultSets.java`, transaction handling, logging frequency,
  public method signatures, and the `rows()` one-shot checks.
- Metadata caching, row-materialization refactors, and other work belonging to
  later plans.
- Docker/Testcontainers changes or new external services.

## Git workflow

- Branch: `advisor/004-close-row-stream-on-runtime-failure`
- Make one logical commit; suggested message:
  `fix: close row streams after mapping failures`
- Match the repository's short conventional-commit style, for example
  `ec40641 fix: render multi-dimensional SQL arrays as nested lists`.
- Do not push or open a PR unless the operator instructs it.

## Steps

### Step 1: Add dynamic-proxy mapping-failure regression tests

Create `RowStreamTest` in package `io.github.chetmancini.pouroversql`, using the
same license header as `PouroverTest`. Add exactly these focused tests:

1. `mappingRuntimeExceptionClosesResourcesAndPropagates`
2. `mappingErrorClosesResourcesAndPropagates`
3. `closeFailureIsSuppressedOnMappingRuntimeFailure`
4. `successfulStreamReturnsRowsAndClosesOnce`

Build a small test fixture around `Proxy.newProxyInstance`; do not use Mockito or
add a dependency. A generic helper may cast the proxy to the requested JDBC
interface. The fixture should hold a `List<String>` of close events and construct:

- A `ResultSetMetaData` proxy returning `1` from `getColumnCount()` and `"n"`
  from `getColumnLabel(1)`.
- A `ResultSet` proxy whose first `next()` returns `true` and whose next call
  returns `false` in the success case; `getMetaData()` returns the metadata
  proxy. In failure cases, `getObject(1)` throws the exact supplied runtime
  exception or error. Record `resultSet.close`; optionally throw a supplied
  `SQLException` from that close after recording the event.
- `Statement` and `Connection` proxies that record `statement.close` and
  `connection.close`. With `managedTransaction=false`, no rollback or autocommit
  method should be called.
- A direct `new RowStream(connection, statement, resultSet, CredentialScrubber.empty(), 100_000, false)`.
  Plan 001's package-private empty scrubber is intentional test input; do not add credentials.

Have unexpected proxy method calls throw `UnsupportedOperationException` with
the method name, so the tests expose accidental extra JDBC interactions instead
of returning misleading primitive defaults.

For each mapping-failure test, consume with `stream.rows().toList()` and capture
the throwable. Assert it is the **same instance** supplied to `getObject`, not a
`QueryException`. Assert close events are exactly, in order:

```text
resultSet.close, statement.close, connection.close
```

Call `stream.close()` once after the failure and assert the same three events are
still present exactly once; this proves the existing `closed` flag prevents a
second cleanup. In the runtime-exception test, also assert a second `rows()` call
throws `IllegalStateException` with message `rows() may only be called once`,
preserving the existing check order and one-shot semantics even though the
stream is now closed. Use `IllegalStateException("mapping failed")` for the
runtime case and `AssertionError("mapping failed")` for the error case; both must
originate in `ResultSet.getObject`, not in the row consumer.

For the suppression case, use a mapping `IllegalStateException` and a distinct
`SQLException("close failed")` from `resultSet.close()`. Assert the mapping
exception remains primary, has exactly one suppressed exception, that suppressed
exception is a `QueryException`, and its cause is the exact close
`SQLException`. Assert all three resources were still closed once. For the
ordinary success case, return value `1` from `getObject(1)`, assert the result is
one map equal to `Map.of("n", 1)`, assert automatic close at exhaustion in the
same order, and verify an explicit later `close()` adds no events.

**Verify the tests reproduce the bug before changing production code**:

```sh
./gradlew test --tests 'io.github.chetmancini.pouroversql.RowStreamTest'
```

Expected on the unmodified implementation: nonzero exit. The runtime, error, and
close-suppression tests fail because no resource-close events and no suppressed
close failure are produced; the ordinary-success test passes. If mapping failures
already produce close events before the production edit, stop and reconcile the
fixture/drift rather than claiming a regression was reproduced.

### Step 2: Close and rethrow unchecked mapping failures

In `RowStream.advance`, keep the existing `try` and `catch (SQLException)` exactly
as they are. Add this catch immediately after the `SQLException` catch:

```java
        } catch (RuntimeException | Error e) {
            try {
                close();
            } catch (RuntimeException | Error closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
```

The resulting structure must be the SQL catch followed by the unchecked catch,
then the existing count/logging and `action.accept(row)` outside the `try`.
Catching `RuntimeException | Error` from `close()` includes its normal
`QueryException` wrapper and also prevents an unusual cleanup error from masking
the original mapping failure. The original throwable is always rethrown; cleanup
failures are only attached as suppressed.

Do not extract a second resource-closing routine and do not call each JDBC
resource directly. `close()` marks the stream closed before attempting resources,
attempts all resources, preserves transaction behavior, and makes a caller's
later close a no-op. Do not catch `Throwable`, move `action.accept`, reset
`rowsCalled`, or wrap `e` in `QueryException`.

**Verify focused and existing behavior**:

```sh
./gradlew test --tests 'io.github.chetmancini.pouroversql.RowStreamTest'
./gradlew test --tests 'io.github.chetmancini.pouroversql.PouroverTest'
```

Expected: both commands exit 0. All four new tests pass, including exact resource
order and suppression; all existing `PouroverTest` tests, including
`streamClosesConnectionWhenReadFails`, still pass unchanged.

### Step 3: Clarify the Javadoc and record the fix

The current class sentence `Read errors surface as QueryException` is too broad
once unchecked mapping failures are explicitly preserved. Update that sentence
in `RowStream.java` to:

```java
 * SQL read errors surface as {@link QueryException}; runtime mapping failures and errors propagate unchanged
 * after resources are released. Instances are not meant to be shared between threads.
```

Do not otherwise rewrite the class or method documentation. In particular,
`close()` still throws `QueryException` when called directly and its resource
contract is unchanged.

Add one bullet under `CHANGELOG.md` → `1.0.0 (unreleased)` → `Fixed`:

```markdown
- `RowStream` now releases its result set, statement and connection when row mapping throws a runtime exception or error; the original throwable is rethrown, with any cleanup failure suppressed.
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
`RowStream.java`, new `RowStreamTest.java`, `CHANGELOG.md`, and optionally
`plans/README.md` when this executor owns the index. Set plan 004 to `DONE` only
after all checks pass, or to `BLOCKED` with a one-line reason if stopped. Create
the single logical commit with the message above; never push or open a PR without
operator instruction.

## Test plan

- New `RowStreamTest.mappingRuntimeExceptionClosesResourcesAndPropagates`: the
  original runtime exception survives unchanged, resources close once in order,
  explicit close is idempotent, and `rows()` remains one-shot.
- New `RowStreamTest.mappingErrorClosesResourcesAndPropagates`: an
  `AssertionError` from mapping is neither swallowed nor wrapped, and all JDBC
  resources close once.
- New `RowStreamTest.closeFailureIsSuppressedOnMappingRuntimeFailure`: a result
  set close `SQLException` becomes a suppressed `QueryException` on the original
  mapping failure; statement and connection cleanup still occur.
- New `RowStreamTest.successfulStreamReturnsRowsAndClosesOnce`: a normal one-row
  result is unchanged, exhaustion closes resources, and later close is a no-op.
- Existing `PouroverTest.streamClosesConnectionWhenReadFails`: `SQLException`
  still surfaces as `QueryException` and closes the H2 connection.
- Existing `PouroverTest.streamClosesConnection`,
  `closingRowsStreamClosesConnection`, `fullyReadStreamReleasesConnectionEvenWithoutClose`,
  and `rowsCanOnlyBeCalledOnce`: normal lifecycle and one-shot behavior remain
  intact without editing those tests.
- Verification: both targeted commands in Step 2 exit 0; `./gradlew build` then
  covers the complete unit/examples/demo suite.

## Done criteria

- [ ] `advance` catches `RuntimeException | Error` only around result-set
  advancement/mapping, invokes existing `close()`, adds cleanup failure as
  suppressed, and rethrows the original instance.
- [ ] `action.accept(row)` remains outside the mapping `try`; no consumer
  behavior changed.
- [ ] Existing `SQLException` handling, `QueryException.wrap`, `close()`, and
  transaction logic are unchanged.
- [ ] `rowsCalled`, `exhausted`, and `closed` semantics and all public signatures
  are unchanged; repeated close does not repeat JDBC closes.
- [ ] The four named dynamic-proxy tests pass, and existing `PouroverTest` passes
  unchanged.
- [ ] `RowStream` Javadoc distinguishes SQL read errors from unchecked mapping
  failures, and `CHANGELOG.md` contains the new `Fixed` bullet.
- [ ] `./gradlew build` exits 0.
- [ ] `./gradlew javadoc` exits 0 with no warnings.
- [ ] `git diff --check` exits 0.
- [ ] No files outside the in-scope list are modified (`git status --short`).
- [ ] `plans/README.md` plan 004 status is updated unless the reviewer maintains
  the index.

## STOP conditions

Stop and report back (do not improvise) if:

- Any current-state production, test, or changelog excerpt does not match the
  live source at `5b74317` after accounting for later drift.
- The new failure tests already observe resource closes on the unmodified code,
  or the proxy fixture cannot make `getObject` throw the exact test throwable.
  The regression evidence is then invalid or the code has drifted.
- A correct fix appears to require editing `Pourover.java`, `ResultSets.java`,
  `close()`, transaction handling, or a public signature.
- Preserving the original mapping throwable requires moving or catching around
  `action.accept`; consumer behavior is explicitly out of scope.
- Existing tests require runtime mapping failures to become `QueryException`, or
  require a second `rows()` call to report something other than the existing
  one-shot violation; those are compatibility conflicts to report, not tests to
  weaken.
- A verification command fails twice after a reasonable, in-scope fix attempt.

## Maintenance notes

- Future row-mapping or metadata-caching work must keep the unchecked catch
  around every driver/mapping operation that occurs before a row is emitted.
  Plan 007 depends on these regression tests protecting that refactor.
- Keep consumer callbacks outside the resource/mapping catch. Broadening it
  would silently change consumer exception semantics beyond this bug fix.
- Reviewers should check throwable identity, suppressed cleanup failures,
  close-once behavior, and unchanged SQL exception scrubbing. A duplicate JDBC
  close loop is unnecessary and risks diverging from `close()` transaction
  handling.
- Automatic cleanup for abandoned, unconsumed streams and explicit cancellation
  remain separate design problems; this plan only handles failures encountered
  while advancing an active stream.
