# Examples

Three short, standalone programs that use Pour-Over SQL against a tiny in-memory H2 table. They need no Docker and no setup, and they compile as part of `./gradlew build`.

| Example | Shows |
|---|---|
| `ListExample` | `Pourover.list`: all rows as a `List<Map<String, Object>>` |
| `StreamExample` | `Pourover.stream`: rows one at a time, with `try`-with-resources |
| `FileExample` | `Pourover.writeTo`: JSONL and CSV files, with snake_case keys |

Run one from the repository root:

```
./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.ListExample
./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.StreamExample
./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.FileExample
```

Without `-PmainClass`, `ListExample` runs. You can also run each `main` from your IDE.
