# Contributing

Thanks for taking a look. Bug reports, fixes and small features are welcome.

## Build and test

You need Java 21 and nothing else; the Gradle wrapper does the rest.

```
./gradlew build          # compile, unit tests, examples, demo tests
./gradlew test           # unit tests only
./gradlew javadoc        # must finish with no warnings
./gradlew :demo:run      # the browser playground on http://localhost:8080
```

The unit tests use an in-memory H2 database and need no services.

### End-to-end tests

```
./gradlew e2eTest
```

These start PostgreSQL and MySQL in Testcontainers, so they need Docker. Without Docker they are skipped. They are not part of `build`; CI runs them as a separate job. If you change anything that touches a real driver (clients, streaming, fetch size, transactions), run them before opening a pull request.

## Code style

Follow the surrounding code. Java 21, four-space indentation, no wildcard imports, and Javadoc on public API (doclint runs in `./gradlew javadoc`). Prefer a small test next to the change over a long explanation. Do not add a dependency without checking whether one already in the build does the job.

If you change anything the README shows, change the marked snippet in `examples/` as well. A test in `:examples` fails when the README and the compiled snippets drift apart.

## Commits and pull requests

- Commit messages use a short conventional prefix: `feat:`, `fix:`, `docs:`, `build:`, `test:`, `ci:`, `refactor:`. Keep the subject under about 72 characters and say why in the body when it is not obvious.
- One logical change per pull request. Say what changed, how you tested it, and whether e2e was run.
- `./gradlew build` and `./gradlew javadoc` pass.
- Add or update tests for behavior changes, and a line in `CHANGELOG.md` for anything a user would notice.
- Never commit credentials, `.env` files or real data. Use fictional data in tests and docs.

## Issues

Include the Pour-Over version, Java version, engine and driver version, what you ran, and what you expected. For security problems, do not open a public issue; see [SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your contribution is licensed under the Apache License, Version 2.0, the same as the project. Existing files keep their original copyright notices; new files carry "Copyright 2026 Chet Mancini" under Apache 2.0.
