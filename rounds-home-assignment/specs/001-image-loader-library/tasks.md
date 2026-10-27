---

description: "Task list for the image loader library and example app"
---

# Tasks: Image Loader Library and Example App

**Input**: Design documents from `specs/001-image-loader-library/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Required. Constitution VI requires unit tests for library behavior (time, concurrency
and failure paths), parsing and ViewModels. Constitution II requires a Java interop test. The
cases come from plan.md § Test Strategy. There are no instrumented tests. In each story, write the
tests first. In Kotlin, a test that refers to a class that does not exist yet fails to compile,
and that counts as a failing test.

**Organization**: Tasks are grouped by user story. The library grows story by story: US1 loads
and displays images, US2 adds the caches, US3 adds invalidation, and US4 proves Java interop.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: The user story the task belongs to (US1–US4)

## Path Conventions

All paths are relative to the Gradle root `rounds-home-assignment/`, the folder that holds
`CLAUDE.md`. The Git root is its parent. Run `./gradlew` from the Gradle root.

- Library sources: `imageloader/src/main/java/io/github/vladyslavchabanenko/imageloader/`
- Library Kotlin tests: `imageloader/src/test/kotlin/io/github/vladyslavchabanenko/imageloader/`
- Library Java interop test: `imageloader/src/test/java/io/github/vladyslavchabanenko/imageloader/`
- App sources: `app/src/main/java/io/github/vladyslavchabanenko/imageloader/sample/`
- App resources: `app/src/main/res/`
- App tests: `app/src/test/java/io/github/vladyslavchabanenko/imageloader/sample/`

## Deviations from the design documents

These close gaps found while deriving the tasks. Items 1–3 and `PixelSize` are recorded in
research.md (R4, R8, R9, R10) and data-model.md. Items 4–6 change only file lists: plan.md's
Project Structure still lists `colors.xml` and omits the test helpers. Item 7 was found while
implementing and is recorded in research.md (R5).

1. **Memory-cache entries expire too (G4, SC-003).** Research R9 caches bitmaps with no timestamp,
   so a process that stays alive past 4 hours would keep showing images from memory. Memory
   entries therefore carry `downloadedAtMillis` and are checked with the same validity rule as
   disk entries. `DiskCache.get` and `FetchResult` return the download time (T040, T042, T044).
2. **The disk directory is resolved lazily.** `Context.getCacheDir()` touches the disk, and
   `ImageLoader.get()` runs on the main thread, so `DiskCache` takes a `Lazy<File>` (T040, T044).
3. **`DiskCache` time handling.** `get(url)` reads the injected clock. `put(url, bytes,
   downloadedAtMillis)` takes the time the fetcher recorded, so the disk and memory entries agree.
   Research R8 mentions both a clock and `now` parameters.
4. **Helpers not listed in the plan**: `PixelSize` (in `ViewSize.kt`), `fakes/TestImages.kt`
   (JPEG bytes for decode tests) and `MainDispatcherRule.kt`. `BitmapDecoder` is an `object`.
5. **No `colors.xml`.** Colors come from Material 3 theme attributes.
6. **`.gitignore` also ignores `.gradle/` and `.idea/`**, so the Gradle root stays clean if it is
   delivered on its own.
7. **`awaitSize()` also waits for a pre-draw callback (T013).** Found during T037: a recycled row
   re-attached with unchanged bounds gets no layout notification, so the layout listener alone
   never resumed. See research R5. `ViewSizeTest` reproduces it.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: a two-module Gradle build that compiles, lints and runs (empty) tests.

- [X] T001 [P] Create `.gitignore` in the Gradle root ignoring `build/`, `.gradle/`, `.kotlin/`, `.idea/` and `local.properties` (the parent repo's `.gitignore` ignores only a root-level `/build`)
- [X] T002 [P] Create the version catalog `gradle/libs.versions.toml` with exactly the versions in plan.md Technical Context and research R1:
  - `[versions]`: `agp` 9.4.1, `kotlin` 2.4.21, `coroutines` 1.11.0, `serialization` 1.12.0, `coreKtx` 1.19.1, `appcompat` 1.8.0, `activity` 1.13.0, `lifecycle` 2.11.0, `recyclerview` 1.4.0, `annotation` 1.11.0, `material` 1.14.0, `junit` 4.13.2, `robolectric` 4.17, `androidxTestCore` 1.7.0, `mockwebserver` 5.5.1, `ktlintGradle` 14.2.0, `minSdk` 24, `compileSdk` 37, `targetSdk` 37
  - `[libraries]`: `kotlinx-coroutines-android`, `kotlinx-coroutines-test`, `kotlinx-serialization-json` (group `org.jetbrains.kotlinx`), `androidx-core-ktx`, `androidx-appcompat`, `androidx-activity-ktx`, `androidx-lifecycle-runtime-ktx`, `androidx-lifecycle-viewmodel-ktx`, `androidx-recyclerview`, `androidx-annotation`, `material` (`com.google.android.material:material`), `junit`, `robolectric` (`org.robolectric:robolectric`), `androidx-test-core` (`androidx.test:core`), `mockwebserver3` (`com.squareup.okhttp3:mockwebserver3`)
  - `[plugins]`: `android-application` (`com.android.application`), `android-library` (`com.android.library`), `kotlin-android` (`org.jetbrains.kotlin.android`, used only to pin KGP), `kotlin-serialization` (`org.jetbrains.kotlin.plugin.serialization`), `ktlint` (`org.jlleitschuh.gradle.ktlint`)
- [X] T003 [P] Create `gradle.properties` with `org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8`, `android.useAndroidX=true` and `kotlin.code.style=official`
- [X] T004 [P] Create `.editorconfig` (research R15): `root = true`. `[*]` sets UTF-8, LF and a final newline. `[*.{kt,kts}]` sets `indent_size = 4`, `ktlint_code_style = ktlint_official` and `ij_kotlin_imports_layout = *,java.**,javax.**,kotlin.**,^`. `[*.xml]` sets `indent_size = 4`
- [X] T005 Create `settings.gradle.kts`: `pluginManagement` repositories (`google()` limited to the `com.android.*`, `com.google.*` and `androidx.*` groups, then `mavenCentral()`, `gradlePluginPortal()`), `dependencyResolutionManagement` with `repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)`, `google()` and `mavenCentral()`, `rootProject.name = "rounds-home-assignment"`, and `include(":imageloader", ":app")`
- [X] T006 Create the root `build.gradle.kts` with only a `plugins {}` block that declares `libs.plugins.android.application`, `android.library`, `kotlin.android`, `kotlin.serialization` and `ktlint`, all with `apply false`. Declaring `kotlin.android` here pins KGP 2.4.21 instead of AGP's bundled version (research R1)
- [X] T007 [P] Create `imageloader/build.gradle.kts` and `imageloader/src/main/AndroidManifest.xml`:
  - plugins: `android.library` and `ktlint` only (AGP 9 has built-in Kotlin, so no `kotlin.android`)
  - `android {}`: `namespace = "io.github.vladyslavchabanenko.imageloader"`. `compileSdk` and `minSdk` come from the catalog (`libs.versions.compileSdk.get().toInt()`). `compileOptions` use Java 17. `testOptions.unitTests` sets `isIncludeAndroidResources = true` and `isReturnDefaultValues = true`
  - `kotlin { explicitApi(); compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`. If `explicitApi()` is not available, use the fallback `freeCompilerArgs.add("-Xexplicit-api=strict")` from research R1
  - dependencies: `implementation` of coroutines-android, lifecycle-runtime-ktx and annotation. `testImplementation` of junit, coroutines-test, robolectric, androidx-test-core and mockwebserver3
  - manifest: only `<uses-permission android:name="android.permission.INTERNET" />`
- [X] T008 [P] Create `app/build.gradle.kts` and a minimal app shell:
  - plugins: `android.application`, `kotlin.serialization`, `ktlint`
  - `android {}`: `namespace` and `applicationId` are both `"io.github.vladyslavchabanenko.imageloader.sample"`. `compileSdk`, `minSdk` and `targetSdk` come from the catalog. Set `versionCode = 1` and `versionName = "1.0"`. `buildFeatures` sets `viewBinding = true` and `buildConfig = true`. `compileOptions` use Java 17. Add `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`
  - dependencies: `implementation(project(":imageloader"))`, core-ktx, appcompat, activity-ktx, material, recyclerview, lifecycle-viewmodel-ktx, lifecycle-runtime-ktx, coroutines-android, serialization-json. `testImplementation` of junit and coroutines-test
  - `app/src/main/AndroidManifest.xml`: the INTERNET permission and `<application android:allowBackup="false" android:label="@string/app_name" android:supportsRtl="true" android:theme="@style/Theme.ImageLoaderSample">`, with no components yet
  - `app/src/main/res/values/strings.xml` defines `app_name` = "Image Loader Sample". `app/src/main/res/values/themes.xml` defines `Theme.ImageLoaderSample` with parent `Theme.Material3.DayNight.NoActionBar`
- [X] T009 Generate the Gradle 9.8.2 wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`). There is no `gradle` on PATH, so run a cached distribution from the Gradle root, for example `~/.gradle/wrapper/dists/gradle-9.8.1-bin/*/gradle-9.8.1/bin/gradle wrapper --gradle-version 9.8.2 --distribution-type bin`. Then, unless `ANDROID_HOME` is set, create the git-ignored `local.properties` with `sdk.dir=` set to the absolute Android SDK path (`~/Library/Android/sdk` expanded on macOS)
- [X] T010 Verify the scaffold (research R1, R15) using a temporary `imageloader/src/main/java/io/github/vladyslavchabanenko/imageloader/Probe.kt`:
  - `./gradlew --version` reports Gradle 9.8.2, and `./gradlew buildEnvironment` shows kotlin-gradle-plugin 2.4.21
  - Add `Probe.kt` with a top-level `fun probe() = 1` that has no visibility modifier and is badly indented. `./gradlew :imageloader:compileDebugKotlin` must fail because of explicit API mode (if it does not, switch to the `-Xexplicit-api=strict` fallback). `./gradlew ktlintCheck` must report the formatting violation (if ktlint inspects no files under AGP built-in Kotlin, point the plugin at the module source sets)
  - Delete `Probe.kt`, then confirm that `./gradlew build` passes

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: library building blocks that every story uses (download, size, decode), test fakes
and the app's debug StrictMode.

