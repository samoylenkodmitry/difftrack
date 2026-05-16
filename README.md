# Branch Lens

An IntelliJ IDEA plugin that, for the currently opened file, shows gutter badges next
to lines or blocks that **differ in other local Git branches**, and tells you who last
modified the corresponding branch-side line.

> Local Git only. No remotes, no fetch, no network, no fuzzy line-identity guesses.

## What it does

For every visible line in the active editor:

- Reads the file's blob in each *other* local branch.
- Asks `git diff --no-index -U0` to compare the editor snapshot to the branch blob.
- Classifies each hunk conservatively into:
  - `ReplacedLine` — exact 1-for-1 replacement (high or medium confidence).
  - `ChangedBlock` — replacement with unequal sizes; line pairing is ambiguous.
  - `DeletedInBranch` — current line is absent in the branch.
  - `BranchInsertionAfterCurrentLine` — branch has additional lines after this line.
  - `FileMissingInBranch` — file does not exist in the branch.
- Renders a small badge in the gutter:

  | Badge | Meaning                                                |
  |-------|--------------------------------------------------------|
  | `1`–`9` | Number of local branches where this line differs.    |
  | `9+`  | 10 or more branches differ.                            |
  | `±`   | At least one *changed-block* difference (ambiguous).   |
  | `+`   | Only branch-side insertions anchor to this line.       |
  | `?`   | File is missing in (at least one of) the branches.     |

Clicking a badge opens a popup with each branch's name, line number, current vs.
branch text, and (when available) `git blame` author/date/commit for the
corresponding branch-side line. For deleted lines we *do not* invent a blame author.

## Design principles

- **Do not invent line identity.** Git diff hunks are the source of truth. Ambiguous
  multi-line edits are reported as *changed blocks*, never as fake exact mappings.
- **Local-only.** No remotes, no fetch, no network.
- **Cheap to scroll, expensive to edit.** Analysis runs on document change (debounced),
  scrolling just re-renders visible badges from the cached result.
- **Cooperative cancellation.** Every analysis is a coroutine job tied to the project
  scope; document changes / file closes cancel in-flight work and destroy the
  subprocess.
- **Skip what's not safe.** Files over 10,000 lines, binary files, and files outside
  Git are silently skipped.

## Default limits

| Setting                       | Default  |
|-------------------------------|----------|
| `maxLines`                    | 10,000   |
| `maxFileBytes`                | 2 MB     |
| `maxBranches`                 | 30       |
| `staleBranchDays`             | 180      |
| `analysisDebounceMs`          | 300      |
| `gitCommandTimeoutMs`         | 5,000    |
| `visibleRenderMarginLines`    | 80       |
| `maxConcurrentGitProcesses`   | 3        |

All adjustable via **Settings → Tools → Branch Lens**.

## Building

Requires JDK 21 (Gradle's toolchain feature will provision it if needed) and an
internet connection on first build (to download Gradle 9.4.1 and the IntelliJ
Platform sources).

```bash
./gradlew check
./gradlew runIde       # launch a sandbox IDE with the plugin loaded
./gradlew buildPlugin  # produce build/distributions/branch-lens-*.zip
```

The integration tests shell out to a real `git` binary and create temporary
repositories. They are skipped (via JUnit `Assume`) if `git` isn't on PATH.

## Compatibility

- IntelliJ Platform 2024.2 or newer.
- JDK 21 toolchain (auto-provisioned by Gradle).
- Kotlin 2.1.

## Roadmap (post-MVP, not in v1)

- PSI-aware semantic line matching.
- Remote-branch and pull-request integration.
- Cross-file move/rename detection between branch tips (`git diff -M`).
- Lazy *find deletion commit* via `git log -S/-G`.
- Tool window with all line differences.
- Branch pinning, file-level heatmap.

See [`roadmap.md`](roadmap.md) for the full specification this implementation is
built against.

## License

TBD.
