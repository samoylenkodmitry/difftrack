# Branch Lens Changelog

## [Unreleased]

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
