# Branch Lens

[![Build](https://github.com/samoylenkodmitry/difftrack/actions/workflows/build.yml/badge.svg)](https://github.com/samoylenkodmitry/difftrack/actions/workflows/build.yml)

<!-- Plugin description -->
**Branch Lens** is an IntelliJ IDEA plugin that, for the currently open file, shows
gutter badges next to lines or blocks that **differ in other local Git branches**, and
tells you who last modified the corresponding branch-side line.

<p align="center">
  <img src="https://raw.githubusercontent.com/samoylenkodmitry/difftrack/main/docs/screenshot.png" alt="Branch Lens gutter badges on pricing.kt" width="700"/>
</p>

Local Git only — no remotes, no fetch, no network, no fuzzy line-identity guesses.
Git's own `diff` and `blame` are the source of truth.

For every visible editor line the plugin reads the file's blob from each other local
branch, compares it to the in-editor text via `git diff --no-index -U0`, and classifies
each hunk conservatively as:

- **ReplacedLine** — exact 1-for-1 replacement (high or medium confidence)
- **ChangedBlock** — replacement with unequal sizes; line pairing is ambiguous
- **DeletedInBranch** — current line is absent in the branch
- **BranchInsertionAfterCurrentLine** — the branch inserts extra lines here
- **FileMissingInBranch** — the file does not exist in the branch

Badges in the gutter:

| Badge | Meaning                                              |
|-------|------------------------------------------------------|
| `1`–`9` | Number of local branches where this line differs.  |
| `9+`  | 10 or more branches differ.                          |
| `±`   | At least one *changed-block* difference (ambiguous). |
| `+`   | Only branch-side insertions anchor to this line.     |
| `?`   | File is missing in (at least one of) the branches.   |

Hover any badge for an HTML tooltip listing each branch with the blame author, date,
and commit subject for the corresponding branch-side line. Click a badge to open
IntelliJ's built-in diff viewer (with a chooser first when more than one branch
differs at that line). A `Branch Lens` tool window at the bottom of the IDE lists
every diverging line in the active file. The action **`Compare File with Local
Branch…`** (right-click in editor / `Ctrl+Shift+A`) lets you pick any local branch
and open the diff without needing a gutter badge.
<!-- Plugin description end -->

## Demo

<video src="https://raw.githubusercontent.com/samoylenkodmitry/difftrack/main/docs/demo.mp4" controls width="700"></video>

If the inline player doesn't render in your reader (e.g. on JetBrains Marketplace),
open [`docs/demo.mp4`](docs/demo.mp4) directly on GitHub.

## Design principles

- **Do not invent line identity.** Git diff hunks are the source of truth. Ambiguous
  multi-line edits are reported as *changed blocks*, never as fake exact mappings.
- **Local-only.** No remotes, no fetch, no network.
- **Cheap to scroll, expensive to edit.** Analysis runs on document change (debounced);
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

## Roadmap (post-MVP, not in v1)

- PSI-aware semantic line matching.
- Remote-branch and pull-request integration.
- Cross-file move/rename detection between branch tips (`git diff -M`).
- Lazy *find deletion commit* via `git log -S/-G`.
- Tool window with all line differences.
- Branch pinning, file-level heatmap.

See [`roadmap.md`](roadmap.md) for the full specification this implementation is built
against.

## License

Apache 2.0 — see [`LICENSE`](LICENSE).