**⚠️ CRITICAL**: no user story work can begin until this phase is complete.

- [X] T011 [P] Write `imageloader/src/test/kotlin/io/github/vladyslavchabanenko/imageloader/HttpDownloaderTest.kt` (plain JUnit and `runTest`). Start a `mockwebserver3.MockWebServer` in `@Before` and close it in `@After`. Build `HttpDownloader(Dispatchers.IO, connectTimeoutMillis = 10_000, readTimeoutMillis = 10_000)` for every case except the stall case, so those timeouts are never reached. Cases:
  - 200 with `Content-Length` returns the exact bytes. 200 with a chunked body (no `Content-Length`) also returns the exact bytes
  - 403 and 500 throw `IOException` (FR-011)
  - stall: with `readTimeoutMillis = 200`, a body that sends one chunk and then pauses 2 s (`MockResponse.Builder().throttleBody(…, 2, TimeUnit.SECONDS)`) throws `IOException` (FR-013). The 10× margin keeps it deterministic
  - cancelling the calling job while a long body throttled to one small chunk every 50 ms is being read ends the job as cancelled well before the body would finish
- [X] T012 Implement `imageloader/src/main/java/io/github/vladyslavchabanenko/imageloader/HttpDownloader.kt` per research R7, and make `HttpDownloaderTest` pass:
  - `internal fun interface Downloader { suspend fun download(url: String): ByteArray }`
  - `internal class HttpDownloader(ioDispatcher: CoroutineDispatcher, connectTimeoutMillis: Int = 30_000, readTimeoutMillis: Int = 30_000) : Downloader`
  - It runs in `withContext(ioDispatcher)`, uses `HttpURLConnection` with both timeouts, and throws `IOException("HTTP <code>")` outside 2xx
  - It reads in 8 KiB chunks with `ensureActive()` between chunks. When `contentLengthLong >= 0` it fills an exact-size `ByteArray`, otherwise a growing buffer
  - Streams are closed with `use {}`, and `disconnect()` is called in `finally`
  - Each network download logs `Log.d("ImageLoader", "download $url")` (G11, quickstart row 2)
- [X] T013 [P] Implement `imageloader/src/main/java/io/github/vladyslavchabanenko/imageloader/ViewSize.kt` per research R5:
  - `internal data class PixelSize(val width: Int, val height: Int)`
  - `internal fun View.laidOutSizeOrNull(): PixelSize?`: when `isLaidOut && !isLayoutRequested`, returns width and height minus padding. A dimension ≤ 0 is replaced by `resources.displayMetrics.widthPixels` or `heightPixels`. Otherwise returns `null`
  - `internal suspend fun View.awaitSize(): PixelSize`: returns `laidOutSizeOrNull()`, or suspends in `suspendCancellableCoroutine` with a `View.OnLayoutChangeListener`. The listener removes itself and resumes with a size computed from the new width and height. Do not re-check `isLaidOut` there, because the platform sets it only after the listeners run. `invokeOnCancellation` removes the listener
- [X] T014 [P] Create the test helper `imageloader/src/test/kotlin/io/github/vladyslavchabanenko/imageloader/fakes/TestImages.kt` with `fun jpegBytes(width: Int, height: Int, color: Int = Color.RED): ByteArray`. It fills a `Bitmap` with `color` and compresses it to JPEG. It works only under Robolectric `@GraphicsMode(GraphicsMode.Mode.NATIVE)`
- [X] T015 Write `imageloader/src/test/kotlin/io/github/vladyslavchabanenko/imageloader/BitmapDecoderTest.kt` with `@RunWith(RobolectricTestRunner::class)` and `@GraphicsMode(GraphicsMode.Mode.NATIVE)`. This is the first Robolectric test. Add `imageloader/src/test/resources/robolectric.properties` with `sdk=34`, so Robolectric runs on JDK 17 as well as newer JDKs (runtimes for SDK 36+ need JDK 21). Prove it once by running `./gradlew :imageloader:testDebugUnitTest` with `JAVA_HOME` set to the JDK 17 install. Cases:
  - `calculateInSampleSize`: 4000×3000 into 1080×600 gives 2, a source smaller than the target gives 1, and an exact power-of-two boundary gives the larger sample size
  - `coverSize`: never upscales, keeps the aspect ratio, and covers both target dimensions
  - decoding `jpegBytes(1920, 1080)` for a 540×300 target gives a bitmap that covers 540×300 and is no larger than `coverSize`
  - non-image bytes and empty bytes throw `InvalidImageException`
