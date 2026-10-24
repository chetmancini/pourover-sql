# Pour-Over SQL playground

A small browser playground for Pour-Over SQL. Type a `SELECT`, pick a key case, and see the JSONL, the CSV and the Java call side by side. It runs the real library on an in-memory H2 database of fictional coffee orders, so it needs no Docker, no database server and no CLI.

## Run it

```
./gradlew :demo:run
```

Then open <http://localhost:8080>. Set `PORT` to use another port (`PORT=9000 ./gradlew :demo:run`).

You can also run `io.github.chetmancini.pouroversql.demo.Playground.main` from your IDE. A one-line JBang launcher will be available after the first release, once the library is published.

## Endpoints

- `GET /` serves the single-page UI.
- `GET /health` returns `ok`.
- `POST /query` takes `{"sql": "...", "keyCase": "default"|"snake"|"camel"}` and returns `{"jsonl", "csv", "javaSnippet", "rowCount", "truncated"}`. Rejections and SQL errors return HTTP 400 with `{"error": "..."}`.

## Safety

The playground runs SQL you type, so it is locked down:

- It binds to `127.0.0.1` only.
- One `SELECT` or `WITH` statement per request. A second statement, other commands, and H2 file or code functions (`FILE_READ`, `FILE_WRITE`, `CSVREAD`, `CSVWRITE`, `LINK_SCHEMA`, `RUNSCRIPT`, `SCRIPT`, `ALIAS`, `CALL`, `EXECUTE`) are rejected.
- Queries run as a database user that can only `SELECT` from the sample schema, with a 5 second timeout and a cap of 1,000 rows.
- `POST /query` requires `Content-Type: application/json`. Requests with a foreign `Origin` or a `Host` other than `localhost`/`127.0.0.1` on the right port are rejected, which blocks cross-site requests and DNS rebinding. Request bodies are capped at 64 KB.
- Errors return the database message, never a stack trace.

The seed data is in `src/main/resources/seed.sql`.
