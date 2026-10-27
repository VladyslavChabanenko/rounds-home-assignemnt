# Implementation Plan: Image Loader Library and Example App

**Branch**: `001-image-loader-library` | **Date**: 2026-10-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-image-loader-library/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

Build a two-module Android project in this folder:

- `:imageloader`, a Kotlin library. One call shows the image at a URL in an `ImageView`, with a
  placeholder (resource or drawable) and an optional error image. Images are cached in memory
  (decoded, `LruCache`) and on disk (original bytes in app-private `cacheDir`) for 4 hours from
  download. Concurrent requests for one URL share one download. `invalidateCache()` discards
  everything. It is built only on platform APIs (`HttpURLConnection`, `BitmapFactory`,
  `LruCache`, `java.io` files), kotlinx.coroutines and AndroidX Lifecycle. Java callers get the
  same single-call API through overloads and a `@JvmStatic` accessor.
- `:app`, an example app built with Views and MVVM. It fetches the published JSON list and shows it in a
  `RecyclerView`, with a loading state, an error state with retry, and an "Invalidate cache"
  toolbar action that reloads the visible rows.

Design decisions and their alternatives are in [research.md](research.md). Entities and state
rules are in [data-model.md](data-model.md). The library's public contract is in
[contracts/image-loader-api.md](contracts/image-loader-api.md) and the consumed JSON format is in
[contracts/image-list-json.md](contracts/image-list-json.md). Build, run and verification steps
are in [quickstart.md](quickstart.md).

## Technical Context

**Language/Version**: Kotlin 2.4.21 for all production code; Java 17 source/bytecode level. Java
appears only in the library's interop test (Constitution II).

**Primary Dependencies**:

- Build: Android Gradle Plugin 9.4.1 (built-in Kotlin), Gradle 9.8.2, version catalog
  `gradle/libs.versions.toml`, ktlint Gradle plugin 14.2.0
- `:imageloader` runtime: kotlinx-coroutines-android 1.11.0, androidx.lifecycle
  lifecycle-runtime-ktx 2.11.0, androidx.annotation 1.11.0
- `:app` runtime: AppCompat 1.8.0, Material Components 1.14.0, RecyclerView 1.4.0, activity-ktx
  1.13.0, lifecycle-viewmodel-ktx / lifecycle-runtime-ktx 2.11.0, core-ktx 1.19.1,
  kotlinx-serialization-json 1.12.0 (+ the Kotlin serialization compiler plugin)

**Storage**: Library: one file per URL under `context.cacheDir/image_loader/` (small header with
the download time, then the original image bytes), plus an in-memory `LruCache` of decoded
bitmaps. App: none. The list lives in the ViewModel only (spec Assumptions: no offline list).

**Testing**: JUnit 4.13.2, kotlinx-coroutines-test 1.11.0, Robolectric 4.17 (views, bitmaps and
the Java interop test on the JVM; tests pinned to SDK 34 so JDK 17 works), androidx.test:core 1.7.0, OkHttp `mockwebserver3` 5.5.1 (HTTP
client tests only). Everything runs with `./gradlew test`. There are no instrumented tests
(Constitution VI: optional, and the spec does not require them).

**Target Platform**: Android phones, `minSdk` 24, `compileSdk`/`targetSdk` 37. Builds with JDK 17
or newer (AGP 9.4 needs at least 17).

**Project Type**: Android library plus example app (Gradle multi-module).

**Performance Goals**: list of ids visible within 3 s on a typical connection (SC-001); no
network, disk or decode work on the main thread, so scrolling 50 rows back and forth stays smooth
(SC-006); at most one download per distinct URL, which is 45 for the current list (SC-005).

**Constraints**: no third-party image libraries; 4-hour TTL from download time, fixed; stalled
downloads abandoned after 30 s without data (FR-013); a 23.6 MB JPEG must display without OOM
(FR-010); memory cache = 1/8 of max heap; disk cache = 100 MB; Java callers need no coroutine
code (FR-017).

**Scale/Scope**: 2 modules, 1 screen, 50 list entries. Measured on 2026-10-27: 45 distinct URLs
totalling about 40 MB (largest 23.6 MB, next largest 0.7 MB), 5 URLs return HTTP 403.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Initial gate (before research): **PASS**. Re-check after Phase 1 design: **PASS**, no violations.
Notes per gate:

