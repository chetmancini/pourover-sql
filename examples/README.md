# Examples

Three short, standalone programs that use Pour-Over SQL against a tiny in-memory H2 table, plus one that only compiles. The three run with no Docker and no setup, and all four compile as part of `./gradlew build`.

| Example | Shows |
|---|---|
| `ListExample` | `Pourover.list`: all rows as a `List<Map<String, Object>>` |
| `StreamExample` | `Pourover.stream`: rows one at a time, with `try`-with-resources |
| `FileExample` | `Pourover.writeTo`: JSONL and CSV files, with snake_case keys |
| `ConnectExample` | `Pourover.connect` to a PostgreSQL server. Compile-only: it is never run because it needs a database |

Run one from the repository root:

```
./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.ListExample
./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.StreamExample
./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.FileExample
```

Without `-PmainClass`, `ListExample` runs. You can also run each `main` from your IDE.