- [X] T016 Implement `imageloader/src/main/java/io/github/vladyslavchabanenko/imageloader/BitmapDecoder.kt` per research R6, and make `BitmapDecoderTest` pass:
  - `internal class InvalidImageException(message: String) : Exception(message)`
  - `internal object BitmapDecoder` with `fun decode(bytes: ByteArray, targetWidth: Int, targetHeight: Int): Bitmap`. It does a bounds pass (non-positive dimensions throw `InvalidImageException`), then an ARGB_8888 decode with `calculateInSampleSize` (a `null` result throws `InvalidImageException`). If the result is still larger than `coverSize`, it applies `Bitmap.createScaledBitmap` down to `coverSize` and recycles the intermediate bitmap
  - pure functions `calculateInSampleSize(srcWidth, srcHeight, reqWidth, reqHeight): Int` and `coverSize(srcWidth, srcHeight, reqWidth, reqHeight): PixelSize`
- [X] T017 [P] Create `imageloader/src/test/kotlin/io/github/vladyslavchabanenko/imageloader/fakes/FakeDownloader.kt`, which implements `Downloader`:
  - scripted responses per URL: `succeed(url, bytes)`, `fail(url, exception)`, and `gate(url): CompletableDeferred<ByteArray>`, which keeps the call suspended until the test completes the deferred
  - `callCount(url)`, plus `cancelledCount(url)`, which counts calls that ended with `CancellationException` (rethrown)
- [X] T018 [P] Create `app/src/main/java/io/github/vladyslavchabanenko/imageloader/sample/SampleApplication.kt`. `onCreate` calls `StrictMode.setThreadPolicy(ThreadPolicy.Builder().detectAll().penaltyLog().build())` when `BuildConfig.DEBUG` (Constitution V). Register it with `android:name=".SampleApplication"` in `app/src/main/AndroidManifest.xml`

**Checkpoint**: `./gradlew build` passes, and the downloader and decoder are tested.

---

## Phase 3: User Story 1 - Browse the image list (Priority: P1) 🎯 MVP

**Goal**: the app fetches the published list and shows one row per entry, with its id and its
image (placeholder while loading, error image on failure), a loading state, an error state with
Retry, and survival of rotation. The library loads images with no caching yet.

**Independent Test**: launch the app online. All 50 rows appear in order with their ids.
Placeholders are replaced by images, and ids 4, 13, 24, 37 and 49 show the broken-image icon.
Fast scrolling never shows a wrong image. Offline start shows an error, and Retry recovers.
Rotation keeps the list (quickstart rows 1, 3, 4, 5, 6).

### Tests for User Story 1 ⚠️

- [X] T019 [P] [US1] Create `imageloader/src/test/kotlin/io/github/vladyslavchabanenko/imageloader/fakes/FakeLifecycleOwner.kt`:
  - `class FakeLifecycleOwner(initialState: Lifecycle.State = Lifecycle.State.RESUMED) : LifecycleOwner`, backed by a `LifecycleRegistry` (created on the Robolectric main thread), with `fun destroy()` moving it to `DESTROYED`
  - `class LifecycleContext(base: Context, owner: LifecycleOwner) : ContextWrapper(base), LifecycleOwner by owner`, for the context fallback test
- [X] T020 [US1] Write `imageloader/src/test/kotlin/io/github/vladyslavchabanenko/imageloader/ImageLoaderTest.kt` (FR-001, 002, 007, 008, 010, 011):
  - Setup: `@RunWith(RobolectricTestRunner::class)`, `@GraphicsMode(NATIVE)` and `runTest`. A private `createLoader()` helper builds `ImageLoader` through its internal constructor with `FakeDownloader` and `StandardTestDispatcher(testScheduler)` as the main and default dispatchers. Later stories change only this helper. The target is an `ImageView(ApplicationProvider.getApplicationContext())` with `layout(0, 0, 200, 100)` and `setViewTreeLifecycleOwner(FakeLifecycleOwner())`. Use `ColorDrawable`s as placeholder and error drawables (assert identity) and `android.R.drawable.*` ids for the resource overload. Advance with `advanceUntilIdle()`, and also idle the Robolectric main looper if needed. Add a private `ImageView.relayout()` helper: `measure(makeMeasureSpec(200, EXACTLY), makeMeasureSpec(100, EXACTLY))`, then `layout(0, 0, 200, 100)`. Setting a drawable of a different intrinsic size calls `requestLayout()`, so after `load()` shows the placeholder, `laidOutSizeOrNull()` returns null and `awaitSize()` waits. An unattached view gets no layout pass, and `layout()` with unchanged bounds does not notify listeners without `measure()`. Call `relayout()` after each `load()` that should proceed and before any synchronous memory-hit assertion
  - The placeholder is set synchronously by both overloads, before any dispatcher advances
  - On success, the view shows a `BitmapDrawable` that covers 200×100 and is not larger than the cover size of the source
  - On download failure and on non-image bytes, the error drawable is shown. With `errorResId = 0` or `error = null`, the placeholder stays. Nothing is thrown
  - A second `load` on the same view while the first download is gated cancels the first download (`cancelledCount == 1`), and the first image (distinguish images by pixel color) is never shown
  - `FakeLifecycleOwner.destroy()` during a gated download cancels the download and leaves the placeholder
  - A load into a view whose owner is already destroyed never calls the downloader
  - A view without a view-tree owner, created with a `LifecycleContext`, is cancelled when that owner is destroyed (R4 fallback)
  - Fragment case: a view with no view-tree owner, created with a `LifecycleContext` (owner A), gets view-tree owner B (`setViewTreeLifecycleOwner(B)`) before it is laid out. Destroying B during a gated download cancels the download (`cancelledCount == 1`) and leaves the placeholder, while A stays `RESUMED`
  - A view that is not laid out yet does not download until `relayout()` is called, and then decodes at that size
- [X] T021 [P] [US1] Write `app/src/test/java/io/github/vladyslavchabanenko/imageloader/sample/data/ImageListParserTest.kt`, with the fixture `app/src/test/resources/image_list.json`. Download the fixture once (`curl -fsSL https://zipoapps-storage-test.nyc3.digitaloceanspaces.com/image_list.json -o app/src/test/resources/image_list.json`). Tests never use the network. Cases:
  - the fixture parses to 50 entries with ids 0–49 in order and 45 distinct URLs, so duplicates are kept
  - `[]` gives an empty list, and unknown keys are ignored
  - these elements are skipped while their valid neighbors keep their order: `1` (not an object), missing `id`, `"id": null`, `"id": "abc"`, `"id": 1.5`, missing `imageUrl`, `"imageUrl": null` and `"imageUrl": "  "`
  - a root object `{}` and malformed text `[{` throw
