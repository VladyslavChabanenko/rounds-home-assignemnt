<!--
Sync Impact Report
==================
Version change: template (unversioned) → 1.0.0
Bump rationale: initial ratification. An uncommitted first draft was revised before adoption to
remove feature-specific requirements; it stays 1.0.0 because no earlier version was adopted.

Modified principles (template placeholder → new title):
- [PRINCIPLE_1_NAME] → I. Modular, Library-First Architecture
- [PRINCIPLE_2_NAME] → II. Java & Kotlin Interoperability (NON-NEGOTIABLE)
- [PRINCIPLE_3_NAME] → III. Coroutines & Structured Concurrency (NON-NEGOTIABLE)
- [PRINCIPLE_4_NAME] → IV. MVVM with Android Views (NON-NEGOTIABLE)
- [PRINCIPLE_5_NAME] → V. Main-Thread & Resource Safety

Added sections:
- VI. Testable by Design
- VII. Simplicity & Scope Discipline
- Technology Stack & Code Standards (was [SECTION_2_NAME])
- Development Workflow & Quality Gates (was [SECTION_3_NAME])

Removed sections: none (relative to the template)

Templates and guidance:
- ✅ .specify/templates/plan-template.md: Constitution Check pre-filled with gates I–VII
- ✅ .specify/templates/tasks-template.md: tests required per Principle VI; Android module paths
- ✅ .claude/skills/speckit-tasks/SKILL.md: "Tests are OPTIONAL" rule now defers to constitution
- ✅ .specify/templates/spec-template.md: reviewed, no change needed
- ✅ .specify/templates/checklist-template.md: reviewed, no change needed
- ✅ CLAUDE.md: reviewed, no change needed (points to the current plan)
- n/a .specify/templates/commands/*.md: directory not present (commands live in .claude/skills/)

Deferred TODOs: none
-->

# Rounds Home Assignment Constitution

## Core Principles

### I. Modular, Library-First Architecture

- Reusable functionality MUST live in dedicated Android library modules. App modules consume a
  library only through its public API, and libraries MUST NOT depend on app modules.
- Library modules MUST be compiled with Kotlin explicit API mode; anything outside the
  documented contract is `internal` or `private`.
- Library runtime dependencies are limited to the Android SDK, AndroidX, and kotlinx.coroutines.
  Any other runtime dependency MUST be justified in `plan.md`.

**Rationale**: Libraries in this repository are deliverables in their own right. Clear module
boundaries and a narrow, explicit API keep them reusable, reviewable, and free to change
internally.

### II. Java & Kotlin Interoperability (NON-NEGOTIABLE)

- Every public library API MUST be callable idiomatically from Java. Asynchronous operations
  MUST have a non-`suspend` entry point (fire-and-forget, a `fun interface` callback, or a
  cancellable handle). `suspend` variants MAY be added for Kotlin callers but MUST NOT be the
  only option.
- Public signatures MUST NOT expose constructs that are unusable or awkward from Java:
  `kotlin.Result`, value/inline classes (including `kotlin.time.Duration`), Kotlin function
  types as callbacks (use `fun interface`), or default arguments without `@JvmOverloads`.
- Static-style access MUST use `@JvmStatic` so Java callers never write `Companion` or
  `INSTANCE`; public top-level or extension functions MUST set `@file:JvmName`.
- Each library MUST have at least one Java source file (test or sample) that compiles against
  and exercises its public API, so the build proves interoperability.

**Rationale**: Libraries here must serve both Kotlin and Java callers. Java compatibility
regresses silently unless the build checks it.

### III. Coroutines & Structured Concurrency (NON-NEGOTIABLE)

- All asynchronous work MUST use Kotlin Coroutines. Raw `Thread`s, `AsyncTask`, ad-hoc
  `Executor`s, RxJava, and callback chains for concurrency are prohibited.
- Every coroutine MUST run in an owned scope: a library-owned scope with a `SupervisorJob`, or
  `viewModelScope`/`lifecycleScope` in apps. `GlobalScope` is prohibited, and so is
  `runBlocking` outside tests.
- Dispatchers MUST be injected, not hardcoded inside classes. Blocking I/O runs on an I/O
  dispatcher, CPU-heavy work on a default dispatcher, and UI updates on the main dispatcher.
- Work tied to a UI element or lifecycle MUST be cancelled when its owner is destroyed or when
  newer work supersedes it (e.g. a recycled list row is rebound). Results of superseded work
  MUST NOT be applied.
- Asynchronous work MUST NOT keep Views, Activities, or Fragments reachable after it completes
  or is cancelled.
- `CancellationException` MUST NOT be swallowed; catch-all handlers around suspend code MUST
  rethrow it.

**Rationale**: Structured scopes make cancellation and lifecycle handling correct by
construction, and injected dispatchers make concurrent code testable with virtual time.

### IV. MVVM with Android Views (NON-NEGOTIABLE)

- App UI MUST use Android Views: XML layouts with ViewBinding. Jetpack Compose (its
  dependencies, `buildFeatures.compose`, or Compose interop) is prohibited.
- Apps MUST follow MVVM with unidirectional data flow: View (Activity/Fragment and adapters)
  → ViewModel → Repository → data source.
- ViewModels MUST expose UI state as a read-only `StateFlow` of an immutable state type
  (e.g. a sealed interface covering loading, content, and error). ViewModels MUST NOT hold
  references to Views, Activities, or Fragments.
- Views render state and forward user actions to ViewModel methods. They contain no business,
  networking, or persistence logic.
- Views MUST collect flows lifecycle-aware (`repeatOnLifecycle`). State MUST survive
  configuration changes without reloading data.
- Lists MUST use `RecyclerView` with `ListAdapter`/`DiffUtil`.
- Dependencies are provided by constructor injection (e.g. a `ViewModelProvider.Factory`). A DI
  framework is not required.

**Rationale**: Unidirectional, lifecycle-aware state keeps the UI predictable across
configuration changes and makes ViewModels unit-testable without Android UI.

### V. Main-Thread & Resource Safety

- Network, disk I/O, and CPU-heavy work MUST NOT run on the main thread. Debug builds of apps
  MUST enable a `StrictMode` thread policy that at least logs violations.
- Every cache or in-memory collection of large objects (e.g. bitmaps, byte buffers) MUST be
  bounded by size.
- Streams, connections, and other closeables MUST be closed deterministically (`use {}`).
  Network calls MUST set connect and read timeouts.
- Files a module persists MUST live in app-private storage and be written atomically (temp file,
  then rename). Unreadable or corrupt files are treated as absent.
- Runtime failures inside a library (network, I/O, parsing) MUST NOT crash the host app. They
  are reported through the library's public API or leave a defined fallback state.

**Rationale**: Mobile apps run with tight memory, unreliable networks, and a single UI thread.
These rules keep the UI responsive, keep memory use predictable, and contain library failures.

### VI. Testable by Design

- Time (clock), dispatchers, and network/disk access MUST sit behind injectable abstractions, so
  logic is unit-tested on the JVM without real network, real time, or `Thread.sleep`.
- Unit tests are REQUIRED for: each library's public behavior, including its time-,
  concurrency-, and failure-dependent paths; data parsing and mapping; and ViewModel state
  transitions.
- Coroutine tests MUST use `kotlinx-coroutines-test` (`runTest`, test dispatchers, virtual
  time). Every test MUST be deterministic.
- Hand-written fakes SHOULD be preferred over mocking frameworks.
- Instrumented and UI tests are OPTIONAL unless a spec requires them.

**Rationale**: Time- and concurrency-dependent behavior can be verified reliably only with
injected clocks and dispatchers, and tests let a reviewer confirm requirements quickly.

### VII. Simplicity & Scope Discipline

- Build what the spec requires, nothing more. Features, options, and extension points that do
  not trace to a spec requirement are prohibited.
- No abstraction with a single implementation unless it exists for testability (Principle VI).
- Platform and AndroidX building blocks SHOULD be preferred over custom equivalents.
- Every new dependency MUST be justified in `plan.md`.

**Rationale**: Reviewers judge clarity as much as capability. Less code means fewer bugs and a
faster review.

## Technology Stack & Code Standards

**Stack**

- Kotlin (latest stable) for all production code. Java is used only to verify interoperability
  (Principle II).
- Gradle Kotlin DSL with a version catalog (`gradle/libs.versions.toml`); module build scripts
  contain no hardcoded versions.
- At project creation, use the latest stable AGP, Kotlin, AndroidX, and kotlinx.coroutines.
  `compileSdk`/`targetSdk` are the latest stable API level; `minSdk` is declared once and shared.
- Apps use AppCompat/Material Components and AndroidX Lifecycle (ViewModel, runtime). App
  modules MAY use mainstream non-UI libraries (e.g. Retrofit/OkHttp, kotlinx.serialization)
  when justified in `plan.md`.

**Code standards**

- Follow the official Kotlin coding conventions (`kotlin.code.style=official`). Formatting
  SHOULD be enforced by ktlint or an equivalent tool.
- Android Lint MUST report zero errors. New warnings in touched code are fixed or suppressed
  with a stated reason.
- No `!!` in production code.
- Immutability by default: `val`, read-only collections, `data class` for models, and
  `sealed interface` for finite states and results.
- No silently swallowed exceptions: errors become typed states or results, or are logged.
- No hardcoded user-visible strings, colors, or dimensions in code or layouts. Images have a
  `contentDescription` or are explicitly marked decorative.
- Every public library declaration has KDoc.
- Classes and functions are small and single-purpose. No commented-out code and no untracked
  TODOs.

## Development Workflow & Quality Gates

- Work follows the Spec Kit flow: `/speckit-specify` → `/speckit-clarify` (as needed) →
  `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`. `/speckit-plan` MUST pass the
  Constitution Check before research and again after design.
- Definition of done for any change: `./gradlew build` passes (all modules compile, unit tests
  pass, Lint has no errors) and the change meets its spec's acceptance scenarios.
- Commits are small, focused, and buildable, with imperative messages. Conventional Commits
  prefixes are encouraged.
- The README MUST cover each library's usage from Kotlin and Java and its behavioral guarantees
  (threading, errors, lifecycle), plus the app architecture and how to build, run, and test.
- No secrets or machine-local files (e.g. `local.properties`) are committed; the repository is
  publishable as-is.

## Governance

- This constitution supersedes all other practices and guidance in this repository. When
  guidance conflicts with it, the constitution wins until it is amended.
- Principles marked NON-NEGOTIABLE MUST NOT be waived; changing them requires an amendment. A
  deviation from any other MUST rule requires a justified entry in the plan's Complexity
  Tracking table.
- Feature-specific requirements belong in specs and plans, not in this constitution.
- Amendments are made through `/speckit-constitution`. Each one includes an updated Sync Impact
  Report and updates dependent templates in the same change.
- Versioning follows semver. MAJOR: a principle is removed or redefined. MINOR: a principle or
  section is added, or guidance is materially expanded. PATCH: clarifications and wording.
- Compliance is checked at `/speckit-plan` (Constitution Check gate), by `/speckit-analyze`
  (violations are CRITICAL), and in every code review.
- Runtime development guidance lives in `CLAUDE.md` and the current feature's `plan.md`.

**Version**: 1.0.0 | **Ratified**: 2026-10-27 | **Last Amended**: 2026-10-27
