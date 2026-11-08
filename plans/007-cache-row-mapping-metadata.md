# Plan 007: Cache row-mapping metadata

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index. This plan assumes plans 004 and 006 have already landed;
> do not start against the pre-004/pre-006 implementations.
>
> **Drift check (run first)**: `git diff --stat 5b74317..HEAD -- src/main/java/io/github/chetmancini/pouroversql/util/ResultSets.java src/main/java/io/github/chetmancini/pouroversql/Pourover.java src/main/java/io/github/chetmancini/pouroversql/RowStream.java src/test/java/io/github/chetmancini/pouroversql/util/ResultSetsTest.java src/test/java/io/github/chetmancini/pouroversql/PouroverTest.java CHANGELOG.md`
> Changes from plan 004 in `RowStream`/`PouroverTest` and plan 006 in
> `ResultSets`/`ResultSetsTest`/`CHANGELOG.md` are expected prerequisites, not
> drift. Compare the other code against "Current state"; if those prerequisite
> changes are absent, or if there are unrelated mismatches, treat it as a STOP
> condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: MED
- **Depends on**: `plans/004-close-row-stream-on-runtime-failure.md` and `plans/006-materialize-direct-java-arrays.md`
- **Category**: perf
- **Planned at**: commit `5b74317`, 2026-11-06

## Why this matters

Every mapped row currently rereads JDBC metadata, rebuilds raw labels, and—for formatted file output—reconverts every key and allocates a second map. The per-row work is proportional to column count even though labels and formatted keys are fixed for a result set. A per-result-set `RowMapping` will perform metadata and key-case work once, reduce formatted output to one map per row, and centralize duplicate/collision behavior while retaining plan 006's value materialization and plan 004's stream cleanup.

## Current state

The excerpts below are the planned-at baseline. Apply them together with the explicit prerequisite notes rather than reverting either prerequisite.

- `src/main/java/io/github/chetmancini/pouroversql/util/ResultSets.java` — shared row mapping and value materialization. Baseline `toMap` at lines 63–70 rereads metadata on each call:
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
  `label(ResultSetMetaData, int)` uses `getColumnLabel`, falling back to `getColumnName` only when the label is null or empty. `columnLabels(ResultSet)` at lines 167–174 returns distinct raw labels at first occurrence. **Plan 006 prerequisite:** the private value path now also handles direct object/primitive arrays via `materializeArray`, with `byte[]` Base64 before generic arrays and JDBC `Array` freeing unchanged. Reuse `materialize`; do not duplicate or undo that logic.
- `src/main/java/io/github/chetmancini/pouroversql/FormatOptions.java` — public key converter, unchanged by this plan. Lines 54–69 currently return the same map for `DEFAULT`; otherwise they create a `LinkedHashMap`, convert each key, and throw on distinct-source formatted collisions:
  ```java
  public Map<String, Object> formatKeys(Map<String, Object> row) {
      if (keyCase == KeyCase.DEFAULT) {
          return row;
      }
      Map<String, Object> result = new LinkedHashMap<>(row.size() * 2);
      Map<String, String> sources = new HashMap<>(row.size() * 2);
      row.forEach((key, value) -> {
          String converted = keyCase.apply(key);
          String previous = sources.putIfAbsent(converted, key);
          if (previous != null) {
              throw new IllegalStateException("Columns '" + previous + "' and '" + key
                      + "' both map to key '" + converted + "' under " + keyCase + " key case");
          }
          result.put(converted, value);
      });
      return result;
  }
  ```
  Calling this once on a label-to-null map in the `RowMapping` constructor preserves its exact conversion order and collision message. `formatKeys` remains public and unchanged for callers with hand-built rows.
