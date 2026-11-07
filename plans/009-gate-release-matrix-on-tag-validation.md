# Plan 009: Gate the release matrix on tag validation

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 5b74317..HEAD -- .github/workflows/release.yml`
> If the in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: dx
- **Planned at**: commit `5b74317`, 2026-11-06

## Why this matters

A version mismatch currently fails in the `library` job, but the seven-job CLI matrix has no dependency on that validation and starts anyway. This wastes CI time and compute on tags that cannot produce a coherent release. A small `check-tag` prerequisite will reject a bad tag before either build starts while keeping library and CLI builds parallel; manual dispatch will still build artifacts without creating a GitHub release.

## Current state

`.github/workflows/release.yml` is the only release workflow and the only file in scope. Its triggers are tag pushes and manual dispatch:

```yaml
# .github/workflows/release.yml:13-19
on:
  push:
    tags: ["v*"]
  workflow_dispatch:

permissions:
  contents: read
```

The `library` job currently owns the only version check. Its validation step and explanatory comment are at lines 34–45:

```yaml
      # Tag pushes must match the project version; manual runs skip this check
      # because the project version is a SNAPSHOT.
      - name: Verify tag matches project version
        if: github.ref_type == 'tag'
        env:
          TAG: ${{ github.ref_name }}
        run: |
          version="$(sed -n 's/^version=//p' gradle.properties)"
          if [ "${TAG#v}" != "$version" ]; then
            echo "Tag $TAG does not match gradle.properties version $version" >&2
            exit 1
          fi
```

The comparison reads `version` from `gradle.properties`; the planned-at value is a SNAPSHOT, which is why manual dispatch must skip the tag comparison. The shell check itself needs only the checked-out files and standard Unix tools, not Java or Gradle.

The `cli` job at lines 73–105 has no `needs`. Its matrix has seven entries:

```yaml
  cli:
    name: CLI jar (${{ matrix.name }})
    runs-on: ubuntu-latest
    timeout-minutes: 30
    strategy:
      fail-fast: false
      matrix:
        include:
          - { name: all, engines: "" }
          - { name: postgres, engines: postgres }
          - { name: mysql, engines: mysql }
          - { name: sqlserver, engines: sqlserver }
          - { name: snowflake, engines: snowflake }
          - { name: redshift, engines: redshift }
          - { name: informix, engines: informix }
```

The downstream release job already has the required dependencies and manual-dispatch guard:

```yaml
  github-release:
    name: Create GitHub Release
    if: github.ref_type == 'tag'
    needs: [library, cli]
```

The header comment at lines 4–7 already says that tag pushes check the version and create a release, while manual runs upload workflow artifacts only and never create a release. That remains true after this refactor; preserve it. Preserve the build, test, smoke-test, artifact, publishing, signing, and disabled Maven Central sections verbatim. Match the workflow's existing two-space indentation, inline job dependencies for one prerequisite, and bracketed dependency list for `github-release`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Lint workflow | `actionlint .github/workflows/release.yml` | exit 0, no diagnostics (installed; baseline also exits 0) |
| Check dependency structure | Run the `python3` standard-library script in Step 2 | exit 0, prints `release dependency checks passed` |
| Check whitespace | `git diff --check` | exit 0, no output |
| Review scope | `git diff -- .github/workflows/release.yml` | only the new prerequisite job, moved validation step/comment, and two new `needs` entries |

No tag push, workflow dispatch, GitHub release, package publication, or credentials are needed for verification.

## Scope

**In scope** (the only workflow/source file you may modify):

- `.github/workflows/release.yml`

The executor may additionally update this plan's existing row in `plans/README.md`, subject to the executor instructions above.

**Out of scope** (do NOT touch, even though they look related):

- Every other workflow and every Gradle, Java, documentation, and release-configuration file.
- Publishing and local staging, signing, Maven Central configuration (including commented configuration), artifact names/contents, checkout/setup action versions, library tests, CLI build commands, smoke tests, engine matrix entries, and `fail-fast: false`.
- The `github-release` dependency list, release creation command, permissions, and `if: github.ref_type == 'tag'` guard.
- Any new CI service, action, YAML utility dependency, branch/tag rule, or actual release dispatch.

## Git workflow

- Branch: `advisor/009-gate-release-matrix-on-tag-validation`
- Make one logical commit; suggested message: `ci: gate release builds on tag validation`
- Do NOT push or open a PR unless the operator instructed it. In particular, do not push a fake tag to test this change.

## Steps

### Step 1: Extract tag validation into a lightweight prerequisite job

In `.github/workflows/release.yml`, add a `check-tag` job immediately before `library`. Use only checkout and the existing validation step; do not add Java or Gradle setup. The complete new job must have this shape, with the existing comment and shell body moved verbatim:

```yaml
  check-tag:
    name: Check tag
    if: github.ref_type == 'tag' || github.event_name == 'workflow_dispatch'
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@v7

      # Tag pushes must match the project version; manual runs skip this check
      # because the project version is a SNAPSHOT.
      - name: Verify tag matches project version
        if: github.ref_type == 'tag'
        env:
          TAG: ${{ github.ref_name }}
        run: |
          version="$(sed -n 's/^version=//p' gradle.properties)"
          if [ "${TAG#v}" != "$version" ]; then
            echo "Tag $TAG does not match gradle.properties version $version" >&2
            exit 1
          fi