- [X] T022 [P] [US1] Write `app/src/test/java/io/github/vladyslavchabanenko/imageloader/sample/data/ImageListRepositoryTest.kt` with a fake `ImageListDataSource` and `StandardTestDispatcher(testScheduler)` as the IO dispatcher. A two-entry JSON maps to two `ImageEntry`s. An `IOException` from the data source propagates. Malformed JSON throws
- [X] T023 [P] [US1] Write `app/src/test/java/io/github/vladyslavchabanenko/imageloader/sample/ui/MainDispatcherRule.kt`, a `TestWatcher` with `val testDispatcher = StandardTestDispatcher()` that calls `Dispatchers.setMain` and `resetMain`. Then write `app/src/test/java/io/github/vladyslavchabanenko/imageloader/sample/ui/ImageListViewModelTest.kt` with a call-counting fake data source (FR-020, FR-022):
  - the state starts as `Loading`, then becomes `Content` with items in source order
  - a failing fetch gives `Error`, and after fixing the fake, `onRetry()` goes through `Loading` to `Content`
  - a second collector of `uiState` gets the current `Content`, and the data source was called once

### Implementation for User Story 1: library

- [X] T024 [P] [US1] Create `imageloader/src/main/res/values/ids.xml` with `<item name="imageloader_request" type="id" />`, the view tag that holds the active request (FR-007)
- [X] T025 [US1] Implement `imageloader/src/main/java/io/github/vladyslavchabanenko/imageloader/ImageLoader.kt` for US1 (contract `contracts/image-loader-api.md`, research R3/R4). `invalidateCache()` is added in T049:
  - `public class ImageLoader internal constructor(downloader: Downloader, mainDispatcher: CoroutineDispatcher, defaultDispatcher: CoroutineDispatcher)` with `private val libraryScope = CoroutineScope(SupervisorJob() + mainDispatcher)`
  - Both public `load` overloads have exactly the contract's signatures, KDoc and annotations (`@MainThread`, `@JvmOverloads`, `@DrawableRes`). The resource overload resolves drawables with `target.context.getDrawable(id)` (`errorResId == 0` means no error image) and delegates to the `Drawable` overload
  - Request start: cancel `target.getTag(R.id.imageloader_request) as? Job`, then `target.setImageDrawable(placeholder)`. Pick the scope in this order: `target.findViewTreeLifecycleOwner()?.lifecycleScope`, then the first `LifecycleOwner` found by unwrapping `ContextWrapper.baseContext` from `target.context`, then `libraryScope`. Then `val job = scope.launch(mainDispatcher, start = CoroutineStart.LAZY) { … }`, `target.setTag(R.id.imageloader_request, job)` and `job.start()`
  - Job body: `target.awaitSize()`, then `downloader.download(url)`, then `withContext(defaultDispatcher) { BitmapDecoder.decode(...) }`. Call `target.setImageBitmap(bitmap)` only if the tag is still `coroutineContext[Job]`. Rethrow `CancellationException`. For any other `Exception`, show `error` if one was given (otherwise keep the placeholder) and `Log.w("ImageLoader", "Failed to load $url", e)`
  - Remember the owner whose scope was chosen. Right after `awaitSize()`, if `target.findViewTreeLifecycleOwner()` is non-null and is a different owner, register `treeOwner.lifecycleScope.coroutineContext.job.invokeOnCompletion { job.cancel() }` and call `dispose()` on the handle in `finally` (research R4)
  - `public companion object { @JvmStatic public fun get(context: Context): ImageLoader }` with KDoc: a lazily created, thread-safe singleton (double-checked locking on a `@Volatile` field) built from `context.applicationContext` with `HttpDownloader(Dispatchers.IO)`, `Dispatchers.Main` and `Dispatchers.Default`. This is the only place in the library that names `Dispatchers.*`
- [X] T026 [US1] Run `./gradlew :imageloader:testDebugUnitTest`. `HttpDownloaderTest`, `BitmapDecoderTest` and `ImageLoaderTest` must pass, and the report under `imageloader/build/reports/tests/` must list them, which confirms that `src/test/kotlin` is compiled

### Implementation for User Story 1: example app

- [X] T027 [P] [US1] Create `app/src/main/java/io/github/vladyslavchabanenko/imageloader/sample/data/ImageEntry.kt`: `data class ImageEntry(val id: Long, val imageUrl: String)`
- [X] T028 [US1] Implement `app/src/main/java/io/github/vladyslavchabanenko/imageloader/sample/data/ImageListParser.kt` per research R14 and `contracts/image-list-json.md`, and make `ImageListParserTest` pass:
  - `object ImageListParser` with `fun parse(json: String): List<ImageEntry>`, using a private `Json { ignoreUnknownKeys = true }` and a private `@Serializable data class ImageEntryDto(val id: Long? = null, val imageUrl: String? = null)`
  - The root comes from `Json.parseToJsonElement`. If the root is not a `JsonArray`, throw `IllegalArgumentException`
  - Each element is decoded with `decodeFromJsonElement`. An element that throws `SerializationException` or `IllegalArgumentException`, or that has a null id or a null or blank URL, is skipped
- [X] T029 [P] [US1] Implement `app/src/main/java/io/github/vladyslavchabanenko/imageloader/sample/data/ImageListDataSource.kt`:
  - `fun interface ImageListDataSource { fun fetchImageListJson(): String }`, a blocking call that the repository runs on IO
  - `class HttpImageListDataSource(private val url: String = IMAGE_LIST_URL)`, a GET over `HttpURLConnection` with 30 s connect and read timeouts. Non-2xx throws `IOException`. It uses `use {}` and calls `disconnect()` in `finally`. A companion `const val IMAGE_LIST_URL` holds the spec URL (FR-018)
- [X] T030 [US1] Implement `app/src/main/java/io/github/vladyslavchabanenko/imageloader/sample/data/ImageListRepository.kt`: `class ImageListRepository(dataSource: ImageListDataSource, ioDispatcher: CoroutineDispatcher)` with `suspend fun loadImages(): List<ImageEntry> = withContext(ioDispatcher) { ImageListParser.parse(dataSource.fetchImageListJson()) }`. Make `ImageListRepositoryTest` pass
- [X] T031 [P] [US1] Add the list-screen resources under `app/src/main/res/`:
  - `app/src/main/res/values/strings.xml` adds `image_list_error` ("Couldn't load the image list. Check your connection and try again."), `retry` ("Retry"), `image_id_label` ("ID: %1$d") and `image_content_description` ("Image %1$d")
  - `app/src/main/res/values/dimens.xml` defines `image_row_height` (200dp), `list_item_spacing` (8dp) and `content_padding` (16dp)
  - `app/src/main/res/drawable/ic_image_placeholder.xml` and `app/src/main/res/drawable/ic_broken_image.xml` are vector drawables based on the Material "image" and "broken_image" icons. Each vector is sized to the row's aspect ratio (`360dp` × `200dp`, same viewport) with the 24-unit icon path centered by a `<group android:translateX/translateY>`, so `centerCrop` keeps the icon small. Tint with `android:tint="?attr/colorOnSurfaceVariant"`
