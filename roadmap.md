Yes — simplify it. For v1, do **whole-file diff per local branch**, skip files over **10k LOC**, parse Git’s unified hunks, and render conservative badges only where Git’s diff says the current file line participates in a changed/deleted block.

The key rule for the coding agent should be:

> Do not invent line identity. Use Git diff hunks as the source of truth. When a hunk is ambiguous, show “changed block”, not a fake exact line mapping.

Below is a roadmap/spec you can paste into a fresh coding-agent session.

---

# Coding-agent roadmap: IntelliJ IDEA plugin “Branch Lens”

## 0. Product goal

Build an IntelliJ IDEA plugin that answers, for the currently opened file:

> “Which local branches have this line/block different, and who last modified the corresponding branch version?”

Scope:

```text
Local Git only.
No remotes.
No network.
No fuzzy matching in v1.
No analysis for files over 10,000 LOC.
Analyze the whole current file, but render badges only for visible editor lines.
Use Git diff/blame instead of implementing our own diff algorithm.
```

The plugin should show small gutter badges next to changed lines. Clicking a badge opens a popup showing branches where that line/block differs, the branch-side text, and blame metadata when available.

Use the current IntelliJ Platform Gradle Plugin 2.x, not the old 1.x Gradle IntelliJ Plugin. JetBrains documents 2.x as the current plugin for building/testing/verifying/publishing IntelliJ plugins, with plugin ID `org.jetbrains.intellij.platform`; their docs also list Gradle 9.0.0 and Java Runtime 17 as minimum requirements for the Gradle plugin itself. ([JetBrains Marketplace][1]) For plugins targeting newer IDEs, use Java 21 and Kotlin 2.x: JetBrains’ project creation docs say 2024.2+ targets use Java 21, and Kotlin 2.x is required for plugins targeting 2025.1 or later. ([JetBrains Marketplace][2])

---

# 1. Recommended repository scaffold

Create this structure:

```text
branch-lens/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── README.md
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── dev/branchlens/
│   │   │       ├── BranchLensProjectService.kt
│   │   │       ├── editor/
│   │   │       │   ├── EditorTracker.kt
│   │   │       │   ├── VisibleRangeTracker.kt
│   │   │       │   ├── GutterBadgeRenderer.kt
│   │   │       │   └── BranchLensBadgeIcon.kt
│   │   │       ├── git/
│   │   │       │   ├── GitCli.kt
│   │   │       │   ├── GitRepositoryLocator.kt
│   │   │       │   ├── GitBranchProvider.kt
│   │   │       │   ├── GitBlobReader.kt
│   │   │       │   ├── GitDiffRunner.kt
│   │   │       │   └── GitBlameRunner.kt
│   │   │       ├── diff/
│   │   │       │   ├── UnifiedDiffParser.kt
│   │   │       │   ├── HunkMapper.kt
│   │   │       │   └── LineDifferenceClassifier.kt
│   │   │       ├── model/
│   │   │       │   ├── AnalysisModels.kt
│   │   │       │   └── SettingsModels.kt
│   │   │       ├── popup/
│   │   │       │   └── BranchLensPopup.kt
│   │   │       ├── settings/
│   │   │       │   ├── BranchLensSettings.kt
│   │   │       │   └── BranchLensConfigurable.kt
│   │   │       └── util/
│   │   │           ├── Debouncer.kt
│   │   │           ├── TempFileUtil.kt
│   │   │           └── TextUtil.kt
│   │   └── resources/
│   │       └── META-INF/
│   │           └── plugin.xml
│   └── test/
│       └── kotlin/
│           └── dev/branchlens/
│               ├── UnifiedDiffParserTest.kt
│               ├── HunkMapperTest.kt
│               ├── GitCliIntegrationTest.kt
│               └── BranchLensAnalyzerTest.kt
└── .github/
    └── workflows/
        └── build.yml
```

The IntelliJ Platform Plugin Template is also acceptable because JetBrains maintains it as a Gradle-based scaffold with CI support, and it uses the IntelliJ Platform Gradle Plugin 2.x. ([JetBrains Marketplace][3])

---

# 2. MVP behavior

## Enabled cases

The plugin should run only when all are true:

```text
- Current editor has a VirtualFile.
- File is inside a Git repository.
- File is text, not binary.
- Current document has <= 10,000 lines.
- File path is tracked or exists in at least one local branch.
- Git executable is available.
```

## Disabled cases

