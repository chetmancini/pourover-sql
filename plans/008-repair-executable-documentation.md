# Plan 008: Repair executable documentation

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 5b74317..HEAD -- README.md CONTRIBUTING.md examples/README.md examples/src/main/java/io/github/chetmancini/pouroversql/examples/FileExample.java`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: docs
- **Planned at**: commit `5b74317`, 2026-11-06

## Why this matters

The README's `writeTo` example is not independently compilable because it uses `dir` and `snake` without showing their declarations, even though the compiled example already declares both. The examples page also ends with an ambiguous claim about running each `main`, although `ConnectExample` deliberately has no `main`. Finally, contributors are shown the fat-jar route but not the faster Gradle dry-run command. Moving the existing snippet boundary and making these small documentation corrections will give readers executable guidance without changing runtime behavior.

## Current state

- `README.md` — user-facing library and CLI documentation. Its `writeTo` block at lines 106–111 omits the declarations of `dir` and `snake`.
- `examples/src/main/java/io/github/chetmancini/pouroversql/examples/FileExample.java` — runnable H2 example and source of the README's `file` snippet. The declarations are immediately above the current start marker.
- `examples/src/test/java/io/github/chetmancini/pouroversql/examples/ReadmeSnippetsTest.java` — existing regression test; extracts every marked example region and asserts that its normalized contents occur verbatim in `README.md`. It needs no modification for this fix.
- `examples/README.md` — already has three run commands and an accurate table, but its final sentence can be read as saying every example has a runnable `main`.
- `examples/src/main/java/io/github/chetmancini/pouroversql/examples/ConnectExample.java` — compile-only PostgreSQL example; `listUsers` is a package-private method and there is no `main`.
- `CONTRIBUTING.md` — build/test commands; lacks the direct CLI dry run.
- `build.gradle.kts` — `application` points at `PouroverCommand`; `tasks.run` uses the normal runtime, CLI, and driver classpaths, while `shadowJar` separately assembles the driver-bundled fat jar. This file is context only.

The broken snippet boundary and its README rendering currently are:

```java
// examples/src/main/java/io/github/chetmancini/pouroversql/examples/FileExample.java:52-59
Pourover db = Pourover.of(dataSource);
FormatOptions snake = new FormatOptions(KeyCase.SNAKE);
Path dir = Files.createTempDirectory("pourover-example");
try {
    // snippet:start file
    Path jsonl = db.writeTo("SELECT * FROM cups", dir.resolve("cups.jsonl"), OutputFormat.JSONL, snake);
    Path csv = db.writeTo("SELECT * FROM cups", dir.resolve("cups.csv"), OutputFormat.CSV, snake);
    // snippet:end file
```

````markdown
<!-- README.md:106-111 -->
`writeTo` writes JSONL or CSV, with optional key-case conversion:

```java
Path jsonl = db.writeTo("SELECT * FROM cups", dir.resolve("cups.jsonl"), OutputFormat.JSONL, snake);
Path csv = db.writeTo("SELECT * FROM cups", dir.resolve("cups.csv"), OutputFormat.CSV, snake);
```
````

The examples page currently lists exactly these runnable commands:

```text
# examples/README.md:12-20
./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.ListExample
./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.StreamExample
./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.FileExample

Without `-PmainClass`, `ListExample` runs. You can also run each `main` from your IDE.
```

Its table already labels `ConnectExample` as “Compile-only: it is never run because it needs a database.” The source confirms that constraint:

```java
// ConnectExample.java:25-47
/**
 * Shows how to connect to a real database. It compiles with the build but is never run, because it
 * needs a PostgreSQL server; the other examples run against in-memory H2.
 */
// ...
static List<Map<String, Object>> listUsers(String password) {
    // snippet:start connect
    Pourover db = Pourover.connect(SqlEngine.POSTGRESQL,
            new ConnectionParams("localhost", 5432, "app", password, "main"));
    List<Map<String, Object>> users = db.list("SELECT * FROM users");
    // snippet:end connect
    return users;
}
```

The contributor command block currently contains only the slower/general entry points:

```text
# CONTRIBUTING.md:9-14
./gradlew build          # compile, unit tests, examples, demo tests
./gradlew test           # unit tests only
./gradlew javadoc        # must finish with no warnings
./gradlew :demo:run      # the browser playground on http://localhost:8080
```

Use the repository's existing documentation style: concise prose, fenced commands, and no new documentation framework. Java remains Java 21 with four-space indentation and no wildcard imports. The only Java edit in this plan is relocating the two snippet markers around the existing declarations and complete `try/finally`; all declaration, output, and cleanup statements stay unchanged.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Verify README extraction | `./gradlew :examples:test --tests 'io.github.chetmancini.pouroversql.examples.ReadmeSnippetsTest'` | exit 0; the snippet test passes |
| Run list example | `./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.ListExample` | exit 0; prints the H2 rows |
| Run stream example | `./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.StreamExample` | exit 0; prints the even-row count |
| Run file example | `./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.FileExample` | exit 0; prints JSONL and CSV output |
| Fast CLI dry run | `./gradlew run --console=plain -q --args='--dry --type postgresql'` | exit 0; prints dry-run parameters without connecting or prompting |
| Full regression/docs gate | `./gradlew build javadoc` | exit 0; all tests pass and Javadoc/doclint reports no warnings |
| Whitespace check | `git diff --check` | exit 0, no output |

The baseline at the planned-at commit passed `./gradlew build javadoc`. Unit tests and the three runnable examples use in-memory H2 and require no Docker or external service.

## Scope

**In scope** (the only source/documentation files you may modify):

- `README.md`
- `CONTRIBUTING.md`
- `examples/README.md`
- `examples/src/main/java/io/github/chetmancini/pouroversql/examples/FileExample.java`

The executor may additionally update this plan's existing row in `plans/README.md`, subject to the executor instructions above.

**Out of scope** (do NOT touch, even though they look related):

- `examples/src/test/java/io/github/chetmancini/pouroversql/examples/ReadmeSnippetsTest.java` — its existing extractor already handles the snippet-marker relocation; a test edit is not necessary. Do not weaken or rewrite it.
- `ConnectExample.java`, `ListExample.java`, `StreamExample.java`, Gradle files, and all production code — no source behavior changes are needed or permitted.
- `README.md` sections other than the `writeTo` Java block, and any broader documentation redesign, new examples, new commands, or changelog work.

## Git workflow

- Branch: `advisor/008-repair-executable-documentation`
- Make one logical commit; suggested message: `docs: repair executable documentation`
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Include the declarations and complete lifecycle in the `file` snippet

In `examples/src/main/java/io/github/chetmancini/pouroversql/examples/FileExample.java`, move only the snippet markers: place `// snippet:start file` immediately before the existing `FormatOptions snake = ...` declaration and `// snippet:end file` immediately after the existing `finally` block. Leave every declaration, `writeTo` call, print statement, and cleanup statement in its current position. Do not include `Pourover db = ...`; `db` is already established by the preceding library examples. The complete `try/finally` must be inside the region: moving `dir` into the `try` would make it invisible to `finally`, while extracting only part of the `try` would leave an unbalanced snippet.

The resulting source region must be exactly:

```java
Pourover db = Pourover.of(dataSource);
// snippet:start file
FormatOptions snake = new FormatOptions(KeyCase.SNAKE);
Path dir = Files.createTempDirectory("pourover-example");
try {
    Path jsonl = db.writeTo("SELECT * FROM cups", dir.resolve("cups.jsonl"), OutputFormat.JSONL, snake);
    Path csv = db.writeTo("SELECT * FROM cups", dir.resolve("cups.csv"), OutputFormat.CSV, snake);
    System.out.println("--- cups.jsonl");
    System.out.print(Files.readString(jsonl));
    System.out.println("--- cups.csv");
    System.out.print(Files.readString(csv));
} finally {
    Files.deleteIfExists(dir.resolve("cups.jsonl"));
    Files.deleteIfExists(dir.resolve("cups.csv"));
    Files.deleteIfExists(dir);
}
// snippet:end file
```

In `README.md`, replace the current two-call fenced block with the complete extracted region, normalized with no outer method indentation but preserving indentation inside `try` and `finally`:

```java
FormatOptions snake = new FormatOptions(KeyCase.SNAKE);
Path dir = Files.createTempDirectory("pourover-example");
try {
    Path jsonl = db.writeTo("SELECT * FROM cups", dir.resolve("cups.jsonl"), OutputFormat.JSONL, snake);
    Path csv = db.writeTo("SELECT * FROM cups", dir.resolve("cups.csv"), OutputFormat.CSV, snake);
    System.out.println("--- cups.jsonl");
    System.out.print(Files.readString(jsonl));
    System.out.println("--- cups.csv");
    System.out.print(Files.readString(csv));
} finally {
    Files.deleteIfExists(dir.resolve("cups.jsonl"));
    Files.deleteIfExists(dir.resolve("cups.csv"));
    Files.deleteIfExists(dir);
}
```

**Verify**: `./gradlew :examples:test --tests 'io.github.chetmancini.pouroversql.examples.ReadmeSnippetsTest'` → exit 0; the existing extraction test passes, proving the complete marked region and README agree verbatim after normalization.

### Step 2: Make IDE run guidance specific to the three runnable examples

In `examples/README.md`, preserve the existing introduction, four-row table, and three Gradle commands. Replace only the final sentence's ambiguous “run each `main`” wording with:

```markdown
Without `-PmainClass`, `ListExample` runs. You can also run those three `main` methods from your IDE; `ConnectExample` is compile-only and has no `main`.
```

Do not add a run command for `ConnectExample` and do not try to add a `main` method to it.

**Verify** each documented runnable program from the repository root:

1. `./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.ListExample` → exit 0 and prints the two H2 rows.
2. `./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.StreamExample` → exit 0 and prints the even-row count.
3. `./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.FileExample` → exit 0 and prints both output formats.

### Step 3: Document and exercise the fast CLI dry run

Add this line to the existing `CONTRIBUTING.md` “Build and test” fenced command block, without removing the fat-jar-related guidance elsewhere in the repository:

```sh
./gradlew run --console=plain -q --args='--dry --type postgresql'  # fast CLI dry run
```

Immediately below that block, add a short explanation: the normal `run` task compiles only what the run task needs and runs the CLI directly instead of assembling the all-driver fat jar; these arguments perform a dry run, so no database connection or password is needed. Keep this to one or two sentences and leave the existing statement about H2 unit tests intact.

**Verify**: `./gradlew run --console=plain -q --args='--dry --type postgresql'` → exit 0; it prints the selected parameters and neither connects to PostgreSQL nor prompts for a password.

### Step 4: Run the full regression and documentation gates

Run the complete build and Javadoc tasks, then check the patch for whitespace errors.

**Verify**:

- `./gradlew build javadoc` → exit 0; all unit/example/demo tests pass and Javadoc/doclint emits no warnings.
- `git diff --check` → exit 0 with no output.
- `git diff -- README.md CONTRIBUTING.md examples/README.md examples/src/main/java/io/github/chetmancini/pouroversql/examples/FileExample.java` → shows only the two snippet-marker relocations, the expanded README file example, the clarified examples sentence, and the new contributor command/explanation.

## Test plan

- Add no new test and modify no test: the existing `ReadmeSnippetsTest.everyMarkedSnippetAppearsVerbatimInReadme` is the regression test for this bug. Expanding the marked region around the declarations and complete `try/finally` makes its `file` snippet independently compilable, so the ordinary verbatim comparison now guards the complete example.
- `./gradlew :examples:test --tests 'io.github.chetmancini.pouroversql.examples.ReadmeSnippetsTest'` → all selected tests pass.
- Run all three documented H2 programs with their exact `:examples:run` commands → each exits 0. This checks that snippet/comment edits did not break executable examples.
- `./gradlew build javadoc` → full regression suite and public API doclint pass.
- The documented fast CLI command exits 0 without a database or credentials.

## Done criteria

- [ ] The `file` snippet starts before the existing `snake` and `dir` declarations and ends after the complete existing `finally`; no declaration, output, or cleanup statement moves or is duplicated.
- [ ] The README `writeTo` block contains exactly the extracted snippet, including `FormatOptions snake`, `Path dir`, and the balanced `try/finally`.
- [ ] `examples/README.md` names only `ListExample`, `StreamExample`, and `FileExample` as runnable and explicitly says `ConnectExample` is compile-only with no `main`.
- [ ] `CONTRIBUTING.md` includes `./gradlew run --console=plain -q --args='--dry --type postgresql'` and explains that `run` avoids assembling the all-driver fat jar.
- [ ] The focused README snippet test, all three runnable example commands, the fast dry run, and `./gradlew build javadoc` exit 0.
- [ ] `git diff --check` exits 0.
- [ ] No production behavior or test code changed, and no files outside the in-scope list were modified except the permitted `plans/README.md` status row.
- [ ] `plans/README.md` status row updated, unless the reviewer maintains the index.

## STOP conditions

Stop and report back (do not improvise) if:

- Any current-state excerpt no longer matches, particularly if `FileExample` now computes the output directory/options differently or the examples table/run commands have changed.
- The README snippet test cannot pass with the described marker relocation and verbatim README update; do not weaken its assertions or add a special case.
- Any of the three H2 examples requires an external service, or a failure appears to require a production-code or Gradle change.
- The exact dry-run command attempts a connection, prompts for a password, or does not exit 0.
- A step's verification fails twice after a reasonable documentation-only correction, or the fix appears to require an out-of-scope file.

## Maintenance notes

- Future edits to the `writeTo` calls or their setup must be made inside the marked `file` region and mirrored verbatim in `README.md`; keep declarations needed to understand a snippet inside that snippet's boundaries.
- If another example gains or loses a `main`, update both the run-command list and the final IDE sentence in `examples/README.md`. `ConnectExample` should remain compile-only unless it is deliberately redesigned to run without a real PostgreSQL service.
- Keep the direct `run` dry run distinct from `shadowJar`: the former is a fast contributor check, while the latter remains the packaging/distribution path.
- Reviewers should confirm that `FileExample.java` only relocates the two snippet markers, leaves every statement and the `dir` variable's method scope unchanged, and does not relax any snippet-test assertions.