- [X] T032 [US1] Create the layouts in `app/src/main/res/layout/`:
  - `app/src/main/res/layout/item_image.xml`: a vertical `LinearLayout` with `layout_marginBottom="@dimen/list_item_spacing"`. It holds an `ImageView` `@+id/image` (`match_parent` × `@dimen/image_row_height`, `scaleType="centerCrop"`, `android:background="?attr/colorSurfaceContainerHighest"`, `tools:ignore="ContentDescription"` with an XML comment saying the adapter sets it per row) and a `TextView` `@+id/id_label` (`padding="@dimen/content_padding"`, `textAppearance="?attr/textAppearanceBodyLarge"`)
  - `app/src/main/res/layout/activity_image_list.xml`: a `CoordinatorLayout` `@+id/root` containing an `AppBarLayout` with `android:fitsSystemWindows="true"` that holds a `MaterialToolbar` `@+id/toolbar` (`app:title="@string/app_name"`), and a `FrameLayout` with `app:layout_behavior="@string/appbar_scrolling_view_behavior"`. The `FrameLayout` holds a `RecyclerView` `@+id/list` (`app:layoutManager="androidx.recyclerview.widget.LinearLayoutManager"`, `clipToPadding="false"`, `visibility="gone"`), a centered indeterminate `CircularProgressIndicator` `@+id/progress`, and a centered vertical `LinearLayout` `@+id/error_group` (`visibility="gone"`, padded) containing a `TextView` `@+id/error_message` (`@string/image_list_error`, centered text) and a `MaterialButton` `@+id/retry_button` (`@string/retry`)
- [X] T033 [P] [US1] Create `app/src/main/java/io/github/vladyslavchabanenko/imageloader/sample/ui/ImageListUiState.kt`: `sealed interface ImageListUiState` with `data object Loading`, `data class Content(val items: List<ImageListItem>)` and `data object Error`, plus `data class ImageListItem(val id: Long, val imageUrl: String)` (data-model.md)
- [X] T034 [US1] Implement `app/src/main/java/io/github/vladyslavchabanenko/imageloader/sample/ui/ImageListViewModel.kt` (research R13), and make `ImageListViewModelTest` pass:
  - `class ImageListViewModel(private val repository: ImageListRepository) : ViewModel()` with a private `MutableStateFlow<ImageListUiState>(Loading)` exposed as `val uiState: StateFlow<ImageListUiState>`
  - `init` loads, and `fun onRetry()` loads again. Loading sets `Loading`, then launches in `viewModelScope`. It maps entries to `ImageListItem`s in a `Content` state. It rethrows `CancellationException` and turns any other `Exception` into `Error`
  - `companion object { val Factory: ViewModelProvider.Factory = viewModelFactory { initializer { ImageListViewModel(ImageListRepository(HttpImageListDataSource(), Dispatchers.IO)) } } }`
- [X] T035 [US1] Implement `app/src/main/java/io/github/vladyslavchabanenko/imageloader/sample/ui/ImageListAdapter.kt`: `class ImageListAdapter(private val imageLoader: ImageLoader) : ListAdapter<ImageListItem, ImageListAdapter.ViewHolder>(DiffCallback)`. The `ViewHolder` uses `ItemImageBinding`. `bind(item)` sets `id_label` from `R.string.image_id_label`, sets the image `contentDescription` from `R.string.image_content_description`, and calls `imageLoader.load(item.imageUrl, binding.image, R.drawable.ic_image_placeholder, R.drawable.ic_broken_image)` (FR-019). `DiffCallback` compares items by `id` and contents by equality
- [X] T036 [US1] Implement `app/src/main/java/io/github/vladyslavchabanenko/imageloader/sample/ui/ImageListActivity.kt`, and register it in `app/src/main/AndroidManifest.xml` with `android:exported="true"` and a MAIN/LAUNCHER intent filter:
  - `AppCompatActivity`. Call `enableEdgeToEdge()` before `super.onCreate`, then inflate `ActivityImageListBinding`. Create the view model with `by viewModels { ImageListViewModel.Factory }`
  - The adapter is `ImageListAdapter(ImageLoader.get(this))` with `stateRestorationPolicy = PREVENT_WHEN_EMPTY`. `ViewCompat.setOnApplyWindowInsetsListener(binding.list)` applies the left, right and bottom `systemBars()` insets as padding
  - The Retry button calls `viewModel.onRetry()`. In `lifecycleScope.launch { repeatOnLifecycle(STARTED) { … } }`, collect `uiState` and render it: `progress` is visible for `Loading`, `list` for `Content` (with `submitList(items)`), and `error_group` for `Error`
- [X] T037 [US1] Validate US1: `./gradlew build` passes. Then `./gradlew :app:installDebug` on an emulator and walk rows 1, 3, 4, 5 and 6 of `specs/001-image-loader-library/quickstart.md`. Without caching, scrolling back downloads again. US2 fixes that

**Checkpoint**: US1 works end to end and can be demoed as the MVP.

---

## Phase 4: User Story 2 - Reuse cached images for 4 hours (Priority: P2)

**Goal**: downloaded images are reused from memory and disk for 4 hours from download, including
after restart. Concurrent requests for one URL share one download. Failures are not cached.

**Independent Test**: after one unhurried full scroll, logcat shows 45 `download` lines. Rows seen before
still show in airplane mode. Reopening within 4 hours downloads nothing. Moving the clock 4 hours
forward (or back before the download) downloads everything again (quickstart rows 2, 7, 8, 9).

### Tests for User Story 2 ⚠️

- [X] T038 [P] [US2] Create `imageloader/src/main/java/io/github/vladyslavchabanenko/imageloader/Clock.kt` (`internal fun interface Clock { fun nowMillis(): Long }`) and `imageloader/src/test/kotlin/io/github/vladyslavchabanenko/imageloader/fakes/FakeClock.kt` (`class FakeClock(var now: Long = 1_000_000L) : Clock`, plus `fun advanceBy(millis: Long)`)
- [X] T039 [P] [US2] Write `imageloader/src/test/kotlin/io/github/vladyslavchabanenko/imageloader/DiskCacheTest.kt` (plain JUnit, `TemporaryFolder`, `FakeClock`, `maxBytes` of a few hundred bytes for the LRU cases) covering FR-003–006, 014, 015:
  - `DiskCache.isFresh`: fresh at `downloadedAt`, fresh 1 ms before +4 h, expired exactly at +4 h, expired when `now < downloadedAt`
  - `get` returns the bytes and `downloadedAtMillis` while fresh. At +4 h, and when the clock is set back before the download time, it returns `null` and the file is deleted
  - a new `DiskCache` instance on the same directory returns the entry (persists across restarts)
  - a file with bad magic, a file shorter than the header, and a header with an empty payload each return `null`, and the file is deleted
  - a leftover `.tmp` file is deleted when the index is built and is never returned
  - LRU: puts beyond `maxBytes` evict the least recently used entries, and a `get` refreshes recency. An entry larger than `maxBytes` is not stored
  - `remove(url)` deletes one entry, and `clear()` deletes every file
  - with a `Lazy<File>` whose parent is a regular file, so writes fail, `put` does not throw and leaves no entry