Show no gutter badges and optionally log/debug-reason when:

```text
- File has > 10,000 LOC.
- File appears binary.
- File is outside Git.
- Git command times out.
- Branch count exceeds configured maximum and user has not opted into all branches.
- Current file is not supported by simple text diff.
```

Default limits:

```text
maxLines = 10000
maxFileBytes = 2 MB
maxBranches = 30
gitCommandTimeoutMs = 5000
analysisDebounceMs = 300
visibleRenderMarginLines = 80
maxConcurrentGitProcesses = 3
```

---

# 3. Core simplified algorithm

For every analyzed editor document:

```text
1. Snapshot the current editor text.
2. Count lines. If > 10k, stop.
3. Resolve Git repo root and file path relative to repo root.
4. List local branches from refs/heads.
5. Exclude the current branch by default.
6. For each selected local branch:
   6.1 Read that branch’s file blob.
   6.2 If file is missing, record FileMissing for that branch.
   6.3 Write current editor snapshot and branch blob to temp files.
   6.4 Run git diff --no-index -U0 between temp files.
   6.5 Parse unified hunks.
   6.6 Convert hunks into per-current-line differences.
   6.7 For branch-side changed lines, load blame metadata lazily or batched.
7. Aggregate differences per current editor line.
8. Render gutter badges for currently visible lines only.
```

Use `git diff` instead of implementing a diff. Git’s own docs define `git diff` as showing differences between working tree/index/tree/blob endpoints, and `--no-index` can compare two filesystem paths; `-U<n>` / `--unified=<n>` controls the number of context lines, so use `-U0` to make hunk-to-line projection simpler. ([Git SCM][4])

Run diff like this:

```bash
git -C <repoRoot> diff \
  --no-index \
  --unified=0 \
  --no-color \
  --no-ext-diff \
  --text \
  -- \
  <currentSnapshotTmpFile> \
  <branchBlobTmpFile>
```

Important: `git diff --no-index` returns exit code `1` when files differ. Treat exit codes as:

```text
0 = no difference
1 = difference found
>1 = command error
```

Pass arguments with `ProcessBuilder` / IntelliJ `GeneralCommandLine`. Never invoke through a shell string.

---

# 4. Git backend

## 4.1 Repository detection

Implement:

```kotlin
data class GitRepo(
    val root: Path,
    val currentBranch: String?,
    val headCommit: String?
)
```

Use commands:

```bash
git -C <fileParent> rev-parse --show-toplevel
git -C <repoRoot> symbolic-ref --quiet --short HEAD
git -C <repoRoot> rev-parse HEAD
```

Detached HEAD is allowed, but current branch may be null.

## 4.2 Local branch listing

Use local refs only:

```bash
git -C <repoRoot> for-each-ref \
  --sort=-committerdate \
  --format=%(refname:short)%00%(objectname)%00%(committerdate:unix)%00%(authorname)%00 \
  refs/heads
```

Git’s `for-each-ref` command iterates matching refs and supports formatting fields such as `refname`, `objectname`, `HEAD`, and date/sort fields, so it is appropriate for local branch enumeration. ([Git SCM][5])

Data model:

```kotlin
data class LocalBranch(
    val name: String,
    val headCommit: String,
    val committerDateEpochSeconds: Long?,
    val authorName: String?,
    val isCurrent: Boolean
)
```

Rules:

```text
- Only refs/heads.
- Never fetch.
- Never inspect refs/remotes.
- Sort by recent commit date.
- Exclude current branch by default.
- Respect maxBranches.
```

## 4.3 Reading branch file content

Use the branch commit SHA, not raw branch name, after branch enumeration:

```bash
git -C <repoRoot> show <branchCommit>:<relative/path>
```

Git’s `show` documentation says it can show blobs, trees, tags, and commits; for plain blobs, it shows the blob contents. ([Git SCM][6])

Alternative:

```bash
git -C <repoRoot> cat-file -p <branchCommit>:<relative/path>
```

Git’s `cat-file -p` pretty-prints the object contents and supports object names of the form `<tree-ish>:<path>`. ([Git SCM][7])

Implementation requirements:

```text
- Decode as UTF-8 first.
- If decoding fails, skip branch/file as binary or unsupported.
- Normalize CRLF/CR to LF before diffing.
- Detect NUL bytes and skip as binary.
- Cache by (repoRoot, branchCommit, relativePath).
```

---

# 5. Diff parser