```

The job-level condition explicitly admits both supported event types. On a tag, the step performs the existing comparison; on manual dispatch, checkout succeeds and the comparison step is skipped, so downstream manual builds still run.

Delete the validation step and its two-line comment from `library`. Do not leave a second copy. Add the single prerequisite to both build jobs:

```yaml
  library:
    name: Library jars and staging repository
    needs: check-tag
    runs-on: ubuntu-latest
```

```yaml
  cli:
    name: CLI jar (${{ matrix.name }})
    needs: check-tag
    runs-on: ubuntu-latest
```

Do not add `library` to `cli.needs`: after `check-tag` succeeds, both build jobs must remain eligible to run in parallel. Leave `github-release` unchanged, including `needs: [library, cli]` and its tag-only `if` condition. No existing comment becomes stale when the validation comment moves with its step; preserve the header and all other comments as written.

**Verify**: `actionlint .github/workflows/release.yml` → exit 0 with no diagnostics.

### Step 2: Check the job graph and event guards structurally

Run this standard-library Python check from the repository root. It checks the relevant job blocks rather than relying only on visual inspection; `actionlint` remains responsible for full workflow syntax validation.

```sh
python3 - <<'PY'
from pathlib import Path
import re

lines = Path('.github/workflows/release.yml').read_text().splitlines()
starts = {}
for index, line in enumerate(lines):
    match = re.fullmatch(r'  ([a-z][a-z0-9-]*):', line)
    if match:
        starts[match.group(1)] = index

def block(name):
    start = starts[name]
    later = [index for index in starts.values() if index > start]
    return lines[start:min(later) if later else len(lines)]

def needs(name):
    declarations = [line.strip() for line in block(name) if line.startswith('    needs:')]
    assert len(declarations) == 1, f'{name} must have one needs declaration'
    return declarations[0]