- `src/main/java/io/github/chetmancini/pouroversql/Pourover.java` — all eager modes and file output pass through private `run`. Lines 255–270 currently compute writer keys once but map and format per row:
  ```java
  writer.begin(columnKeys(resultSet, fo));
  long count = 0;
  while (resultSet.next()) {
      writer.writeRow(fo.formatKeys(ResultSets.toMap(resultSet)));
      count++;
      if (count % o.logFrequency() == 0) {
          logger.info("Handled {} rows...", count);
      }
  }
  ```
  `list` and `forEach` call `run` with `FormatOptions.defaults()` and a `CallbackRowWriter`; `writeTo` supplies JSONL/CSV formatting. `columnKeys` at lines 326–333 separately calls `ResultSets.columnLabels` and `fo.formatKeys` for `writer.begin`.
- `src/main/java/io/github/chetmancini/pouroversql/RowStream.java` — lazy streaming path. Baseline `advance` calls `ResultSets.toMap(resultSet)` at line 112 after `resultSet.next()` returns true. **Plan 004 prerequisite:** its runtime-failure handling now closes the result set, statement, and connection when result-set advancement or row mapping throws, while preserving `QueryException` wrapping for `SQLException`, transaction restoration, and idempotent cleanup. Consumer callbacks remain outside that catch. Insert the lazy mapping initialization inside the existing protected `try` path and leave plan 004's catches/cleanup intact.
- `src/test/java/io/github/chetmancini/pouroversql/util/ResultSetsTest.java` — dynamic-proxy `ResultSet`/metadata tests and plan 006 direct-array regressions. Extend these proxies to count metadata calls and model multiple current rows.
- `src/test/java/io/github/chetmancini/pouroversql/PouroverTest.java` — H2 coverage for `list`, `forEach`, `stream`, JSONL/CSV, nulls, duplicate/error cleanup, empty output, default/snake/camel key case, and formatted collisions. `keyCaseCollisionThrows` pins the exact message for `USER_ID` plus `userId` under `SNAKE`. Plan 004's stream failure cleanup tests must remain unchanged and green.
- `CHANGELOG.md` — add the performance improvement under `1.0.0 (unreleased)` → `Changed`, not as a row-format behavior change.

Compatibility invariants: raw duplicate labels retain first-position ordering but the later column's value wins; distinct raw labels that format to one key throw even for an empty write result; output keys retain column order; `DEFAULT`, `SNAKE`, and `CAMEL` behavior and null values are unchanged. No benchmark was taken during recon.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Focused mapping/materialization tests | `./gradlew test --tests 'io.github.chetmancini.pouroversql.util.ResultSetsTest'` | exit 0; metadata, collision, duplicate-label, and plan 006 array tests pass |
| Public query-mode and cleanup tests | `./gradlew test --tests 'io.github.chetmancini.pouroversql.PouroverTest'` | exit 0; all modes, key cases, empty results, and plan 004 cleanup tests pass |
| Full build and tests | `./gradlew build` | exit 0; `BUILD SUCCESSFUL` |
| Public Javadoc/doclint | `./gradlew javadoc` | exit 0; no Javadoc errors |

At the planned-at commit, the baseline `./gradlew build` and `./gradlew javadoc` passed.

## Scope

**In scope** (the only files you should modify):
- `src/main/java/io/github/chetmancini/pouroversql/util/ResultSets.java`
- `src/main/java/io/github/chetmancini/pouroversql/Pourover.java`
- `src/main/java/io/github/chetmancini/pouroversql/RowStream.java`
- `src/test/java/io/github/chetmancini/pouroversql/util/ResultSetsTest.java`
- `src/test/java/io/github/chetmancini/pouroversql/PouroverTest.java`
- `CHANGELOG.md`

**Out of scope** (do NOT touch, even though they look related):
- `FormatOptions.java` and `KeyCase.java`: reuse `formatKeys` once during mapping construction; do not change its public behavior or add another conversion algorithm.
- Public row shapes, method signatures of `Pourover`/`RowStream`, or the compatible `ResultSets.toMap(ResultSet)` signature.
- `ResultSets.columnLabels(ResultSet)` compatibility and all JDBC value-conversion/freeing semantics from plan 006.
- Cross-result-set caches, `ThreadLocal`, `WeakHashMap`, global/static mutable mapping state, concurrency support, or a benchmark suite.
- Duplicate per-mode array/key-case tests and unrelated writer or transaction refactors.