Implement a parser for unified diff output.

Hunk header regex:

```regex
^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@
```

Data model:

```kotlin
data class UnifiedDiff(
    val fileHeader: DiffFileHeader?,
    val hunks: List<DiffHunk>
)

data class DiffHunk(
    val oldStart: Int,
    val oldLength: Int,
    val newStart: Int,
    val newLength: Int,
    val lines: List<DiffLine>
)

sealed class DiffLine {
    data class Context(val oldLine: Int, val newLine: Int, val text: String) : DiffLine()
    data class Removed(val oldLine: Int, val text: String) : DiffLine()
    data class Added(val newLine: Int, val text: String) : DiffLine()
}
```

Interpretation:

```text
old/current side = editor snapshot
new/branch side = branch blob
```

Because the diff command compares:

```text
currentSnapshotTmpFile -> branchBlobTmpFile
```

a `-` line means:

```text
This current editor line is absent/different in the branch.
```

a `+` line means:

```text
This branch line is absent/different in the current editor.
```

---

# 6. Hunk-to-line classification

Do not directly assume every removed line maps to a specific added line. Classify conservatively.

Data model:

```kotlin
sealed class BranchLineDifference {
    data class ReplacedLine(
        val branch: LocalBranch,
        val currentLine: Int,
        val branchLine: Int,
        val currentText: String,
        val branchText: String,
        val confidence: Confidence
    ) : BranchLineDifference()

    data class ChangedBlock(
        val branch: LocalBranch,
        val currentLines: IntRange,
        val branchLines: IntRange?,
        val currentText: List<String>,
        val branchText: List<String>,
        val confidence: Confidence
    ) : BranchLineDifference()

    data class DeletedInBranch(
        val branch: LocalBranch,
        val currentLine: Int,
        val currentText: String
    ) : BranchLineDifference()

    data class BranchInsertionAfterCurrentLine(
        val branch: LocalBranch,
        val anchorCurrentLine: Int,
        val branchLines: IntRange,
        val branchText: List<String>
    ) : BranchLineDifference()

    data class FileMissingInBranch(
        val branch: LocalBranch,
        val relativePath: String
    ) : BranchLineDifference()
}

enum class Confidence {
    HIGH,
    MEDIUM,
    BLOCK_ONLY
}
```

Classification rules per edit group:

```text
Case A:
removed.size == added.size == 1
=> ReplacedLine, HIGH

Case B:
removed.size == added.size && removed.size > 1
=> ReplacedLine per pair, MEDIUM
Reason: probably line-by-line replacement, but still hunk-local.

Case C:
removed.size > 0 && added.size > 0 && sizes differ
=> ChangedBlock for every removed current line, BLOCK_ONLY
Reason: do not fake exact line pairing.

Case D:
removed.size > 0 && added.size == 0
=> DeletedInBranch

Case E:
removed.size == 0 && added.size > 0
=> BranchInsertionAfterCurrentLine
Anchor to the line before the insertion. If insertion is before line 1, anchor to line 1 with special label “before first line”.
```

Badge rendering should reflect confidence:

```text
Exact/high confidence: number badge, e.g. 3
Medium: number badge with tooltip note
Block-only: ± or ~ badge
Insertion-only: + badge
File missing: ? in popup, not necessarily per-line badge
```

Important UX rule:

```text
For BLOCK_ONLY, tooltip must say “changed block”, not “this exact line is changed to X”.
```

This avoids false precision.

---

# 7. Blame metadata

Use blame only after diff identifies branch-side lines. Do not blame every line of every branch upfront.

Git’s blame documentation says blame annotates each line with the revision and author that last modified it; `-L` restricts blame to ranges; `--line-porcelain` emits commit information for every line and implies porcelain mode. ([Git SCM][8])

Command:

```bash
git -C <repoRoot> blame \
  --line-porcelain \
  -w \
  -M \
  -L <start>,<end> \
  <branchCommit> \
  -- \
  <relative/path>
```

Use `-M` by default because Git documents it as detecting moved/copied lines within a file. Add `-C` only as an optional setting because it is more expensive and searches across files/copies. ([Git SCM][8])

Blame model:

```kotlin
data class BlameInfo(
    val commitHash: String,
    val author: String?,
    val authorMail: String?,
    val authorTimeEpochSeconds: Long?,
    val authorTimezone: String?,
    val summary: String?
)
```

Blame cache key:

```text
(repoRoot, branchCommit, relativePath, lineRange)
```