- [x] **I. Modularity**: reusable code lives in library modules used only via their public API;
  explicit API mode; library deps limited to Android SDK, AndroidX, kotlinx.coroutines (others
  justified)
  - `:imageloader` holds all loading and caching code. `:app` depends on it as
    `implementation(project(":imageloader"))` and uses only `ImageLoader`. Explicit API mode is
    enabled in `:imageloader` (research R1). Its runtime dependencies are coroutines, AndroidX
    Lifecycle and AndroidX Annotation, so no justification is needed.
- [x] **II. Java interop**: public library API is Java-friendly (non-`suspend` entry points, no
  Kotlin-only types); a Java call site is planned for each library
  - The public API is `ImageLoader.get(Context)` (`@JvmStatic`), `load(...)` overloads (resource
    or `Drawable`, `@JvmOverloads` for the optional error image) and `invalidateCache()`. All
    return `Unit` (fire-and-forget). No `suspend`, `Result`, `Duration` or function types appear
    in the API. A Java test, `ImageLoaderJavaInteropTest.java`, calls every public method.
- [x] **III. Coroutines**: all async work in owned scopes with injected dispatchers; superseded
  work is cancelled; no `GlobalScope`/threads/RxJava
  - Requests run in the target view's `lifecycleScope` (cancelled on `ON_DESTROY`), before
    attachment in the context owner's scope, or in the library's `SupervisorJob` scope when there
    is no owner. Once the view is laid out, its view-tree owner's `ON_DESTROY` also cancels the
    request (research R4). Shared downloads run in the library scope and are cancelled when their
    last waiter leaves. Main, IO and Default
    dispatchers are constructor parameters, and `ImageLoader.get()` is the only place that names
    `Dispatchers.*`. A new `load()` on a view cancels the view's previous request. Catch-all
    blocks rethrow `CancellationException`.
- [x] **IV. MVVM + Views**: XML + ViewBinding, no Compose; ViewModel exposes `StateFlow` UI state
  - The app has one Activity using ViewBinding, a `ListAdapter`, and `ImageListViewModel`
    exposing `StateFlow<ImageListUiState>` (sealed Loading / Content / Error) plus a one-off
    event flow for the confirmation message. The flow of control is View → ViewModel →
    `ImageListRepository` → `ImageListDataSource`. Dependencies arrive by constructor injection
    through a `ViewModelProvider.Factory`. The UI is collected with `repeatOnLifecycle`.
- [x] **V. Main-thread & resources**: no main-thread I/O; bounded caches; closed resources;
  atomic file writes; library failures contained
  - Disk I/O runs on a serialized IO dispatcher, network on IO, decoding on Default. The memory
    cache is limited to 1/8 of the heap and the disk cache to 100 MB, both evicting LRU. Streams
    and connections are closed with `use {}`/`finally`. Connect and read timeouts are 30 s. Writes
    go to a temp file, then `renameTo`. Corrupt entries are treated as absent. Every failure
    becomes the error/placeholder fallback plus a log line, never an exception in the host. A
    debug-only `StrictMode` thread policy is set in `SampleApplication`.