## Git workflow

- Branch: `advisor/007-cache-row-mapping-metadata`.
- Commit the cached mapping, callers, and regressions as one logical unit; use `perf: cache row mapping metadata`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Add a per-result-set cached `RowMapping`

In `ResultSets.java`, import `io.github.chetmancini.pouroversql.FormatOptions` and add a `public static final class RowMapping` nested in `ResultSets`. This small object belongs to one open `ResultSet`; do not add static caches.

Use this exact public surface:

```java
public RowMapping(ResultSet rs, FormatOptions options) throws SQLException
public List<String> outputKeys()
public Map<String, Object> mapCurrentRow() throws SQLException
```

Implement the constructor as follows:

1. Read `rs.getMetaData()` and `getColumnCount()` once. For every physical column, call the existing private `label(meta, i)` once and retain the full ordered raw-label list. Also build a `LinkedHashMap<String, Object>` containing each unique raw label at its first insertion position, with null values. Repeated labels do not move that position.
2. Call `options.formatKeys(...)` exactly once on the unique-label map. `DEFAULT` leaves labels untouched; non-default cases convert each unique label once and retain the existing exact collision exception/message. This check happens during construction even if no rows exist.
3. Cache the unique formatted keys as an immutable ordered `List<String>` (for example `List.copyOf`) for `outputKeys()`. Build a separate raw-to-formatted-key lookup by correlating the insertion-ordered key sets of the unique raw-label map and the formatted map pairwise; their keys correspond one-to-one, while their values are only null placeholders and must not be read as keys. Use that lookup to cache one non-null formatted output key for **every physical column** by its raw label; duplicate raw labels therefore share an output key. `outputKeys()` returns the unique list without touching JDBC metadata.
4. `mapCurrentRow()` allocates one `LinkedHashMap<String, Object>` and iterates every physical column in order. For each column it calls `rs.getObject(index)`, passes the value through the existing private `materialize(...)`, and puts it under that column's cached output key. A later duplicate therefore overwrites the value without changing first-position ordering, while earlier duplicate values are still read/materialized and any JDBC locators are freed exactly as before. It must not call `getMetaData`, `label`, `columnLabels`, `formatKeys`, or `KeyCase.apply`.

Add complete public Javadoc/doclint comments for `RowMapping`, its constructor, and both public methods: construction caches labels/formatted keys for one result set; duplicate raw labels keep first position and later value; formatted collisions throw `IllegalStateException`; `outputKeys` is ordered/unique; `mapCurrentRow` requires the result set to be positioned on a row, returns a new ordered map, maps SQL NULL to null, and uses the documented materialization rules. Include `@param`, `@return`, and `@throws` tags consistent with the surrounding code.

Keep `toMap(ResultSet)` source-compatible and behavior-compatible by delegating with default formatting:
```java
return new RowMapping(rs, FormatOptions.defaults()).mapCurrentRow();
```
This compatibility helper still constructs metadata per invocation, as a standalone call must; the performance gain comes from callers reusing one `RowMapping`. Leave `columnLabels(ResultSet)`, `label`, and the entire plan 006 materialization path intact.

**Verify**: `./gradlew test --tests 'io.github.chetmancini.pouroversql.util.ResultSetsTest'` → exit 0; existing raw-map and plan 006 materialization tests pass through the delegating compatibility method.

### Step 2: Reuse one mapping in eager/file and lazy stream callers

In `Pourover.run`, immediately after entering the try-with-resources for `statement`/`resultSet`, construct one `ResultSets.RowMapping mapping = new ResultSets.RowMapping(resultSet, fo);`. Call `writer.begin(mapping.outputKeys())` at the same point in the lifecycle as today—before the first `resultSet.next()`—and replace the loop body expression with `writer.writeRow(mapping.mapCurrentRow())`. This ensures formatted collisions still fail for empty results, gives CSV its empty-result header, and creates only one output map per row. Remove the now-unused private `columnKeys` method and any imports made unused (baseline `LinkedHashMap`; retain `ArrayList`, which `list` uses).