Optimization:

```text
- Merge adjacent branch line ranges before running blame.
- If many ranges exist, blame the whole file once.
- Cache full-file blame by (repoRoot, branchCommit, relativePath).
```

Deleted lines caveat:

Git’s blame docs explicitly say blame does not tell you about lines that were deleted or replaced; those require diff or history search. ([Git SCM][8]) Therefore:

```text
For DeletedInBranch:
- Do not invent author/time.
- Show “line is absent in this branch; no branch-side line exists to blame.”
- Optional future feature: lazy “find deletion commit” using git log -S/-G.
```

---

# 8. Editor integration

Use a project service with a coroutine scope:

```kotlin
@Service(Service.Level.PROJECT)
class BranchLensProjectService(
    private val project: Project,
    private val scope: CoroutineScope
)
```

JetBrains recommends launching plugin coroutines from services that receive their coroutine scope via constructor injection; the scope is canceled when the service/plugin lifecycle ends. ([JetBrains Marketplace][9])

Use these event sources:

```text
- Editor opened/closed.
- Selected editor changed.
- Document changed.
- Visible area changed / editor scroll.
- Settings changed.
```

Implementation plan:

```text
1. On project service init:
   - Discover existing editors.
   - Attach editor trackers for project editors.
   - Register listeners with proper Disposable.

2. On document change:
   - Clear rendered badges for that editor.
   - Debounce analysis.
   - Re-run whole-file analysis.

3. On scroll:
   - Do not re-run Git diff if analysis cache is fresh.
   - Re-render badges for visible range + margin.

4. On file close:
   - Remove highlighters.
   - Cancel pending jobs for that editor.
```

Do not perform heavy work inside listeners. JetBrains’ threading docs explicitly warn that listeners should not perform heavy operations and should instead schedule background processing. ([JetBrains Marketplace][10])

---

# 9. Threading and cancellation

Architecture:

```text
EDT:
- Read current visible range.
- Apply/remove gutter highlighters.

Short read action:
- Snapshot document text.
- Read VirtualFile path.
- Read document modification stamp.

Background / IO:
- Run Git commands.
- Parse diff.
- Parse blame.
- Build analysis result.
```

Do **not** run Git under a read action.

Do **not** run long `ReadAction.compute` / `runReadAction` calls in the background. JetBrains’ 2026 platform blog explains that long non-cancellable read actions in background threads can block write actions and freeze the UI, and recommends cancellable `readAction` / `smartReadAction`, `ReadAction.nonBlocking`, and periodic cancellation checks. ([The JetBrains Blog][11])

Required behavior:

```text
- Every analysis job is cancellable.
- New document change cancels older analysis.
- New file selection cancels old editor analysis.
- Project disposal cancels all jobs.
- Git subprocess is destroyed on cancellation.
- Use a semaphore to limit concurrent Git commands.
```

Pseudo-flow:

```kotlin
fun scheduleAnalysis(editor: Editor, file: VirtualFile) {
    jobs[editor]?.cancel()

    jobs[editor] = scope.launch {
        delay(settings.analysisDebounceMs)

        val snapshot = readAction {
            EditorSnapshot.from(project, editor, file)
        }

        if (!snapshot.isEligible(settings)) {
            withContext(Dispatchers.EDT) {
                renderer.clear(editor)
            }
            return@launch
        }

        val result = withContext(Dispatchers.IO) {
            analyzer.analyze(snapshot)
        }

        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) {
                renderer.applyVisible(editor, result)
            }
        }
    }
}
```

---

# 10. Gutter rendering

Use editor highlighters with gutter icon renderers.

JetBrains’ `RangeHighlighter` API supports setting line marker renderers and gutter icon renderers; the gutter icon renderer is used for icons drawn to the left of the folding area. ([GitHub][12]) `GutterIconRenderer` provides alignment options and tooltip/action hooks; avoid internal `LINE_NUMBERS` alignment and use public `LEFT`, `CENTER`, or `RIGHT`. ([GitHub][13])

Implementation:

```kotlin
class GutterBadgeRenderer(
    private val project: Project
) {
    fun applyVisible(editor: Editor, result: FileAnalysisResult)
    fun clear(editor: Editor)
}
```

For each visible line with differences:

```kotlin
val highlighter = editor.markupModel.addLineHighlighter(
    zeroBasedLine,
    HighlighterLayer.ADDITIONAL_SYNTAX,
    null
)

highlighter.gutterIconRenderer = BranchLensGutterIconRenderer(summary)
```