- [x] **VI. Tests**: required unit tests identified (library behavior incl. time, concurrency and
  failure paths; parsing; ViewModels)
  - See [Test Strategy](#test-strategy). The clock, downloader and dispatchers are injectable.
    Coroutine tests use `runTest`, and no test uses the real network (MockWebServer is local).
    `HttpDownloaderTest` is the only test that waits in real time, because socket timeouts cannot
    be faked: its stall case waits for a 200 ms timeout against a 2 s pause, and its other cases
    use 10 s timeouts that are never reached.
- [x] **VII. Simplicity**: nothing beyond the spec; every new dependency justified
  - No request handles, transformations, crossfades, configurable TTL, HTTP cache headers or
    per-URL invalidation. Each abstraction (`Downloader`, `Clock`, `ImageListDataSource`) exists
    for testing. See [Dependency Justification](#dependency-justification) for the dependencies.

## Project Structure

### Documentation (this feature)

```text
specs/001-image-loader-library/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   ├── image-loader-api.md
│   └── image-list-json.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

The Gradle root is this folder (`rounds-home-assignment/`, the folder that holds `CLAUDE.md` and
`.specify/`). The Git root is its parent. The parent's `.gitignore` ignores only a root-level
`/build`, so this folder gets its own `.gitignore` for module `build/` directories.

```text
rounds-home-assignment/
├── .gitignore                         # build/, .kotlin/, local.properties
├── README.md                          # Kotlin + Java usage, guarantees, architecture, build/run/test
├── settings.gradle.kts                # includes :imageloader, :app
├── build.gradle.kts                   # plugins (apply false), shared minSdk/compileSdk via catalog
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml             # all versions (no versions in module scripts)
│   └── wrapper/gradle-wrapper.properties
├── gradlew, gradlew.bat
│
├── imageloader/                       # Android library, namespace io.github.vladyslavchabanenko.imageloader
│   ├── build.gradle.kts               # explicitApi(), unitTests.isIncludeAndroidResources/isReturnDefaultValues
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml    # INTERNET permission
│       │   ├── res/values/ids.xml     # view tag id for the active request
│       │   └── java/io/github/vladyslavchabanenko/imageloader/
│       │       ├── ImageLoader.kt     # public: get(), load() overloads, invalidateCache(); request lifecycle
│       │       ├── ImageFetcher.kt    # internal: per-URL dedup (ref-counted), disk → network, disk write
│       │       ├── DiskCache.kt       # internal: file format, TTL, LRU index, 100 MB limit, clear
│       │       ├── HttpDownloader.kt  # internal: Downloader fun interface + HttpURLConnection impl
│       │       ├── BitmapDecoder.kt   # internal: bounds → inSampleSize → scale-to-cover
│       │       ├── ViewSize.kt        # internal: cancellable await of the target's laid-out size
│       │       └── Clock.kt           # internal: fun interface Clock (wall time, injectable)
│       └── test/
│           ├── java/io/github/vladyslavchabanenko/imageloader/
│           │   └── ImageLoaderJavaInteropTest.java   # Robolectric; every public API from Java
│           └── kotlin/io/github/vladyslavchabanenko/imageloader/
│               ├── DiskCacheTest.kt          # JVM: TTL boundary, clock set back, corrupt, LRU, clear, atomic write
│               ├── ImageFetcherTest.kt       # JVM: dedup, ref-counted cancel, failure not cached, generations
│               ├── HttpDownloaderTest.kt     # MockWebServer: 200, 403, stall → timeout, cancellation
│               ├── BitmapDecoderTest.kt      # sample-size math (JVM) + Robolectric native decode
│               ├── ImageLoaderTest.kt        # Robolectric: placeholder, success, error, supersede, lifecycle, invalidate
│               └── fakes/                    # FakeClock, FakeDownloader, FakeLifecycleOwner
│
└── app/                               # Example app, applicationId io.github.vladyslavchabanenko.imageloader.sample
    ├── build.gradle.kts               # viewBinding, buildConfig, serialization plugin
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml    # INTERNET, SampleApplication, ImageListActivity
        │   ├── java/io/github/vladyslavchabanenko/imageloader/sample/
        │   │   ├── SampleApplication.kt          # debug StrictMode thread policy
        │   │   ├── data/
        │   │   │   ├── ImageEntry.kt             # domain model (id, imageUrl)
        │   │   │   ├── ImageListParser.kt        # JSON → List<ImageEntry>, skips invalid entries
        │   │   │   ├── ImageListDataSource.kt    # interface + HttpURLConnection implementation
        │   │   │   └── ImageListRepository.kt    # fetch + parse on the IO dispatcher
        │   │   └── ui/
        │   │       ├── ImageListActivity.kt      # ViewBinding, menu action, render state, events
        │   │       ├── ImageListViewModel.kt     # StateFlow<ImageListUiState>, events, Factory
        │   │       ├── ImageListUiState.kt       # sealed Loading/Content/Error, ImageListItem, ImageListEvent
        │   │       └── ImageListAdapter.kt       # ListAdapter + DiffUtil (+ reload payload)
        │   └── res/
        │       ├── layout/activity_image_list.xml, item_image.xml
        │       ├── menu/image_list.xml           # "Invalidate cache" action (always shown)
        │       ├── drawable/ic_image_placeholder.xml, ic_broken_image.xml
        │       └── values/strings.xml, dimens.xml, colors.xml, themes.xml
        └── test/java/io/github/vladyslavchabanenko/imageloader/sample/
            ├── data/ImageListParserTest.kt
            ├── data/ImageListRepositoryTest.kt
            └── ui/ImageListViewModelTest.kt     # MainDispatcherRule + fake data source
```

**Structure Decision**: a Gradle multi-module Android project with one library module
(`:imageloader`) and one application module (`:app`), as Constitution I and the assignment ("in
the same project") require. Kotlin sources sit under `src/*/java/` (the Android default) and
the Java interop test sits under `src/test/java/`. The namespace `io.github.vladyslavchabanenko`
follows the GitHub-hosted convention and is an assumption that is easy to change. Library code
lives in one flat package because it is small. Everything except `ImageLoader` is `internal`.

## Test Strategy

The constitution (Principle VI) requires the tests below. Each is a JVM unit test run by
`./gradlew test`. FR numbers trace back to the spec.

| Area | Test class | Behaviors covered | FR / SC |
|------|-----------|-------------------|---------|
| Disk cache | `DiskCacheTest` | hit within TTL; miss at exactly downloadedAt + 4 h; miss when clock < downloadedAt; persists across new `DiskCache` instance (restart); corrupt/short file → absent and deleted; leftover `.tmp` ignored and cleaned; LRU eviction past the size limit, with access refreshing order; `clear()` removes all; write failure leaves no entry | FR-003–006, 014, 015; SC-002, 003 |
| Fetch/dedup | `ImageFetcherTest` | two concurrent fetches of one URL → 1 download; disk hit → 0 downloads; network failure → exception, nothing written, retry downloads again; last waiter cancelled → download cancelled, other waiter keeps it alive; disk write failure still returns bytes; new generation after invalidation → disk ignored, fresh download | FR-005, 006, 009, 012; SC-004, 005 |
| HTTP | `HttpDownloaderTest` | 200 → exact bytes; 403/500 → failure; stalled body → timeout failure (short injected timeout); cancellation stops the read | FR-011, 013 |
| Decode | `BitmapDecoderTest` | `inSampleSize` math (pure), scale-to-cover math (pure); real JPEG decode at target size (Robolectric native graphics); invalid bytes → failure | FR-010, 011 |
| Loader | `ImageLoaderTest` | placeholder shown synchronously; image shown on success; error image on failure, placeholder kept when no error image; memory hit shows synchronously without placeholder; rebinding view → old result never applied; `ON_DESTROY` cancels the request and download; `invalidateCache()` → next load downloads again; cache-sourced result invalidated mid-flight is not shown | FR-001, 002, 007, 008, 011, 016; SC-004 |
| Java interop | `ImageLoaderJavaInteropTest` (Java) | `ImageLoader.get`, all four `load` overloads, `invalidateCache` compile and run from Java; placeholder set | FR-017; SC-008 |
| Parsing | `ImageListParserTest` | real list sample parses 50 entries in order; duplicates kept; entries missing id/url or with wrong types skipped; non-array or malformed JSON → failure | FR-018; edge "Malformed list" |
| Repository | `ImageListRepositoryTest` | data source failure propagates; success maps entries | FR-018, 020 |
| ViewModel | `ImageListViewModelTest` | Loading → Content; Loading → Error → retry → Content; invalidate → invalidator called once, item generation bumped (rows reload), `CacheInvalidated` event; state retained (no refetch) for a second collector | FR-020–022 |

Manual acceptance checks (rotation, airplane mode, clock change, scrolling, the 24 MB image) are
listed in [quickstart.md](quickstart.md).

## Dependency Justification

Constitution I and VII require a reason for every dependency beyond the Android SDK, AndroidX
and kotlinx.coroutines.

| Dependency | Scope | Why | Simpler alternative rejected because |
|------------|-------|-----|--------------------------------------|
| kotlinx-serialization-json (+ plugin) | `:app` runtime | Typed, null-safe parsing of the list, tested on the plain JVM | `org.json` is stubbed in JVM unit tests (needs Robolectric or an extra test artifact) and gives untyped, manual parsing |
| Robolectric | test | Runs the Android framework (views, `BitmapFactory`, `LruCache`, lifecycle) on the JVM for loader and interop tests | Mocking framework classes would test the mocks, not behavior. Instrumented tests need a device and are optional per Principle VI |
| androidx.test:core | test | `ApplicationProvider` for a Robolectric `Context` | None simpler |
| mockwebserver3 | `:imageloader` test | Real local HTTP server for status-code, stall/timeout and cancellation tests of `HttpDownloader` | Faking `HttpURLConnection` leaves the timeout and status handling untested |
| ktlint Gradle plugin | build | Enforces official Kotlin style in `./gradlew build` (the Technology Stack section says SHOULD) | Manual formatting is unenforced |

No runtime library outside AndroidX and kotlinx is added to `:imageloader`.

## Complexity Tracking

No Constitution Check violations. Nothing to justify.