- [X] T040 [US2] Implement `imageloader/src/main/java/io/github/vladyslavchabanenko/imageloader/DiskCache.kt` per research R8 and deviations 1–3, and make `DiskCacheTest` pass:
  - `internal class DiskEntry(val bytes: ByteArray, val downloadedAtMillis: Long)` and `internal class DiskCache(directory: Lazy<File>, clock: Clock, maxBytes: Long = 100L * 1024 * 1024)`. It is not thread-safe, and the KDoc says every call must run on the serialized disk dispatcher
  - File name: SHA-256 of the URL as lowercase hex plus `.img`. The temp file is `<name>.tmp`. Format: magic `ILC1`, big-endian `Long` `downloadedAtMillis`, then the payload
  - `get(url): DiskEntry?` validates with `isFresh(downloadedAt, clock.nowMillis())`. Corrupt, expired or unreadable entries are deleted and return `null`. A hit refreshes the index order and calls `setLastModified(now)` as a best effort
  - `put(url, bytes, downloadedAtMillis)`: skip if larger than `maxBytes`. Otherwise `mkdirs()`, write the `.tmp` file, then `renameTo` the `.img` name. On `IOException`, delete the temp file and `Log.w`. Then update the index and evict the eldest entries while the total exceeds `maxBytes`
  - `remove(url)` and `clear()` delete the files and the index entries, and catch and log `IOException`, so they never throw
  - The index is a `LinkedHashMap<String, Long>(16, 0.75f, true)`, built lazily on first use by listing the directory, deleting `*.tmp` files and inserting `.img` files by ascending `lastModified()`
  - `companion object { const val TTL_MILLIS = 14_400_000L; fun isFresh(downloadedAtMillis: Long, nowMillis: Long): Boolean = downloadedAtMillis <= nowMillis && nowMillis < downloadedAtMillis + TTL_MILLIS }`
- [X] T041 [P] [US2] Write `imageloader/src/test/kotlin/io/github/vladyslavchabanenko/imageloader/ImageFetcherTest.kt` (plain JUnit and `runTest`, with a real `DiskCache` on `TemporaryFolder`, `FakeClock` and `FakeDownloader`). Use `StandardTestDispatcher(testScheduler)` as the disk dispatcher and `backgroundScope` as the fetcher scope. Cases (FR-005, 009, 012; SC-005):
  - two concurrent `fetch(url)` calls while the download is gated make one download and both get the same bytes with source `NETWORK`
  - a fresh disk entry gives source `DISK`, its `downloadedAtMillis`, and 0 downloads. A miss downloads once and writes the bytes with `downloadedAtMillis = clock.now`
  - a failed download throws to every waiter and writes nothing, and the next `fetch` downloads again
  - cancelling the only waiter cancels the download (`cancelledCount == 1`). Cancelling one of two waiters does not, and the other completes with one download
  - when the disk write fails (unwritable directory), `fetch` still returns the bytes
  - after a shared fetch fails, a new `fetch` downloads again even while another waiter of the failed fetch has not resumed (launch that waiter on a separate `StandardTestDispatcher` so it stays paused)
- [X] T042 [US2] Implement `imageloader/src/main/java/io/github/vladyslavchabanenko/imageloader/ImageFetcher.kt` per research R10, and make `ImageFetcherTest` pass:
  - `internal enum class Source { DISK, NETWORK }` and `internal class FetchResult(val bytes: ByteArray, val source: Source, val downloadedAtMillis: Long)`
  - `internal class ImageFetcher(downloader: Downloader, diskCache: DiskCache, clock: Clock, diskDispatcher: CoroutineDispatcher, scope: CoroutineScope)`
  - `suspend fun fetch(url: String): FetchResult`: inside `synchronized(inFlight)`, take or create a `SharedFetch(deferred = scope.async { diskOrNetwork(url) })` in `inFlight: HashMap<String, SharedFetch>` and increment `waiters`. Then `await()`. In `finally`, under the same lock, decrement `waiters`. At 0, remove the entry (only if it is still this one) and `deferred.cancel()`
  - Also remove the entry when its deferred completes (`deferred.invokeOnCompletion`, under the same lock, only if it is still this one), so a later `fetch` never joins a finished or failed fetch (research R10)
  - `diskOrNetwork`: run `withContext(diskDispatcher) { diskCache.get(url) }` and return `DISK` on a hit. Otherwise call `downloader.download(url)`, take `now = clock.nowMillis()`, run `withContext(diskDispatcher) { diskCache.put(url, bytes, now) }`, and return `NETWORK`