Renderer:

```kotlin
class BranchLensGutterIconRenderer(
    private val summary: LineSummary
) : GutterIconRenderer() {

    override fun getIcon(): Icon =
        BranchLensBadgeIcon(summary.badgeText)

    override fun getTooltipText(): String =
        summary.toHtmlTooltip()

    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction =
        ShowBranchLensPopupAction(summary)

    override fun getAlignment(): Alignment = Alignment.RIGHT

    override fun equals(other: Any?): Boolean =
        other is BranchLensGutterIconRenderer && other.summary.identity == summary.identity

    override fun hashCode(): Int = summary.identity.hashCode()
}
```

Badge text rules:

```text
1..9 differing branches => "1".."9"
>=10 differing branches => "9+"
only insertions => "+"
only block ambiguity => "±"
file missing only => "?"
mixed => count, with tooltip explaining details
```

---

# 11. Popup content

Clicking a badge opens a compact popup.

Popup title:

```text
Branch Lens: line 42
```

Content grouped by difference kind:

```text
Different in 3 local branches

feature/payment-refactor
  Confidence: high
  Branch line: 118
  Modified by: Alice Smith
  Date: 2026-05-12 14:22
  Commit: abc1234 Fix pricing calculation
  Current: val price = calculatePrice(user)
  Branch:  val price = pricingEngine.calculate(user)

experiment/new-discounts
  Confidence: changed block
  Branch lines: 120-124
  Modified by: Dmitry Samoylenko
  Date: 2026-04-29 09:10
  Commit: def5678 Add discount engine
  This current line belongs to a changed block; exact branch-line pairing is ambiguous.
```

For deleted:

```text
legacy/api-v1
  This line is absent in the branch.
  No branch-side line exists to blame.
```

Actions:

```text
- Copy summary
- Copy branch name
- Copy commit hash
- Compare file with branch
- Ignore branch
- Open settings
```

MVP can implement only:

```text
- Copy summary
- Copy commit hash
```

---

# 12. Settings

Create persistent project-level settings.

```kotlin
data class BranchLensSettingsState(
    var enabled: Boolean = true,
    var maxLines: Int = 10_000,
    var maxFileBytes: Long = 2L * 1024L * 1024L,
    var maxBranches: Int = 30,
    var staleBranchDays: Int = 180,
    var includeStaleBranches: Boolean = false,
    var ignoreWhitespaceInDiff: Boolean = false,
    var useMoveAwareBlame: Boolean = true,
    var useCopyAwareBlame: Boolean = false,
    var analysisDebounceMs: Int = 300,
    var gitCommandTimeoutMs: Int = 5000,
    var visibleRenderMarginLines: Int = 80,
    var excludedBranchPatterns: MutableList<String> = mutableListOf(
        "backup/*",
        "tmp/*",
        "wip/*"
    )
)
```

Default stance:

```text
- Do not ignore whitespace by default.
- Hide stale branches by default.
- Analyze recent local branches first.
- Let user opt into all local branches.
```

Reason: whitespace can be semantically meaningful in some languages/config files, so ignoring it by default can create false negatives.

---

# 13. Cache design

Required caches:

```kotlin
data class BranchListCacheKey(
    val repoRoot: Path
)

data class BranchBlobCacheKey(
    val repoRoot: Path,
    val branchCommit: String,
    val relativePath: String
)

data class DiffCacheKey(
    val repoRoot: Path,
    val branchCommit: String,
    val relativePath: String,
    val documentStamp: Long,
    val documentHash: String
)

data class BlameCacheKey(
    val repoRoot: Path,
    val branchCommit: String,
    val relativePath: String
)
```

Rules:

```text
- Branch blob cache is safe while branchCommit is unchanged.
- Diff cache is safe while documentHash/documentStamp is unchanged.
- Blame cache is safe while branchCommit is unchanged.
- Clear editor result cache on document change.
- Clear branch list cache periodically or when analysis starts and old cache is older than a few seconds.
```

Memory caps:

```text
- Max branch blobs per project: 200
- Max diff results per project: 200
- Max blame files per project: 50
```

---

# 14. Tests

## 14.1 Unit tests for unified diff parser

Cover:

```text
- single-line replacement
- multi-line replacement with equal removed/added count
- multi-line replacement with unequal removed/added count
- deletion only
- insertion only
- insertion before first line
- hunk without explicit length, e.g. -4 +4
- no newline marker: "\ No newline at end of file"
- multiple hunks
- file headers
```

## 14.2 Hunk mapper tests

Create synthetic diff strings and assert line classification.

Examples:

```diff
@@ -2 +2 @@
-val x = old()
+val x = new()
```

Expected:

```text
line 2 => ReplacedLine(branchLine=2, confidence=HIGH)
```

Example:

```diff
@@ -10,2 +10,3 @@
-a()
-b()
+x()
+y()
+z()
```

Expected:

```text
line 10 => ChangedBlock(confidence=BLOCK_ONLY)
line 11 => ChangedBlock(confidence=BLOCK_ONLY)
```

Example:

```diff
@@ -5,2 +4,0 @@
-foo()
-bar()
```

Expected:

```text
line 5 => DeletedInBranch
line 6 => DeletedInBranch
```

Example:

```diff
@@ -8,0 +9,2 @@
+extra1()
+extra2()
```

Expected:

```text
anchor line 8 => BranchInsertionAfterCurrentLine
```

## 14.3 Git integration tests

Use JUnit temp directories and real Git CLI.

Test repo setup:

```bash
git init
git config user.email test@example.com
git config user.name Tester
```

Scenarios:

```text
1. Two local branches, one file, one changed line.
2. Branch missing file.
3. Branch with inserted lines above target line.
4. Branch with deleted current line.
5. Branch with multi-line replacement.
6. Branch with whitespace-only difference.
7. Branch with CRLF vs LF.
8. >10k LOC file is skipped.
9. Binary file is skipped.
10. Many branches; maxBranches limit is respected.
```

## 14.4 Blame parser tests

Use a temp repo with two commits by different authors:

```bash
git -c user.name=Alice -c user.email=a@example.com commit ...
git -c user.name=Bob -c user.email=b@example.com commit ...
```

Assert parsed fields:

```text
commitHash
author
author-mail
author-time
summary
line text
```

## 14.5 UI smoke tests

At minimum:

```text
- Opening a small Git-tracked file creates no exception.
- Changing document cancels previous analysis.
- Closing editor removes highlighters.
- Rendering visible range does not duplicate highlighters.
```

---

# 15. CI

GitHub Actions should run:

```bash
./gradlew check
./gradlew test
./gradlew verifyPlugin
./gradlew buildPlugin
```

The IntelliJ Platform Gradle Plugin provides tasks such as `runIde`, `test`, `testIde`, and plugin verification/build-related tasks; `runIde` starts an IDE instance with the built plugin loaded, and `test` is preconfigured to run with IntelliJ Platform test infrastructure. ([JetBrains Marketplace][14])

Workflow sketch:

```yaml
name: Build

on:
  push:
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - uses: gradle/actions/setup-gradle@v4

      - run: ./gradlew check verifyPlugin buildPlugin
```

---

# 16. Suggested Gradle skeleton

Use this as a starting point; the coding agent should adjust exact platform version to the intended supported IDE range.

```kotlin
// settings.gradle.kts
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

rootProject.name = "branch-lens"
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "dev.branchlens"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2026.1.1")
        bundledPlugin("Git4Idea")
        pluginVerifier()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.branchlens"
        name = "Branch Lens"
        version = project.version.toString()

        vendor {
            name = "Branch Lens"
        }

        description = """
            Shows local Git branches where the current file line or block differs.
        """.trimIndent()
    }
}

tasks.test {
    useJUnitPlatform()
}
```

Use `Git4Idea` only if needed for IDE integration. The MVP can shell out to local `git` and avoid depending on Git plugin APIs, but since IntelliJ IDEA already commonly includes the Git plugin, declaring it as a bundled dependency is acceptable when using Git-related IDE APIs.

---

# 17. Suggested `plugin.xml`

```xml
<idea-plugin>
    <id>dev.branchlens</id>
    <name>Branch Lens</name>
    <vendor>Branch Lens</vendor>

    <depends>com.intellij.modules.platform</depends>
    <depends optional="true">Git4Idea</depends>

    <extensions defaultExtensionNs="com.intellij">
        <projectService serviceImplementation="dev.branchlens.BranchLensProjectService"/>
        <projectConfigurable
            instance="dev.branchlens.settings.BranchLensConfigurable"
            displayName="Branch Lens"/>
    </extensions>
</idea-plugin>
```

---

# 18. Implementation milestones

