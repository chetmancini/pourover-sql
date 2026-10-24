# Security policy

## Supported versions

Pour-Over SQL is pre-1.0. Only the latest commit on the default branch (and, once there is one, the latest release) gets fixes.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting: open the repository's **Security** tab and choose **Report a vulnerability**. Do not open a public issue or pull request for a security problem.

Include what you found, how to reproduce it, and the version or commit you tested.

## What to expect

This is a one-person project, so these are goals and not guarantees. I aim to acknowledge a report within a week, and to either ship a fix or tell you why not within about 30 days. Reporters are credited in the changelog unless they ask not to be.

## The playground

The browser playground (`./gradlew :demo:run`) is a local demo. It binds to loopback (`127.0.0.1`), runs only `SELECT` queries against an in-memory sample database, and is not meant to be exposed to a network or run as a service. Reports about it are welcome, but running it on a public interface is outside what it is designed for.

## Handling credentials

The CLI reads the database password from the `EXTRACT_DB_PASSWORD` environment variable or a console prompt, never from a flag, and redacts it from `--dry` output and error messages.