- [X] T043 [US2] Extend `imageloader/src/test/kotlin/io/github/vladyslavchabanenko/imageloader/ImageLoaderTest.kt` for US2. Update `createLoader()` to pass a `DiskCache` (`lazyOf(temporaryFolder.root)`), a `FakeClock`, a memory size of 32 MB and the test dispatcher as IO. Cases (FR-003–005, 009, 012, 014; SC-003, 005):
  - after one load completes, a second load of the same URL into the same view after `relayout()` shows the bitmap synchronously (the placeholder is never set) with 1 download in total
  - after `clock.advanceBy(4 h)`, the next load shows the placeholder and downloads again
  - a new `ImageLoader` on the same directory and clock (a "restart") loads with 0 new downloads
  - two views loading the same URL concurrently make 1 download and both show the image. Views of different sizes also share the single download
  - non-image bytes show the error image, remove the disk entry, and the next load downloads again
  - a disk entry with a valid header but a non-image payload (seeded with the test's `DiskCache.put`) is removed, and the same request downloads and shows the image (1 download, no error image)
- [X] T044 [US2] Update `imageloader/src/main/java/io/github/vladyslavchabanenko/imageloader/ImageLoader.kt` for caching (research R4 step 2, R6, R8, R9, deviations 1–2), and make every library test pass:
  - The internal constructor becomes `(downloader: Downloader, diskCache: DiskCache, clock: Clock, memoryCacheMaxBytes: Int, mainDispatcher, ioDispatcher, defaultDispatcher)`, with `private val diskDispatcher = ioDispatcher.limitedParallelism(1)` and `private val fetcher = ImageFetcher(downloader, diskCache, clock, diskDispatcher, libraryScope)`
  - Memory cache: `internal data class MemoryKey(val url: String, val width: Int, val height: Int)`, `internal class MemoryEntry(val bitmap: Bitmap, val downloadedAtMillis: Long)`, and `LruCache<MemoryKey, MemoryEntry>(memoryCacheMaxBytes)` with `sizeOf` = `bitmap.allocationByteCount`. A lookup returns the entry only if `DiskCache.isFresh(entry.downloadedAtMillis, clock.nowMillis())`, otherwise it removes it
  - In `load()`, after cancelling the previous job: if `target.laidOutSizeOrNull()` gives a size whose `MemoryKey` hits, call `setImageBitmap`, clear the tag and return without showing the placeholder (G1)
  - Job body: `awaitSize()`, then a memory lookup, otherwise `fetcher.fetch(url)`, decode, `memoryCache.put(key, MemoryEntry(bitmap, result.downloadedAtMillis))`, and apply. On `InvalidImageException`, keep `libraryScope.launch(diskDispatcher) { diskCache.remove(url) }`. Then, if `result.source == DISK` and the request has not retried yet, fetch again: the serialized disk dispatcher runs the remove first, so the read misses and the image downloads. Otherwise show the error fallback. (T050's generation loop also handles this one retry.)
  - `get()` wires `DiskCache(lazy { File(appContext.cacheDir, "image_loader") }, Clock { System.currentTimeMillis() })`, `(Runtime.getRuntime().maxMemory() / 8).toInt()` and `Dispatchers.IO`
- [X] T045 [US2] Validate US2: `./gradlew build` passes. Then install the app and walk rows 2, 7, 8 and 9 of `specs/001-image-loader-library/quickstart.md`, counting downloads with `adb logcat -s ImageLoader`

**Checkpoint**: US1 and US2 work. Scrolling back is instant, and restarts reuse the disk cache.

---

## Phase 5: User Story 3 - Invalidate the cache manually (Priority: P3)

**Goal**: an always-visible "Invalidate cache" toolbar action discards every cached image, shows
a confirmation, and makes the visible rows reload from fresh downloads without fetching the list
again.

**Independent Test**: load the list, tap **Invalidate cache**. A Snackbar says "Image cache
cleared", the visible rows show placeholders, then reload with new `download` lines, and the list
is not fetched again (quickstart row 10).

### Tests for User Story 3 ⚠️

- [ ] T046 [P] [US3] Extend `imageloader/src/test/kotlin/io/github/vladyslavchabanenko/imageloader/ImageFetcherTest.kt` for generations (research R10, R11). Change the existing calls to `fetch(url, 0)`. Add: `fetch(url, 1)` while `fetch(url, 0)` is gated starts a second download, and two concurrent `fetch(url, 1)` calls share one
- [ ] T047 [P] [US3] Extend `imageloader/src/test/kotlin/io/github/vladyslavchabanenko/imageloader/ImageLoaderTest.kt` for invalidation (FR-006, G8, SC-004). Seed the disk directly through the test's `DiskCache` where needed:
  - after an image is in memory and on disk, `invalidateCache()` followed by a load into the same view shows the placeholder synchronously, and after advancing makes exactly one new download (the disk was cleared before the read)
  - stale disk result: give the default (decode) dispatcher its own `TestCoroutineScheduler`, so a request can be paused after a `DISK` fetch and before display. Call `invalidateCache()` there, then advance both schedulers. The disk-sourced bitmap is never shown, a new download happens, and its image is shown
  - a gated network download in flight during `invalidateCache()` is still shown when it completes, but it is not put in memory (the next load into a same-size view shows the placeholder first)
- [ ] T048 [P] [US3] Extend `app/src/test/java/io/github/vladyslavchabanenko/imageloader/sample/ui/ImageListViewModelTest.kt` (FR-021), passing a counting invalidator lambda:
  - in `Content`, `onInvalidateCacheClicked()` calls the invalidator once, emits `Content` whose items all have `cacheGeneration` 1 (same ids and order), and sends exactly one `ImageListEvent.CacheInvalidated` (collect `events` in `backgroundScope`). The data source is not called again
  - in `Error`, it still calls the invalidator and sends the event, and the state stays `Error`. A later `onRetry()` maps the items with the current generation

### Implementation for User Story 3

- [ ] T049 [US3] Update `imageloader/src/main/java/io/github/vladyslavchabanenko/imageloader/ImageFetcher.kt`: `private data class FetchKey(val url: String, val generation: Int)` becomes the in-flight map key, and the method becomes `fetch(url: String, generation: Int)`. Make `ImageFetcherTest` pass
- [ ] T050 [US3] Update `imageloader/src/main/java/io/github/vladyslavchabanenko/imageloader/ImageLoader.kt` per research R11 and contract G8, and make `ImageLoaderTest` pass:
  - `private val generation = AtomicInteger(0)`. `load()` captures `generation.get()` before launching
  - Job body: loop `fetch(url, gen)` and decode. If `result.source == DISK && gen != generation.get()`, set `gen = generation.get()` and fetch again. Otherwise, put the bitmap in memory only if `gen == generation.get()`, then apply
  - `@AnyThread public fun invalidateCache()` with the contract's KDoc: `generation.incrementAndGet()`, `memoryCache.evictAll()`, then `libraryScope.launch(diskDispatcher) { diskCache.clear() }`. It never blocks on I/O
- [ ] T051 [P] [US3] Add the strings `invalidate_cache` ("Invalidate cache") and `cache_invalidated` ("Image cache cleared") to `app/src/main/res/values/strings.xml`. Create `app/src/main/res/menu/image_list.xml` with one item `@+id/action_invalidate_cache`, `android:title="@string/invalidate_cache"`, `app:showAsAction="always"` and no icon (FR-021, research R13)
- [ ] T052 [P] [US3] Update `app/src/main/java/io/github/vladyslavchabanenko/imageloader/sample/ui/ImageListUiState.kt`: `ImageListItem` gets `val cacheGeneration: Int = 0`, and add `sealed interface ImageListEvent { data object CacheInvalidated : ImageListEvent }`
- [ ] T053 [US3] Update `app/src/main/java/io/github/vladyslavchabanenko/imageloader/sample/ui/ImageListViewModel.kt`, and make `ImageListViewModelTest` pass:
  - The constructor becomes `(repository: ImageListRepository, invalidateImageCache: () -> Unit)`, with a `private var cacheGeneration = 0` that is used when mapping items
  - `private val _events = Channel<ImageListEvent>(Channel.BUFFERED)` is exposed as `val events: Flow<ImageListEvent> = _events.receiveAsFlow()`
  - `fun onInvalidateCacheClicked()`: call `invalidateImageCache()`, increment `cacheGeneration`, re-map `Content` items with `copy(cacheGeneration = cacheGeneration)` (other states unchanged), then `_events.trySend(ImageListEvent.CacheInvalidated)`
  - `Factory` passes `ImageLoader.get(checkNotNull(this[APPLICATION_KEY]))::invalidateCache`
- [ ] T054 [US3] Update `app/src/main/java/io/github/vladyslavchabanenko/imageloader/sample/ui/ImageListAdapter.kt`: `DiffCallback.getChangePayload` returns a private `RELOAD` object when the items differ only in `cacheGeneration`, and `null` otherwise. The non-null payload makes `DefaultItemAnimator` rebind the same holder without a crossfade. The default `onBindViewHolder` calls `load()` again, so the placeholder shows and then a fresh download
- [ ] T055 [US3] Update `app/src/main/java/io/github/vladyslavchabanenko/imageloader/sample/ui/ImageListActivity.kt`: call `binding.toolbar.inflateMenu(R.menu.image_list)` and set `setOnMenuItemClickListener`, so `action_invalidate_cache` calls `viewModel.onInvalidateCacheClicked()`. Inside the existing `repeatOnLifecycle(STARTED)` block, also `launch { viewModel.events.collect { … } }`, showing `Snackbar.make(binding.root, R.string.cache_invalidated, Snackbar.LENGTH_SHORT)` for `CacheInvalidated`
- [ ] T056 [US3] Validate US3: `./gradlew build` passes. Then walk row 10 of `specs/001-image-loader-library/quickstart.md`, and confirm that rotating right after the tap does not show the Snackbar again

**Checkpoint**: US1–US3 work, and the full app is feature-complete.

---

## Phase 6: User Story 4 - Use the library from Kotlin or Java (Priority: P4)

**Goal**: Java callers get the same single-call API with no coroutine code, proven by the build.
The README documents both languages.

**Independent Test**: `./gradlew :imageloader:testDebugUnitTest` compiles and runs the Java
interop test. The app is the Kotlin caller.

### Tests for User Story 4 ⚠️

- [ ] T057 [US4] Write `imageloader/src/test/java/io/github/vladyslavchabanenko/imageloader/ImageLoaderJavaInteropTest.java` (Robolectric, plain Java, FR-017, SC-008):
  - `ImageLoader.get(ApplicationProvider.getApplicationContext())` returns the same instance twice
  - On a `new ImageView(context)` that is never laid out (so no download starts), call all four `load` overloads: `(url, view, android.R.drawable.ic_menu_gallery)`, `(…, ic_menu_gallery, android.R.drawable.ic_delete)`, `(url, view, placeholder)` and `(url, view, placeholder, error)`, plus the null-error form. After each call, assert that the placeholder is showing (`assertSame` for `Drawable`s, `assertNotNull` for resource ids)
  - `invalidateCache()` returns without throwing

### Implementation for User Story 4

- [ ] T058 [US4] Check the public surface of `imageloader/src/main/java/io/github/vladyslavchabanenko/imageloader/ImageLoader.kt` against `contracts/image-loader-api.md`. Run `javap -public` on the compiled class (find it with `find imageloader/build -name ImageLoader.class`). It must show exactly the static `get` and the four `load` and one `invalidateCache` signatures from the contract. Every public declaration must have KDoc, and no `suspend`, `Result`, `Duration` or function type may appear. Fix any mismatch
- [ ] T059 [P] [US4] Write the library half of `README.md`: what the library does; Kotlin and Java usage (from `contracts/image-loader-api.md`); behavioral guarantees G1–G11 grouped by threading, errors, lifecycle and caching (4 h TTL, memory 1/8 heap, disk 100 MB, `invalidateCache()`); the ownership rule from research R4; and the "Explicitly not provided" list

**Checkpoint**: all four stories are complete.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T060 Complete `README.md` with the example-app half: module layout, the MVVM flow (View → `ImageListViewModel` → `ImageListRepository` → `ImageListDataSource`), the build, test and run commands and the logcat check from `specs/001-image-loader-library/quickstart.md`, and the list assumptions (fetched at each start, not stored offline)
- [ ] T061 Sweep `imageloader/src/main/` and `app/src/main/` for constitution violations, and fix any:
  - `grep -rn '!!\|GlobalScope\|runBlocking\|Thread(' imageloader/src/main app/src/main` finds nothing
  - `Dispatchers.` appears only in `ImageLoader.get()` and `ImageListViewModel.Factory`
  - every `catch (e: Exception)` around suspend code is preceded by a rethrow of `CancellationException`
  - no hardcoded user-visible strings, colors or dimensions; no TODOs or commented-out code
- [ ] T062 Run `./gradlew ktlintFormat`, then `./gradlew build`. Lint must report zero errors. Review `app/build/reports/lint-results-debug.html` and `imageloader/build/reports/lint-results-debug.html`, and fix or suppress (with a stated reason) every warning in the new code, for example `MissingApplicationIcon`
- [ ] T063 Walk the full manual acceptance table, rows 1–10, of `specs/001-image-loader-library/quickstart.md` on an API 37 emulator, and on an API 24 emulator if one is available. Fix any failure before calling the feature done

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies. T005 and T006 come before T009 (the wrapper needs a settings file). T010 comes last
- **Foundational (Phase 2)**: depends on Setup and blocks every story
- **US1 (Phase 3)**: depends on Foundational
- **US2 (Phase 4)**: depends on US1, because it extends `ImageLoader.kt` and `ImageLoaderTest.kt`
- **US3 (Phase 5)**: depends on US2 (`ImageFetcher`, `DiskCache.clear`, the memory cache) and on US1 (the list screen)
- **US4 (Phase 6)**: depends on US3, because the interop test calls `invalidateCache()`. T059 (README) can start any time after US1
- **Polish (Phase 7)**: depends on all stories

The stories are sequential because each one extends the same library classes. The spec sets
this order ("priorities set the order of delivery"), and all four stories are required.

### Within Each Story

- Tests, then implementation. A test fails first (it does not compile, or it is red), then passes
- Library: fakes → tests → leaf classes (`DiskCache`, `ImageFetcher`) → `ImageLoader`
- App: model → parser and data source → repository → UI state → ViewModel → adapter → Activity
- Each story ends with a validation task against quickstart.md

### Parallel Opportunities

- Setup: T001–T004 together, then T007 and T008 together
- Foundational: T011, T013, T014, T017 and T018 together. Then T012, T015 → T016
- US1: the library track (T019, T020, T024 → T025 → T026) and the app track (T021–T023, T027, T029, T031, T033 → T028, T030, T032, T034 → T035 → T036) are independent until T037
- US2: T039 and T041 together after T038
- US3: T046, T047, T048, T051 and T052 together. Then T049 → T050 (library) in parallel with T053 → T054 → T055 (app)
- US4: T059 in parallel with T057 and T058

---

## Parallel Example: User Story 1

```bash
# Tests, all in different files:
Task: "T020 Write ImageLoaderTest.kt in imageloader/src/test/kotlin/.../ImageLoaderTest.kt"
Task: "T021 Write ImageListParserTest.kt in app/src/test/java/.../data/ImageListParserTest.kt"
Task: "T022 Write ImageListRepositoryTest.kt in app/src/test/java/.../data/ImageListRepositoryTest.kt"
Task: "T023 Write ImageListViewModelTest.kt in app/src/test/java/.../ui/ImageListViewModelTest.kt"

# Leaf implementation files:
Task: "T024 Create imageloader/src/main/res/values/ids.xml"
Task: "T027 Create data/ImageEntry.kt"
Task: "T029 Implement data/ImageListDataSource.kt"
Task: "T031 Add strings, dimens and vector drawables under app/src/main/res/"
Task: "T033 Create ui/ImageListUiState.kt"
```

## Parallel Example: User Story 2

```bash
# After T038 (Clock + FakeClock):
Task: "T039 Write DiskCacheTest.kt"
Task: "T041 Write ImageFetcherTest.kt"
```

## Parallel Example: User Story 3

```bash
Task: "T046 Extend ImageFetcherTest.kt for generations"
Task: "T047 Extend ImageLoaderTest.kt for invalidation"
Task: "T048 Extend ImageListViewModelTest.kt for invalidation"
Task: "T051 Add invalidate strings and menu/image_list.xml"
Task: "T052 Add cacheGeneration and ImageListEvent to ImageListUiState.kt"
```

## Parallel Example: User Story 4

```bash
Task: "T057 Write ImageLoaderJavaInteropTest.java"
Task: "T059 Write the library half of README.md"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: Setup. `./gradlew build` passes on an empty two-module project
2. Phase 2: Foundational. The downloader and decoder are tested
3. Phase 3: US1. The app shows the list with images, errors and retry
4. **Stop and validate** with T037 (quickstart rows 1, 3–6)

The MVP is a checkpoint, not a release: the assignment requires all four stories.

### Incremental Delivery

1. Setup + Foundational → the build is green
2. + US1 → list browsing works (MVP demo)
3. + US2 → 4-hour memory and disk caching and deduplication (rows 2, 7–9)
4. + US3 → the invalidate action (row 10)
5. + US4 → Java interop proven, library README
6. Polish → full quickstart walkthrough, zero Lint errors, README complete

Commit after each task or logical group, with small buildable commits (Constitution, Development
Workflow).

---

## Notes

- [P] tasks touch different files and have no dependencies on incomplete tasks
- The story label maps each task to spec.md for traceability
- `ImageLoader.kt`, `ImageFetcher.kt`, `ImageLoaderTest.kt` and the app UI files are extended
  across stories. Do those tasks in ID order
- Any change to the public API must match `contracts/image-loader-api.md` exactly
- Tests never use the real network or real time. The only real-time wait is the stall case in
  `HttpDownloaderTest` (a 200 ms timeout against a 2 s pause)