## Milestone 1 — Buildable plugin skeleton

Deliverables:

```text
- Gradle project builds.
- Plugin loads in runIde.
- Settings page exists.
- Project service initializes and disposes cleanly.
- README explains local-only behavior.
```

Acceptance:

```text
./gradlew check buildPlugin succeeds.
Plugin appears in sandbox IDE.
No startup exceptions.
```

## Milestone 2 — Git CLI backend

Deliverables:

```text
- GitCli wrapper with timeout and cancellation.
- Repo detection.
- Current branch detection.
- Local branch listing.
- Branch blob reader.
- Binary/large-file guards.
```

Acceptance:

```text
Integration tests create temp repos and verify branch listing + blob reading.
No shell string execution.
All process arguments are passed as arrays.
```

## Milestone 3 — Unified diff parser

Deliverables:

```text
- Run git diff --no-index -U0 for current snapshot vs branch blob.
- Parse unified hunk headers.
- Parse added/removed/context lines.
- Classify line differences conservatively.
```

Acceptance:

```text
Parser tests pass for replacement, block change, deletion, insertion, multiple hunks.
No ambiguous block is reported as exact line replacement.
```

## Milestone 4 — File analysis engine

Deliverables:

```text
- Analyze all selected local branches for one file.
- Aggregate per-current-line summaries.
- Cache results by document hash and branch commit.
- Enforce max lines and branch limits.
```

Acceptance:

```text
Given a temp repo with 3 branches, analysis returns correct per-line branch differences.
Files >10k LOC return Skipped(reason = TOO_LARGE).
```

## Milestone 5 — Blame metadata

Deliverables:

```text
- Blame parser for --line-porcelain.
- Lazy or batched blame loading for branch-side lines.
- Popup displays author/date/commit summary when branch line exists.
- DeletedInBranch explicitly says no branch line exists to blame.
```

Acceptance:

```text
Tests verify author/date/summary are parsed.
No blame is run for unchanged branches.
No per-line per-branch blame loop.
```

## Milestone 6 — Editor + gutter rendering

Deliverables:

```text
- Attach to open editors.
- Debounced analysis on document change.
- Visible range rendering.
- Badge icons.
- Tooltip.
- Click popup.
- Remove stale highlighters.
```

Acceptance:

```text
Scrolling does not trigger Git diff if cached analysis is valid.
Editing cancels old analysis and re-renders after debounce.
Closing editor clears highlighters.
```

## Milestone 7 — Settings and branch filtering

Deliverables:

```text
- Enabled/disabled toggle.
- Max LOC setting.
- Max branches setting.
- Stale branch filter.
- Excluded branch patterns.
- Ignore whitespace option.
```

Acceptance:

```text
Changing settings re-runs analysis.
Excluded branches do not appear.
Whitespace-only changes are hidden only when option is enabled.
```

## Milestone 8 — Polish and hardening

Deliverables:

```text
- Diagnostics logs.
- Graceful Git timeout handling.
- User-facing skipped-file reason.
- README with screenshots/placeholders.
- CI workflow.
- Plugin verifier task.
```

Acceptance:

```text
No UI freeze during analysis.
No heavy work in listeners.
No Git process leaks after cancellation.
No highlighter leaks after editor close.
```

---

# 19. Important non-goals for v1

Do not implement these yet:

```text
- PSI-aware semantic line matching.
- Cross-file move detection in the diff engine.
- Remote branch analysis.
- Fetching.
- Pull request integration.
- Background indexing of the entire repo.
- ML/fuzzy matching.
- “Exact deleted-line author” resolution.
```

Optional v2 features:

```text
- Rename detection with git diff -M between branch tips.
- Lazy deletion commit resolver using git log -S/-G.
- Branch pinning.
- Tool window with all line differences.
- File-level heatmap.
- PSI owner display: class/function containing changed line.
```

Git supports rename detection through `-M` / `--find-renames`, but because the MVP uses temp-file `--no-index` diffs to support unsaved editor text, rename detection should be a separate v2 path rather than mixed into the first implementation. ([Git SCM][4])

---

# 20. Final coding-agent instruction

Paste this into the agent as the implementation mandate:

```text
Build an IntelliJ IDEA plugin named Branch Lens.

Goal:
For the currently opened text file, show gutter badges for visible lines/blocks that differ in other local Git branches. Use only local Git. Do not fetch or inspect remote branches.

Main simplification:
Do not implement a custom diff algorithm. For each selected local branch, read the branch blob for the current relative path, write the current editor document snapshot and branch blob to temp files, run:

git diff --no-index --unified=0 --no-color --no-ext-diff --text -- <currentTmp> <branchTmp>

Parse the unified diff hunks and map old/current-side removed lines to editor lines.

Hard constraints:
- Skip files over 10,000 LOC.
- Skip binary files.
- Do not run network operations.
- Do not run Git on EDT.
- Do not run long read actions.
- Do not shell-concatenate Git commands.
- Limit concurrent Git processes.
- Cancel analysis on document change/editor close/project disposal.
- Render only visible lines plus margin.
- Be conservative: ambiguous multi-line hunks must be shown as changed blocks, not fake exact line replacements.

Use:
- Kotlin.
- IntelliJ Platform Gradle Plugin 2.x.
- Java 21 toolchain.
- Project service with coroutine scope.
- RangeHighlighter + GutterIconRenderer for badges.
- Git CLI backend.
- JUnit tests with temporary real Git repositories.

Implement milestones:
1. Buildable plugin skeleton.
2. Git CLI backend.
3. Unified diff parser.
4. Hunk-to-line classifier.
5. Whole-file branch analyzer.
6. Blame parser using git blame --line-porcelain.
7. Gutter badge renderer.
8. Popup UI.
9. Settings.
10. CI and tests.

Badge semantics:
- Number badge: count of local branches where current line/block differs.
- ± badge: ambiguous changed block.
- + badge: branch has insertion after this line.
- ? badge: file missing in branch or unsupported branch state.

Popup must show:
- Branch name.
- Difference kind.
- Current text.
- Branch text when available.
- Branch line number when exact or block range is known.
- Blame author/date/commit summary for branch-side lines when available.
- Honest message for deleted lines: no branch-side line exists to blame.

Do not implement PSI semantic matching, remote branches, fetch, PR integrations, or deletion-commit search in v1.
```

The simplified version is strong enough for an MVP: Git handles the diff, Git handles blame, and the plugin’s job is mostly safe orchestration, caching, hunk classification, and UI.

[1]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html "IntelliJ Platform Gradle Plugin (2.x) | IntelliJ Platform Plugin SDK"
[2]: https://plugins.jetbrains.com/docs/intellij/creating-plugin-project.html "Creating a Plugin Gradle Project | IntelliJ Platform Plugin SDK"
[3]: https://plugins.jetbrains.com/docs/intellij/plugin-github-template.html "IntelliJ Platform Plugin Template | IntelliJ Platform Plugin SDK"
[4]: https://git-scm.com/docs/git-diff "Git - git-diff Documentation"
[5]: https://git-scm.com/docs/git-for-each-ref "Git - git-for-each-ref Documentation"
[6]: https://git-scm.com/docs/git-show "Git - git-show Documentation"
[7]: https://git-scm.com/docs/git-cat-file "Git - git-cat-file Documentation"
[8]: https://git-scm.com/docs/git-blame "Git - git-blame Documentation"
[9]: https://plugins.jetbrains.com/docs/intellij/launching-coroutines.html "Launching Coroutines | IntelliJ Platform Plugin SDK"
[10]: https://plugins.jetbrains.com/docs/intellij/threading-model.html "Threading Model | IntelliJ Platform Plugin SDK"
[11]: https://blog.jetbrains.com/platform/2026/03/ui-freezes-and-the-dangers-of-non-cancellable-read-actions-in-background-threads/ "UI Freezes and the Dangers of Non-Cancellable Read Actions in Background Threads | The JetBrains Platform Blog"
[12]: https://github.com/JetBrains/intellij-community/blob/master/platform/editor-ui-api/src/com/intellij/openapi/editor/markup/RangeHighlighter.java "intellij-community/platform/editor-ui-api/src/com/intellij/openapi/editor/markup/RangeHighlighter.java at master · JetBrains/intellij-community · GitHub"
[13]: https://github.com/JetBrains/intellij-community/blob/master/platform/editor-ui-api/src/com/intellij/openapi/editor/markup/GutterIconRenderer.java "intellij-community/platform/editor-ui-api/src/com/intellij/openapi/editor/markup/GutterIconRenderer.java at master · JetBrains/intellij-community · GitHub"
[14]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html?from=DevKit&utm_campaign=devkit&utm_source=product "Tasks | IntelliJ Platform Plugin SDK"

