# Branch Lens Changelog

## [Unreleased]

### Changed

- Hover tooltip now lists each diverging branch with the blame author and
  date for the corresponding branch-side line.
- Click on a gutter badge opens IntelliJ's built-in diff viewer (full-file,
  current vs branch). When more than one branch differs at the clicked line,
  a chooser popup appears first listing each branch with author + date.
- `git blame --line-porcelain` is now run against each diverging branch
  during analysis so the per-line attribution is available without a second
  round trip on click.

### Added

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