In `RowStream`, add a nullable instance field `private ResultSets.RowMapping mapping;`. In `advance`, only **after** `resultSet.next()` returns true, initialize it if null with `FormatOptions.defaults()`, then call `mapping.mapCurrentRow()`. Do not construct it in the `RowStream` constructor or before `next()`: an empty stream must finish and close without mapping/metadata work, preserving existing empty-stream behavior. Keep plan 004's runtime-error cleanup around result-set advancement, mapping initialization, and row mapping unchanged; leave consumer execution outside that catch exactly as plan 004 specifies.

**Verify**: `./gradlew test --tests 'io.github.chetmancini.pouroversql.PouroverTest'` → exit 0; list/forEach/stream/file behavior, empty CSV/JSONL, key cases, collisions, and plan 004 cleanup tests all pass.

### Step 3: Pin metadata counts and mapping edge cases

Extend `ResultSetsTest` with a reusable multi-row proxy. It should expose configurable raw labels and row values, advance a cursor from `next()`, return `values[cursor][columnIndex - 1]` from `getObject(int)`, and count `getMetaData`, `getColumnCount`, and each `getColumnLabel` invocation. Handle primitive-return methods deliberately where needed; do not add a mocking dependency.

Add focused `RowMapping` tests:

- **Cache across rows:** construct one mapping, read `outputKeys()`, and map at least two rows. Assert all row values and ordered keys, then assert `getMetaData` once, `getColumnCount` once, and `getColumnLabel` exactly once per physical column across the entire operation—not once per row.
- **Duplicate raw labels:** labels such as `v`, `other`, `v` produce output keys `v`, `other`; the `v` value comes from the third physical column (later wins), while `other` remains second. Assert `getObject` is called once for each of all three physical columns per mapped row, including the overwritten first `v`, so locator materialization/cleanup cannot be skipped. Also verify `toMap(ResultSet)` retains this behavior through its delegation.
- **Formatted collision:** distinct labels `USER_ID` and `userId` with `new FormatOptions(KeyCase.SNAKE)` throw in the `RowMapping` constructor with exactly `Columns 'USER_ID' and 'userId' both map to key 'user_id' under SNAKE key case`. No row is required.
- **Default/snake/camel and nulls:** construct fresh mappings with `FormatOptions.defaults()`, `new FormatOptions(KeyCase.SNAKE)`, and `new FormatOptions(KeyCase.CAMEL)` for a label such as `FirstName`; assert keys `FirstName`, `first_name`, and `firstName`, respectively, in both `outputKeys()` and mapped rows, and assert a SQL null remains a present key with a null value.

In `PouroverTest`, add an **empty-result formatted-collision** query (`SELECT ... WHERE FALSE` or an empty table with quoted aliases `"USER_ID"` and `"userId"` so H2 preserves their case) and assert `writeTo(..., new FormatOptions(KeyCase.SNAKE))` throws the same collision and closes all connections. Keep the existing non-empty collision test. Existing `emptyResultWritesCsvHeaderOnly`, `emptyResultWritesEmptyJsonl`, `streamClosesConnection`, `listReturnsRowsInColumnOrder`, `forEachReceivesEveryRowAndReturnsCount`, `writeToHonorsKeyCase`, and plan 004 failure-cleanup tests are the cross-mode compatibility suite; do not clone each edge case into every mode.

**Verify**: `./gradlew test --tests 'io.github.chetmancini.pouroversql.util.ResultSetsTest'` → exit 0; all new cached-mapping tests and plan 006 array tests pass.

**Verify**: `./gradlew test --tests 'io.github.chetmancini.pouroversql.PouroverTest'` → exit 0; the empty-result collision and every existing public-mode test pass.

### Step 4: Record the improvement and run final gates

Add one bullet under `CHANGELOG.md` → `1.0.0 (unreleased)` → `Changed`: JDBC column metadata and output-key case conversion are now cached per result set instead of repeated per row; mention no change to row shape. Do not claim a measured speedup.

