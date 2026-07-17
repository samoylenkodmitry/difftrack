# Branch Lens

[![Build](https://github.com/samoylenkodmitry/difftrack/actions/workflows/build.yml/badge.svg)](https://github.com/samoylenkodmitry/difftrack/actions/workflows/build.yml)

<!-- Plugin description -->
**Branch Lens** shows how the file you are editing differs across your active Git
branches—without checking out those branches or leaving the editor.

<p align="center">
  <img src="https://raw.githubusercontent.com/samoylenkodmitry/difftrack/main/docs/screenshot.png" alt="Branch Lens gutter badges on pricing.kt" width="700"/>
</p>

Small gutter badges identify changed lines and blocks. Hover for the affected
branches, author, date, and commit subject; click to open IntelliJ's diff viewer at
that line. Right-click a badge to inspect or copy its commit, open it in Git Log,
copy the summary, or configure branch scope. The Branch Lens tool window groups
identical results by originating change by default; switch off **By Change** for the
traditional branch-first view.

Choose the branches that matter to your project:

- **Recent** — analyze recently updated branches.
- **Pinned** — focus on branches such as `main`, `release/*`, or a teammate's feature.
- **All** — analyze every branch within the configured safety limit.
- Include remote-tracking refs already present in the local repository by default.
  Matching local/upstream refs at the same commit are shown once as a paired entry;
  diverged pairs remain separate and show their outgoing/incoming commit counts as
  `diverged ↑ahead ↓behind`.

Branch Lens never fetches and makes no network requests. Git's own `diff` and
`blame` remain the source of truth, and ambiguous edits are shown as changed blocks
instead of invented line mappings. When a file was renamed on another branch,
Branch Lens resolves its branch-side path before comparing it.

Diff windows opened by Branch Lens include author/date annotation columns on both
sides. Current-only lines use current-snapshot blame, so committed additions show
their author and date while working-tree additions are labeled uncommitted.

Branch Lens compares each side's blame commit with the branches' merge base to
distinguish changes such as **added on current**, **removed on branch**, and
**modified independently**. This is history-based evidence rather than a guess from
the diff direction; rewritten or cherry-picked history may still be labeled
**history unclear**.

When many branches produce the same outcome at a line, the gutter popup shows one
summary such as `8 branches — present only in current`. Choose that row only when
you need to drill down to a particular branch's full-file diff.

Contiguous lines affecting the same branch cohort share one badge at the start and
a vertical gutter marker spanning the region. Line pairing and branch-side blame may
vary inside the region without fragmenting it. If every grouped branch would produce
the exact same full-file diff and attribution, Branch Lens opens one common diff
labeled `8 equivalent branches`; otherwise it keeps the branch drill-down.

The change-first tool-window header shows the origin commit, affected line range,
how many selected branches differ, and how many branch tips contain that exact
commit. Right-click it for commit details, Git Log navigation, hash copying, or a
newline-separated list of affected branches. Its toolbar provides immediate
**Recent / Pinned / All / Remotes** scope controls.

Badge meanings:

| Badge | Meaning                                              |
|-------|------------------------------------------------------|
| `1`–`9` | Number of selected branches where this line differs. |
| `9+`  | 10 or more branches differ.                          |
| `±`   | At least one *changed-block* difference (ambiguous). |
| `+`   | Only branch-side insertions anchor to this line.     |
| `?`   | File is missing in (at least one of) the branches.   |

The action **`Compare File with Git Branch…`** is available from the editor context
menu and Find Action. Configure branch scope and performance limits under
**Settings → Tools → Branch Lens**.

[Documentation](https://github.com/samoylenkodmitry/difftrack#readme) ·
[Report an issue](https://github.com/samoylenkodmitry/difftrack/issues/new/choose) ·
[Source code](https://github.com/samoylenkodmitry/difftrack)
<!-- Plugin description end -->

## Demo

<video src="https://raw.githubusercontent.com/samoylenkodmitry/difftrack/main/docs/demo.mp4" controls width="700"></video>

If the inline player doesn't render in your reader (e.g. on JetBrains Marketplace),
open [`docs/demo.mp4`](docs/demo.mp4) directly on GitHub.

## Design principles

- **Do not invent line identity.** Git diff hunks are the source of truth. Ambiguous
  multi-line edits are reported as *changed blocks*, never as fake exact mappings.
- **Offline by design.** Local and locally available remote-tracking refs only; no
  fetch and no network.
- **Cheap to scroll, expensive to edit.** Analysis runs on document change (debounced);
  scrolling just re-renders visible badges from the cached result.
- **Bounded concurrency.** Branch analysis runs in parallel within a project-level
  Git process limit; document changes cancel obsolete work and its subprocesses.
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
internet connection on first build so the wrapper and IntelliJ Platform dependencies
can be downloaded.

```bash
./gradlew check        # unit tests + git-CLI integration tests
./gradlew verifyPlugin # IntelliJ Plugin Verifier across recommended IDEs
./gradlew runIde       # launch a sandbox IDE with the plugin loaded
./gradlew buildPlugin  # produce build/distributions/branch-lens-*.zip
```

Integration tests shell out to a real `git` binary and create temporary
repositories. They are skipped (via JUnit `Assume`) if `git` isn't on PATH.

## Releasing

Releases are fully automated via the GitHub Actions workflows in
`.github/workflows/`:

1. Each push to `main` runs `build`, `test`, `inspectCode` (Qodana),
   `verify` (IntelliJ Plugin Verifier), and — if all four succeed — creates
   a **draft GitHub release** with the changelog body.
2. Publishing that draft (or creating a pre-release) triggers `release.yml`,
   which builds + signs the plugin, uploads the signed `.zip` to the GitHub
   release, calls `./gradlew publishPlugin` against JetBrains Marketplace,
   and opens a PR that patches `CHANGELOG.md` for the published version.

### One-time setup: first Marketplace upload

The very first version of any plugin has to be uploaded to JetBrains
Marketplace **by hand** (so you can set license, category, vendor URL, etc.).
Subsequent versions then auto-publish via `release.yml`.

To do it:

1. Run `./gradlew buildPlugin` and grab `build/distributions/branch-lens-*.zip`
   (or download it from the corresponding GitHub release).
2. Go to <https://plugins.jetbrains.com/plugin/add>, sign in with the same
   JetBrains Hub account that owns `PUBLISH_TOKEN`, upload the zip, fill in
   the listing fields, and submit.
3. Wait for Marketplace approval (usually a few hours).
4. From the next published GitHub release onwards, `release.yml` finds the
   plugin by its id and pushes new versions without any manual step.

The release workflow needs four repository secrets:

| Secret                | Source                                                                                       |
|-----------------------|----------------------------------------------------------------------------------------------|
| `PUBLISH_TOKEN`       | JetBrains Hub → **My Profile → Permanent Tokens → Marketplace token**                        |
| `CERTIFICATE_CHAIN`   | The full chain `.crt`, including any intermediates (used by Gradle to sign the plugin zip).  |
| `PRIVATE_KEY`         | The matching private key (PEM).                                                              |
| `PRIVATE_KEY_PASSWORD`| The passphrase for `PRIVATE_KEY`.                                                            |

See JetBrains' [plugin-signing docs](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
for how to generate the certificate / key pair.

## Compatibility

- IntelliJ Platform 2024.2 or newer.
- JDK 21 toolchain (auto-provisioned by Gradle).
- Kotlin 2.1.

## Roadmap

- PSI-aware semantic line matching.
- Cross-file move/copy attribution beyond direct file renames.
- Optional pull-request context supplied by installed VCS integrations.
- Cross-file move/rename detection between branch tips (`git diff -M`).
- Lazy *find deletion commit* via `git log -S/-G`.
- File-level heatmap and project-wide divergence view.
- Native frontend/backend split for JetBrains Remote Development.

See [`roadmap.md`](roadmap.md) for the full specification this implementation is built
against.

## License

Apache 2.0 — see [`LICENSE`](LICENSE).
