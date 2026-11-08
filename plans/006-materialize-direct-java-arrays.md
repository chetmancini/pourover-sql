# Plan 006: Materialize direct Java arrays

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 5b74317..HEAD -- src/main/java/io/github/chetmancini/pouroversql/util/ResultSets.java src/test/java/io/github/chetmancini/pouroversql/util/ResultSetsTest.java CHANGELOG.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `5b74317`, 2026-11-06

## Why this matters

`ResultSets.materialize` already converts the Java array inside a JDBC `Array` into nested lists, but a Java array returned directly by `ResultSet.getObject` falls through to `toString()` and becomes identity text such as `[Ljava.lang.String;@...` rather than exposing its elements; a direct `String[]` probe reproduced the bad value. Reusing the existing reflective conversion for direct object and primitive arrays gives all public query modes the same detached, element-wise representation without changing binary, JDBC locator, or driver-specific scalar handling.

## Current state

- `src/main/java/io/github/chetmancini/pouroversql/util/ResultSets.java` — shared materialization used by `toMap`, and therefore by `list`, `forEach`, `stream`, and formatted file output. Lines 73–135 currently contain:
  ```java
  private static Object materialize(Object value) throws SQLException {
      if (value == null) {
          return null;
      }
      if (value instanceof Array array) {
          try {
              return toList(array.getArray());
          } finally {
              free(array::free);
          }
      }
      // Clob, SQLXML and Blob branches omitted here; each reads and frees its locator.
      if (value instanceof byte[] bytes) {
          return base64(bytes);
      }
      if (value instanceof Number || value instanceof java.util.Date || value instanceof TemporalAccessor
              || isPlatformType(value.getClass())) {
          return value;
      }
      return value.toString();
  }

  private static List<Object> toList(Object array) throws SQLException {
      if (array == null) {
          return null;
      }
      if (!array.getClass().isArray()) {
          List<Object> single = new ArrayList<>(1);
          single.add(materialize(array));
          return single;
      }
      int length = java.lang.reflect.Array.getLength(array);
      List<Object> list = new ArrayList<>(length);
      for (int i = 0; i < length; i++) {
          Object element = java.lang.reflect.Array.get(array, i);
          // Multi-dimensional arrays (pgjdbc returns Integer[][]) nest; byte[] stays a Base64 leaf.
          boolean nested = element != null && element.getClass().isArray() && !(element instanceof byte[]);
          list.add(nested ? toList(element) : materialize(element));
      }
      return list;
  }
  ```
  The full method order is null → JDBC `Array` → `Clob` → `SQLXML` → `Blob` → `byte[]` → platform/scalar handling → `toString()`. Preserve that order except for inserting generic direct-array handling immediately **after** `byte[]`.
- `ResultSets.toMap(ResultSet)` at lines 63–70 is the public test entry point:
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
- `src/test/java/io/github/chetmancini/pouroversql/util/ResultSetsTest.java` — existing dynamic-proxy tests. `rowWithArray(Object javaArray)` wraps its input in a proxied JDBC `Array`, supplies one metadata column labelled `v`, and returns the JDBC `Array` from `ResultSet.getObject`. Existing tests already pin nested object/primitive JDBC arrays, three-dimensional arrays, byte-array leaves, and one-dimensional primitive arrays.
- `CHANGELOG.md` — user-visible value-materialization fixes belong in `1.0.0 (unreleased)` → `Fixed`; its behavior section currently describes SQL arrays but not direct Java arrays.

No claim was made or verified that any particular supported JDBC driver returns raw arrays. The regression uses the public JDBC helper with a proxy because it deterministically exercises this legal `ResultSet.getObject` value shape.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Focused materialization tests | `./gradlew test --tests 'io.github.chetmancini.pouroversql.util.ResultSetsTest'` | exit 0; all direct and JDBC array tests pass |
| Full build and tests | `./gradlew build` | exit 0; `BUILD SUCCESSFUL` |
| Public Javadoc/doclint | `./gradlew javadoc` | exit 0; no Javadoc errors |

At the planned-at commit, the baseline `./gradlew build` and `./gradlew javadoc` passed.

## Scope

**In scope** (the only files you should modify):
- `src/main/java/io/github/chetmancini/pouroversql/util/ResultSets.java`
- `src/test/java/io/github/chetmancini/pouroversql/util/ResultSetsTest.java`
- `CHANGELOG.md`

**Out of scope** (do NOT touch, even though they look related):
- `Pourover`, `RowStream`, `RowFormatter`, `FormatOptions`, and individual query modes: they already share `ResultSets.toMap`; do not add parallel array conversion or duplicate tests for every mode.
- JDBC `Array.free()` semantics, Clob/SQLXML/Blob handling, Base64 encoding, scalar/date handling, or the public row shape (`List<Object>` remains the array representation).
- Driver-specific capabilities or live-engine claims; no particular driver is asserted to return direct Java arrays.
- Row-mapping metadata caching, which is a separate plan.

## Git workflow

- Branch: `advisor/006-materialize-direct-java-arrays`.
- Commit the shared-path fix and regressions together; use `fix: materialize direct Java arrays`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Reuse the reflective array converter for direct arrays

In `ResultSets.java`, rename the existing private reflective `toList` helper to `materializeArray` so its role covers both JDBC-wrapped and direct arrays. Update its existing JDBC `Array` call and recursive nested-array call to the new name. Do not rewrite its null, scalar, reflection, or nesting logic.

In `materialize`, immediately after the existing `byte[]` Base64 branch and before the `Number`/date/platform-type branch, add:
```java
if (value.getClass().isArray()) {
    return materializeArray(value);
}
```

The order is load-bearing:

1. JDBC `Array` still enters its own branch, uses `materializeArray(array.getArray())`, and frees the locator in `finally`.
2. A direct or nested `byte[]` still becomes one Base64 string, never a list of integers.
3. Other direct object and primitive arrays now use reflection and recursively materialize their elements.
4. Non-array platform values and non-JDK driver-specific objects keep their current behavior.

Update the `toMap` materialization Javadoc so it states that both SQL `ARRAY` values and direct Java arrays become lists, with nested arrays nested and `byte[]` retained as the Base64 exception. Keep the existing locator-freeing statement.

**Verify**: `./gradlew test --tests 'io.github.chetmancini.pouroversql.util.ResultSetsTest'` → exit 0; all pre-existing JDBC-array tests pass after the private-helper rename.

### Step 2: Exercise direct values through the public `toMap` path

Refactor `ResultSetsTest`'s proxy setup just enough to add a `rowWithValue(Object value)` helper that returns `value` directly from one-column `ResultSet.getObject`. Keep `rowWithArray(Object javaArray)` for the existing JDBC `Array` tests; it may delegate its `ResultSet` construction to `rowWithValue(sqlArray)` to avoid duplicating proxy code. Preserve the metadata labels and existing tests.

Add tests through `ResultSets.toMap(...).get("v")` for:

- `new String[] {"a", "b"}` → `List.of("a", "b")`, not an array or identity string.
- `new int[] {1, 2}` → `List.of(1, 2)`, proving primitive reflection works.
- A nested object array containing a nested primitive/object array and a null element, for example `new Object[] {new String[] {"a"}, null}` → a list whose first element is `List.of("a")` and whose second element is null. Use AssertJ `isEqualTo(Arrays.asList(...))` or explicit element assertions; do not use `List.of` for a list containing null.
- Direct `new byte[] {1, 2, 3}` → exactly `"AQID"`, unchanged from existing Base64 behavior.
- An ordinary non-JDK PGobject-like test object whose `toString()` returns a fixed JSON-looking placeholder → that exact string. This pins the existing fallback; do not introduce a real driver dependency or a secret-shaped fixture.

Keep all existing JDBC `Array` tests, including `arrayOfByteArraysKeepsEachElementAsBase64`, to prove locator wrapping and nested binary behavior were not regressed.

**Verify**: `./gradlew test --tests 'io.github.chetmancini.pouroversql.util.ResultSetsTest'` → exit 0; the five new direct-value cases and every existing JDBC-array case pass.

### Step 3: Record the fix and run final gates

Add one concise bullet to `CHANGELOG.md` → `1.0.0 (unreleased)` → `Fixed`: direct Java object and primitive arrays returned by JDBC are now materialized as nested lists, while `byte[]` remains Base64. Phrase this as helper behavior, not as a claim about a named driver.

**Verify**: `./gradlew build` → exit 0 and `BUILD SUCCESSFUL`.

**Verify**: `./gradlew javadoc` → exit 0 with no Javadoc errors.

## Test plan

- Model all new tests on `ResultSetsTest.rowWithArray`'s existing dynamic-proxy style, but return the raw value from `getObject` and invoke only public `ResultSets.toMap`.
- New regressions: direct `String[]`, direct `int[]`, nested arrays with null, direct `byte[]` Base64, and non-array driver-object `toString()` fallback.
- Existing regressions: JDBC arrays still become nested lists and JDBC locators still follow the original `try/finally` free path.
- Verification: `./gradlew test --tests 'io.github.chetmancini.pouroversql.util.ResultSetsTest'` → all pass, including five new direct-value tests.
- Final regression gates: `./gradlew build` and `./gradlew javadoc` → both exit 0. Do not duplicate these tests across list/forEach/stream/writeTo because they all use this shared path.

## Done criteria

- [ ] `materialize` handles direct generic arrays immediately after the `byte[]` branch and before platform-type passthrough.
- [ ] JDBC `Array` and direct arrays reuse the same private reflective `materializeArray` path; JDBC `Array.free()` remains in `finally`.
- [ ] Direct object/primitive/nested arrays become detached nested `List<Object>` values; null elements remain null.
- [ ] Every `byte[]`, including one nested in another array, remains a Base64 leaf.
- [ ] Ordinary non-JDK scalar objects still use `toString()`.
- [ ] Five direct-value regressions exist in `ResultSetsTest` and use public `ResultSets.toMap`.
- [ ] `CHANGELOG.md` and the materialization Javadoc describe direct arrays without claiming a particular driver returns them.
- [ ] `./gradlew test --tests 'io.github.chetmancini.pouroversql.util.ResultSetsTest'` exits 0.
- [ ] `./gradlew build` exits 0.
- [ ] `./gradlew javadoc` exits 0.
- [ ] No files outside the in-scope list are modified (`git status --short`), apart from the permitted `plans/README.md` status update.
- [ ] `plans/README.md` status row updated unless the reviewer maintains the index.

## STOP conditions

Stop and report back (do not improvise) if:

- The current materialization branches or reflective helper differ materially from the excerpts after the drift check.
- The change would move generic array handling ahead of `byte[]`, bypass JDBC locator freeing, or alter the public map/list row shape.
- A test seems to require a driver-specific dependency, a live database, or changes in an individual query mode rather than the shared `ResultSets` path.
- A step's verification fails twice after a reasonable fix attempt.

## Maintenance notes

- Any future value-materialization branch must preserve the binary-before-generic-array ordering and the JDBC locator `try/finally` cleanup.
- Plan 007 will wrap this same private materialization path in a cached row-mapping object; it must not duplicate or bypass `materialize`.
- Reviewers should focus on recursive null/nesting behavior and binary leaves, not on adding driver-specific tests. No benchmark or live-engine follow-up is required by this fix.
