# Branch Lens Changelog

## [Unreleased]

### Added

- Merge-base-aware lineage labels distinguish additions, removals, one-sided
  modifications, independently modified lines, pre-divergence differences, and
  uncommitted current changes.
- The tool window defaults to a change-first view: one header per contiguous change
  shows its origin commit, affected line range, differing-branch count, and exact
  commit propagation across the selected branch tips. **By Change** toggles back to
  the branch-first view.
- Tool-window scope controls switch directly between **Recent**, **Pinned**, and
  **All**, with a separate **Remotes** toggle.
- Commit context actions show commit details, open the revision in IntelliJ Git Log,
  copy its hash, and copy all affected branch names from a change header. Commit
  actions are also available from gutter and Branch Lens diff annotations.
- Current-snapshot blame identifies the author and date of committed current-side
  changes and clearly marks unsaved or uncommitted lines.
- Branch Lens diff windows show built-in author/date annotation columns for both
  sides, including revision data that IntelliJ's disabled generic Annotate action
  cannot provide for synthetic branch content.
- Gutter popups collapse branches with the same line-level outcome into one summary
  row. Selecting it reveals the individual branches for a specific full-file diff.
- Consecutive lines affecting the same branch cohort render as one gutter region,
  even when line pairing or branch-side blame changes inside the Git hunk.
- Multiline regions use one count badge plus a vertical gutter marker spanning the
  affected lines instead of repeating the badge on every line.
- When grouped branches have identical file snapshots, paths, and relevant blame,
  Branch Lens opens one shared diff labeled with the equivalent-branch count.
- Project-specific branch scopes: recent branches, pinned branches only, or all
  branches within the configured limit.
- Optional analysis of locally available remote-tracking refs. Branch Lens still
  performs no fetches and makes no network requests.
- Automatic refresh when Git4Idea reports a branch or repository-state change.
- Rename-aware lookup when the current file has a different path at a branch tip.
- A grouped tool-window view with one section per affected branch and accurate
  unique-line and affected-branch counts.
- Diff viewers opened from a gutter badge or tool-window row now start at the
  selected line.
- Accessible names for gutter badges.
- Gutter context actions for opening the branch diff, copying a plain-text
  summary, and configuring branch scope.

### Changed

- Locally available remote-tracking branches are included by default.
- A local branch and its configured upstream are collapsed into one `local ↔ remote`
  entry when both refs point to the same commit. Diverged pairs remain separate.
- The analysis header and branch entries show upstream status with commit counts:
  `ahead ↑N`, `behind ↓N`, or `diverged ↑N ↓N`.
- Current-side-only lines are described as “present only in current” or “absent in
  branch” when history cannot resolve their lineage; otherwise the merge-base-aware
  label identifies whether the current branch added the line or the other branch
  removed it.
- Branch analysis now runs concurrently within the configured Git process limit.
- Blame is limited to the branch-line range involved in the diff instead of the
  entire file.
- Analysis and blame caches are bounded by estimated memory weight as well as
  entry count.
- Settings changes invalidate cached results and immediately reanalyze open files.
- The Marketplace description now leads with the user workflow instead of
  internal model names.
- IntelliJ Platform Gradle Plugin updated to 2.18.1, with verification extended
  through the 2026.1 platform line.

### Fixed

- Repository-relative paths are canonicalized, fixing false “file missing” results
  for macOS path aliases such as `/var` and `/private/var`.
- Unequal changed blocks no longer produce duplicate rows for every line in the
  block.
- Insertions before line 1 are now visible in both the gutter and tool window.
- Files over the configured byte limit are skipped on both sides of the comparison.
- `maxFileBytes` and branch-scope settings now participate in cache invalidation.
- Missing files are no longer listed twice in the tool window.

## [0.1.1]

### Fixed

- First Marketplace-publish dry run: 0.1.0 was hand-uploaded for the
  one-time bootstrap, so 0.1.1 is the first version produced entirely by
  the automated release pipeline. Functionally identical to 0.1.0.
- `release.yml` reordered so the signed `.zip` is attached to the GitHub
  release *before* the Marketplace push, so the artifact is always
  available even when an upload step fails.

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