assert needs('library') == 'needs: check-tag'
assert needs('cli') == 'needs: check-tag'
assert needs('cli') != 'needs: library'
assert needs('github-release') == 'needs: [library, cli]'
assert "    if: github.ref_type == 'tag'" in block('github-release')
assert "    if: github.ref_type == 'tag' || github.event_name == 'workflow_dispatch'" in block('check-tag')
assert not any('Verify tag matches project version' in line for line in block('library'))
assert sum('Verify tag matches project version' in line for line in block('check-tag')) == 1
print('release dependency checks passed')
PY
```

**Verify**: the script exits 0 and prints exactly `release dependency checks passed`. The equality assertion for `cli` also proves it does not depend on `library`; the `github-release` assertions prove its dependency fan-in and manual-dispatch release guard remain intact.

### Step 3: Review the patch and whitespace

Inspect the one-file diff and confirm there are no collateral workflow changes.

**Verify**:

- `git diff --check` → exit 0 with no output.
- `git diff -- .github/workflows/release.yml` → shows one new `check-tag` job, removal of the old validation step/comment from `library`, and `needs: check-tag` under `library` and `cli`; all build, matrix, artifact, release, signing, and publishing content is otherwise unchanged.

Do not push a tag or manually dispatch the workflow as a verification substitute. The required evidence is local static lint plus the structural assertions.

## Test plan

This is a workflow-only dependency refactor; add no Java tests and no new test framework.

- **Invalid tag regression**: structurally, both `library` and `cli` require `check-tag`, and `check-tag` retains the exact tag comparison. Therefore a failed comparison prevents all seven CLI matrix jobs and the library job from starting. The Step 2 script is the local regression assertion; do not claim a live invalid-tag run.
- **Parallel-build preservation**: `library.needs` and `cli.needs` are both exactly `check-tag`; `cli` has no dependency on `library`.
- **Manual-dispatch regression**: `check-tag` admits `workflow_dispatch`, its comparison step remains tag-only, and `github-release` remains tag-only. Manual runs can build/upload artifacts but cannot enter the GitHub release job.
- **Downstream release preservation**: `github-release.needs` remains `[library, cli]`, so a tag release still waits for both build groups.
- **Syntax/regression gate**: `actionlint .github/workflows/release.yml` and `git diff --check` both exit 0.

## Done criteria

- [ ] A lightweight `check-tag` job checks out the repository and contains the sole `Verify tag matches project version` step with its original shell comparison and explanatory comment.
- [ ] `check-tag` runs for tags and `workflow_dispatch`; the version comparison itself runs only for tags.
- [ ] Both `library` and `cli` declare exactly `needs: check-tag`; `cli` does not need `library`.
- [ ] The seven CLI matrix entries and `fail-fast: false` are unchanged.
- [ ] `github-release` still has `if: github.ref_type == 'tag'` and `needs: [library, cli]`; manual dispatch cannot create a GitHub release.
- [ ] Publishing, signing, artifacts, build/smoke commands, engine selection, and Maven Central content are unchanged.
- [ ] `actionlint .github/workflows/release.yml`, the Step 2 structural script, and `git diff --check` all exit 0.
- [ ] No fake tag was pushed and no live release/publication was attempted.
- [ ] No files outside `.github/workflows/release.yml` were modified except the permitted `plans/README.md` status row.
- [ ] `plans/README.md` status row updated, unless the reviewer maintains the index.

## STOP conditions

Stop and report back (do not improvise) if:

- The current workflow differs from the excerpts, has additional build/release jobs, or no longer has seven CLI matrix entries; the dependency graph then needs fresh review.
- The version check now requires Java, Gradle, credentials, or generated files rather than only checkout and `gradle.properties`.
- Preserving manual artifact builds or preventing manual GitHub releases appears to require changing triggers, publishing steps, release permissions, or the `github-release` guard.
- The only apparent way to order the jobs would make `cli` depend on `library`; that would violate the parallel-build requirement.
- `actionlint` or the structural check fails twice after a reasonable YAML/dependency correction.
- A fix appears to require any file other than `.github/workflows/release.yml` (apart from the permitted plan-index status update).

## Maintenance notes

- Any future release build job that consumes a validated tag should also depend on `check-tag`; do not reintroduce validation into an individual matrix/build job.
- Keep `cli` and `library` siblings after `check-tag`. Adding a `cli → library` edge would serialize builds unnecessarily.
- Preserve the distinction between job admission and step execution: manual dispatch enters `check-tag` but skips its tag-only comparison. Tightening the comparison to manual SNAPSHOT builds would break intended CI.
- Preserve `github-release.if` when editing dependencies. Depending on successful builds alone is not enough to prevent a manual GitHub release.
- Reviewers should compare the moved shell block verbatim and scrutinize the job graph, not just actionlint success. Live tag/release behavior is intentionally not exercised by this plan.