**Verify**: `./gradlew build` → exit 0 and `BUILD SUCCESSFUL`.

**Verify**: `./gradlew javadoc` → exit 0 with no public `RowMapping` Javadoc/doclint errors.

## Test plan

- New `ResultSetsTest` proxy regressions instrument metadata calls across at least two `mapCurrentRow()` calls and prove one-time label reads.
- New behavior regressions cover duplicate raw-label later-wins/first-position semantics, exact formatted-collision failure, default/snake/camel output keys, column order, and null values.
- New `PouroverTest` coverage proves formatted collisions still occur before `writer.begin` completes on empty write results and resources close.
- Existing H2 tests cover all public modes (`list`, `forEach`, `stream`, JSONL/CSV `writeTo`), empty output, headers, and plan 004 cleanup; existing plan 006 tests prove the shared direct-array materialization remains active.
- Verification: both targeted test commands above → all pass, including the new cached-mapping and empty-collision regressions.
- Final regression gates: `./gradlew build` and `./gradlew javadoc` → both exit 0. No benchmark suite is required or permitted.

## Done criteria

- [ ] `ResultSets.RowMapping` is a public nested final class with the three specified public methods and complete Javadoc.
- [ ] One constructor reads all physical column labels once, converts unique labels once, and exposes immutable ordered unique output keys.
- [ ] `mapCurrentRow()` allocates exactly one output map per row and performs no metadata reads or key-case conversions.
- [ ] `mapCurrentRow()` calls the existing private `materialize` path, retaining plan 006 direct arrays, `byte[]` Base64, nested/null values, and JDBC locator freeing.
- [ ] `toMap(ResultSet)` remains available with raw labels and duplicate later-wins behavior.
- [ ] `Pourover.run` constructs the mapping before `writer.begin`; empty CSV headers and empty-result collision errors are preserved.
- [ ] `RowStream` initializes its mapping lazily only after the first successful `next()` and retains all plan 004 cleanup behavior.
- [ ] Metadata-count tests, duplicate-label tests, exact collision tests, default/snake/camel/null tests, and the existing all-mode suite pass.
- [ ] `FormatOptions.formatKeys` remains public and unchanged; no global cache, `ThreadLocal`, `WeakHashMap`, benchmark, or public row-shape change was introduced.
- [ ] `CHANGELOG.md` records the per-result-set caching without an unmeasured performance claim.
- [ ] Both targeted test commands exit 0; `./gradlew build` exits 0; `./gradlew javadoc` exits 0.
- [ ] No files outside the in-scope list are modified (`git status --short`), apart from the permitted `plans/README.md` status update.
- [ ] `plans/README.md` status row updated unless the reviewer maintains the index.

## STOP conditions

Stop and report back (do not improvise) if:

- Plan 004's runtime-failure stream cleanup or plan 006's direct-array materialization/regressions are absent; this plan depends on both and must not recreate or revert them.
- Unrelated code in the drift check differs from the baseline excerpts, or `FormatOptions.formatKeys` collision semantics differ from the quoted implementation.
- The design appears to require changing public row shapes, adding per-row metadata calls, converting duplicate raw labels more than once, or modifying an out-of-scope file.
- Empty-result collision/header behavior or duplicate raw-label later-wins ordering cannot be preserved with the specified constructor/caller placement.
- A step's verification fails twice after a reasonable fix attempt.

## Maintenance notes

- `RowMapping` is deliberately scoped to one result set. Do not replace it with a global/metadata cache: drivers own metadata lifetimes and equivalent labels across queries do not imply equivalent physical column mappings.
- Future value conversions belong in `ResultSets.materialize`/`materializeArray`, so compatibility `toMap`, eager queries, streams, and file output all inherit them.
- If key formatting changes, reviewers should ensure construction still performs collision detection on unique raw labels before any row is read and still preserves first-position/later-value duplicate semantics.
- Reviewers should scrutinize the lazy `RowStream` initialization and plan 004 exception paths, plus the fact that formatted file output now creates only the final output map. Performance measurements and broader cache designs are intentionally deferred.
