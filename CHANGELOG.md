# Branch Lens Changelog

## [Unreleased]

### Added

- **Tool window** (`View → Tool Windows → Branch Lens`) — for the currently
  active editor, lists every diverging line grouped by branch with author,
  date, and commit subject. Single-click jumps the editor to the line;
  double-click also opens the diff viewer. Includes a Refresh button.
- **Plugin icon** (light + dark SVG) under `META-INF/`.
- **Editor / VCS action `Compare File with Local Branch…`** (right-click in
  editor, Git main menu, or Ctrl+Shift+A) — lets you pick any local branch
  from a chooser and open IDEA's diff viewer between the current file and
  its blob on that branch, without needing a gutter badge.
- **Result cache** — per-project LRU stores: full analysis result keyed by
  (file, document hash, branch tips fingerprint, settings hash), and
  per-branch blame keyed by (branch commit, file path). Re-opening files
  or switching editors no longer re-runs git when nothing changed.

### Changed

- Hover tooltip now lists each diverging branch with the blame author and
  date for the corresponding branch-side line.
- Click on a gutter badge opens IntelliJ's built-in diff viewer (full-file,
  current vs branch). When more than one branch differs at the clicked line,
  a chooser popup appears first listing each branch with author + date.
- `git blame --line-porcelain` is now run against each diverging branch
  during analysis so the per-line attribution is available without a second
  round trip on click.

### Removed

- Dead scaffold modules (`Debouncer`, `EditorTracker`, `VisibleRangeTracker`,
  `GitCli.stdoutTextOrThrow`, `BlobResult.Error`, and unused `SkippedReason`
  enum members) that were placeholders the final wiring inlined.

### Initial MVP

- Initial MVP per the spec in `roadmap.md`:

- Initial MVP per the spec in `roadmap.md`:
  - Gutter badges for editor lines that differ in other local Git branches.
  - Conservative hunk-to-line classifier (`ReplacedLine`, `ChangedBlock`,
    `DeletedInBranch`, `BranchInsertionAfterCurrentLine`, `FileMissingInBranch`).
  - Popup with branch name, current vs. branch text, and copy actions.
  - Application-level settings: max LOC, max branches, stale-branch filter,
    ignore-whitespace toggle, move/copy-aware blame, excluded branch globs.
  - Project service with debounced re-analysis on document changes and
    visible-range rendering on scroll.
  - JUnit unit tests (diff parser, hunk mapper) and integration tests against
    real temporary Git repositories.
  - GitHub Actions CI: build, test, Qodana, plugin verifier, draft release.
  - Release workflow that publishes the signed plugin to JetBrains Marketplace
    when a GitHub release is published.
