# Research: Image Loader Library and Example App

**Feature**: `001-image-loader-library` | **Date**: 2026-10-27 | **Plan**: [plan.md](plan.md)

The Technical Context had no open NEEDS CLARIFICATION markers. The spec left the cache size
limits open ("chosen during planning"), and the constitution requires the latest stable
toolchain. Versions were checked on 2026-10-27 against Google Maven, Maven Central,
services.gradle.org and the AGP 9.4.0 release notes. The image list was fetched and every
distinct URL was requested with HEAD to measure sizes.

---

## R1. Toolchain and versions

**Decision**:

| Item | Version | Note |
|------|---------|------|
| Android Gradle Plugin | 9.4.1 | Latest stable. Requires Gradle ≥ 9.6.0 and JDK ≥ 17. Supports up to API 37 |
| Gradle wrapper | 9.8.2 | Latest stable (`services.gradle.org/versions/current`) |
| Kotlin (KGP) | 2.4.21 | AGP 9 ships built-in Kotlin with a bundled KGP 2.2.x. Pin 2.4.21 by declaring the Kotlin plugin `apply false` in the root `plugins {}` block from the catalog. Verify with `./gradlew buildEnvironment` |
| kotlinx.coroutines | 1.11.0 | `kotlinx-coroutines-android` (library + app), `-test` (tests) |
| kotlinx.serialization | 1.12.0 | `:app` only |
| AndroidX | core-ktx 1.19.1, appcompat 1.8.0, activity-ktx 1.13.0, lifecycle 2.11.0, recyclerview 1.4.0, annotation 1.11.0 | Latest stable |
| Material Components | 1.14.0 | Material 3 theme, `MaterialToolbar` |
| Test | junit 4.13.2, robolectric 4.17, androidx.test:core 1.7.0, mockwebserver3 5.5.1 | Robolectric 4.17 has an API 37 (`android-all-instrumented` 17) runtime |
| ktlint Gradle plugin | 14.2.0 | `ktlintCheck` runs as part of `check` |
| SDK levels | `compileSdk` = `targetSdk` = 37, `minSdk` = 24 | 37 is the newest stable platform installed locally (`android-37.0`) and the AGP 9.4 maximum. 24 covers nearly all active devices, and nothing in the design needs a newer API |
| Java level | `sourceCompatibility`/`targetCompatibility` 17, Kotlin `jvmTarget` 17 | Matches the AGP minimum JDK. The installed JDKs are 17 (Homebrew) and 25 (Android Studio JBR), and both can run the build |

- Versions live only in `gradle/libs.versions.toml`. `minSdk`, `compileSdk` and `targetSdk` are
  declared once as catalog versions and read by both modules.
- With built-in Kotlin, modules apply only `com.android.library` / `com.android.application`, not
  `org.jetbrains.kotlin.android`. `:app` also applies `org.jetbrains.kotlin.plugin.serialization`.
- Explicit API mode: `kotlin { explicitApi() }` in `:imageloader`. If the built-in Kotlin
  extension does not expose `explicitApi()`, the fallback is
  `kotlin { compilerOptions { freeCompilerArgs.add("-Xexplicit-api=strict") } }`. Confirm at
  scaffold time by compiling a public declaration without a visibility modifier and checking that
  the build fails.

**Rationale**: Constitution, Technology Stack: "latest stable AGP, Kotlin, AndroidX, and
kotlinx.coroutines" and the latest stable API level.

**Alternatives considered**: AGP 8.x with the separate Kotlin Android plugin was rejected as not
current. Gradle toolchains (`jvmToolchain(17)`) were rejected because they would force JDK 17
detection on reviewers' machines for no benefit.

---

## R2. Modules, names and packages

**Decision**: modules `:imageloader` (library) and `:app` (sample). The library namespace is
`io.github.vladyslavchabanenko.imageloader`. The app's `applicationId` and namespace are
`io.github.vladyslavchabanenko.imageloader.sample`. The library uses one flat package, and every
declaration except `ImageLoader` is `internal`.

**Rationale**: two modules are the minimum the constitution and assignment allow. `io.github.<user>`
is the usual namespace for a project published on GitHub without its own domain. A flat package
suits about seven small files.

**Alternatives considered**: `com.example.*` was rejected because it cannot be published and
looks unfinished. A company namespace (`com.rounds.*`) was rejected because it implies ownership
the repository does not have. A separate `:imageloader-sample-java` module was rejected because
a Java test source in the library already proves interop (Constitution II).

---

## R3. Public API shape (Kotlin + Java)

**Decision**:

```kotlin
public class ImageLoader internal constructor(/* injected collaborators */) {
    @MainThread @JvmOverloads
    public fun load(url: String, target: ImageView, @DrawableRes placeholderResId: Int, @DrawableRes errorResId: Int = 0)
    @MainThread @JvmOverloads
    public fun load(url: String, target: ImageView, placeholder: Drawable, error: Drawable? = null)
    @AnyThread
    public fun invalidateCache()
    public companion object {
        @JvmStatic public fun get(context: Context): ImageLoader   // process-wide instance
    }
}
```

- The instance comes from `ImageLoader.get(context)`, which uses `applicationContext` and is
  lazily created and thread-safe. Kotlin and Java call it the same way:
  `ImageLoader.get(context).load(url, imageView, R.drawable.placeholder)`.
- Two overloads give each language a single call for each input form in FR-002. `@JvmOverloads`
  produces the 3-argument (no error image) and 4-argument variants for Java. Resource IDs are
  resolved with `target.context.getDrawable(id)`, and both overloads share one code path that
  works on `Drawable`s. `errorResId = 0` means no error image, in which case the placeholder stays
  (FR-011).
- Every method returns `Unit`. Cancellation is automatic: a new `load` on the same view cancels
  the old request, and the view's lifecycle ending cancels it too. The spec needs no handle or
  callback.
- There is no `suspend` variant and no `ImageView` extension function. The constitution allows
  them, but no requirement needs them (VII).

**Rationale**: this is the most direct mapping of the assignment's inputs (URL, placeholder,
target view). It satisfies SC-008 ("a single call") in both languages and uses no Kotlin-only
construct (Constitution II).

**Alternatives considered**:
- A fluent builder (`load(url).placeholder(..).error(..).into(view)`, as in Picasso) allows mixing
  a resource placeholder with a `Drawable` error image, but it adds a mutable builder type and is
  several calls. Callers who need to mix can pass `ContextCompat.getDrawable(...)`.
- A static `object` facade (`ImageLoader.load(...)`) is shorter, but it cannot be constructor-
  injected or faked in app code.
- An `ImageSource` sealed type for resource-or-drawable was rejected because it means extra public
  API and awkward Java construction.

---

## R4. Request lifecycle on a target view

**Decision** (all on the main thread):

1. Cancel the job stored in the view's tag (`R.id.imageloader_request`) (FR-007).
2. If the view already has a laid-out size and the memory cache holds a fresh entry for
   `(url, size)` (R9), set the bitmap synchronously and stop. This avoids placeholder flicker when scrolling back.
3. Otherwise show the placeholder and launch the request job:
   - Scope: `owner.lifecycleScope` of `target.findViewTreeLifecycleOwner()`. If that is null,
     which happens for a `RecyclerView` row bound before attachment, use the first
     `LifecycleOwner` found by unwrapping `target.context` (the hosting Activity). If neither
     exists, use the library scope. The job is launched with the injected main dispatcher, so the
     dispatcher stays injectable. `lifecycleScope` is cancelled at `ON_DESTROY` (FR-008), and a
     job launched on an already destroyed lifecycle never runs, so the placeholder stays.
   - Store the job in the view tag.
4. The job awaits the target size (R5) and gets the bitmap (memory, then fetcher R10, then decoder
   R6). If the view's job is still this job, it sets the bitmap.
5. On failure (any `Exception` except `CancellationException`, which is rethrown), it shows
   `error` if one was given, otherwise leaves the placeholder, and logs at WARN with tag
   `ImageLoader` (FR-011).

**Rationale**: the view tag is the platform's own per-view storage and keeps no global map of
views. `lifecycleScope` is the AndroidX building block for lifecycle-bound cancellation, so no
hand-written observer is needed. Once a job completes or is cancelled, nothing in the library
references the view (Constitution III).

**Alternatives considered**:
- Cancelling on `onViewDetachedFromWindow` breaks `RecyclerView`. Rows moved to the view cache are
  detached and later re-attached without rebinding, so they would keep the placeholder forever.
  Coil solves this with restart-on-attach machinery, which is too complex here.
- A `WeakHashMap<ImageView, Job>` adds global state and still needs main-thread confinement.
- Requiring callers to pass a `LifecycleOwner` adds an input the assignment does not list.

Known limitation (documented in the README): inside a Fragment, a row bound before attachment
falls back to the Activity lifecycle. The request still ends by completion or by the 30 s stall
timeout, and the view is released after that.

---

## R5. Target size

**Decision**: if `target.isLaidOut && !target.isLayoutRequested`, use its current width and height
minus padding. Otherwise suspend on a `suspendCancellableCoroutine` that registers a
`View.OnLayoutChangeListener` and removes it on resume or cancellation. A dimension that is still
≤ 0 after layout (for example `wrap_content`) falls back to that dimension of the display
(`resources.displayMetrics`).

**Rationale**: FR-010 needs the size before decoding. A cancellable await never leaves a listener
holding the continuation, which `androidx.core.view.doOnLayout` would. The fallback keeps
wrap-content views working without unbounded decoding.

**Alternatives considered**: decoding at screen size always wastes memory on small rows. Using
`ViewTreeObserver.OnPreDrawListener` works but needs `isAlive` checks and observer juggling.

---

## R6. Decoding and downscaling

**Decision**: `BitmapDecoder.decode(bytes, targetWidth, targetHeight): Bitmap` runs on the Default
dispatcher:

1. Bounds pass (`inJustDecodeBounds`). Non-positive dimensions → `InvalidImageException`.
2. `inSampleSize` = the largest power of two that keeps both sampled dimensions ≥ the target
   ("cover").
3. Decode with that sample size (ARGB_8888). A `null` result → `InvalidImageException`.
4. If the result is still more than the cover size, scale it down to the exact cover size with
   `Bitmap.createScaledBitmap` and recycle the intermediate. The image is never upscaled.

When decoding fails, the request fails, and the disk entry for that URL is removed (R8), so bad
data is never served again (FR-012, FR-015).

**Rationale**: power-of-two sampling bounds decode memory for the 23.6 MB JPEG (FR-010). The exact
cover scale cuts a 1920×1080 source in a 1080×600 row from 8.3 MB to about 2.6 MB, so roughly
three times as many rows fit in the memory cache. "Cover" is enough resolution for both
`centerCrop` and `fitCenter`.

**Alternatives considered**: `ImageDecoder.setTargetSize` is API 28+ only and would need a
second code path for `minSdk` 24. `RGB_565` halves memory but bands gradients. Hardware bitmaps
were rejected for simplicity.

---

## R7. Networking

**Decision**: an `internal fun interface Downloader { suspend fun download(url: String): ByteArray }`
with the production implementation `HttpDownloader`, which uses `HttpURLConnection` on the IO
dispatcher:

- `connectTimeout = readTimeout = 30 s`. The read timeout is exactly "no data for 30 seconds"
  (FR-013). Tests inject shorter timeouts through the internal constructor.
- A response outside 2xx → `IOException("HTTP <code>")`. The 5 URLs returning 403 take this path.
- The body is read in 8 KiB chunks with `ensureActive()` between chunks, so cancellation takes
  effect at the next chunk. A stalled read ends by timeout. When `Content-Length` is known, the
  code fills an exact-size `ByteArray`, which avoids the 2× copy of
  `ByteArrayOutputStream.toByteArray()` for the 23.6 MB file. Otherwise it uses a growing buffer.
- Streams are closed with `use {}` and `disconnect()` is called in `finally`. Redirects are
  followed by default.
- Each network download is logged with `Log.d` (tag `ImageLoader`) so evaluators can count
  downloads (SC-005, quickstart).

**Rationale**: the platform client avoids a runtime dependency (Constitution I). The fun interface
exists so tests can fake the network (VI).

**Alternatives considered**: OkHttp is better but is a third-party runtime dependency for the
library. `runInterruptible` does not help because Android socket reads ignore thread interrupts.
Calling `disconnect()` from another thread on cancellation is not thread-safe per the docs.
Streaming to a temp file first would avoid holding 24 MB in memory, but it complicates "decode
before caching" and storage-full handling. The bytes are short-lived and downloads are
deduplicated.

---

## R8. Disk cache

**Decision**: `DiskCache(directory: Lazy<File>, clock, maxBytes = 100 MB)`, confined to a single
serialized dispatcher (`ioDispatcher.limitedParallelism(1)`), so it needs no locks.

- Location: `context.cacheDir/image_loader/`. This is app-private (FR-015) and survives restarts
  (FR-004). The OS may trim it under storage pressure, which is fine for a cache. The directory
  is passed as `lazy { File(appContext.cacheDir, "image_loader") }` and first resolved on the disk
  dispatcher, because `Context.getCacheDir()` touches the disk and `ImageLoader.get()` runs on the
  main thread.
- File name: lowercase hex SHA-256 of the URL plus `.img`.
- Format: a 4-byte magic `ILC1`, then an 8-byte big-endian `downloadedAtMillis`, then the
  original image bytes.
- `get(url): DiskEntry?`, where `DiskEntry(bytes, downloadedAtMillis)`: read the file. Bad magic,
  a short file, an empty payload or an `IOException` mean the entry is absent, and it is deleted.
  Valid only if `downloadedAt <= now < downloadedAt + 4 h`, with `now = clock.nowMillis()`.
  Otherwise the entry is deleted and returns absent. This covers "exactly 4 hours" (expired) and
  "clock set back" (expired). The download time is returned so the memory cache can apply the
  same rule (R9).
- `put(url, bytes, downloadedAtMillis)`: `ImageFetcher` records `clock.nowMillis()` when the
  download finishes and passes it in, so the disk entry and the memory entry share one download
  time. Write to `<name>.tmp`, then `renameTo(<name>.img)`. On any `IOException`, such as a full
  disk, delete the temp file, log, and carry on. The caller still displays the bytes (edge case
  "Storage full").
- LRU: an in-memory `LinkedHashMap<String, Long>(accessOrder = true)` index of file name to size.
  It is built lazily on first use by listing the directory (deleting stray `.tmp` files) and
  ordering by `lastModified()`, which approximates recency across restarts. A put that pushes the
  total above `maxBytes` deletes the eldest entries (FR-014). An entry larger than `maxBytes` is
  not stored.
- `remove(url)` and `clear()` delete the files and the index entries.
- The TTL is a constant (`DiskCache.TTL_MILLIS = 14_400_000`, 4 h), fixed per the spec
  Assumptions. `DiskCache.isFresh(downloadedAtMillis, nowMillis)` holds the validity rule, and the
  memory cache uses it too (R9).

**Rationale**: a header holds the download time without relying on `File.setLastModified`,
which fails on some Android filesystems, and it works with an injected clock. Temp file plus
rename is atomic within one directory (Constitution V). Serial confinement also orders
`clear()` before every disk read queued after it (R11).

**Alternatives considered**: a journal (DiskLruCache style) is more robust but too large for
this scope. A sidecar metadata file doubles the files per entry and adds failure modes.
Encoding the timestamp in the file name makes keyed lookup depend on a directory scan.
`filesDir` would never be trimmed by the OS, but caches belong in `cacheDir`. Passing the
directory as a plain `File` was rejected because computing it in `ImageLoader.get()` is disk I/O
on the main thread.

**Size choice**: the current list totals about 40 MB across 45 images. 100 MB holds all of it
with room to spare and is modest for a phone. Glide's default is 250 MB.

---

## R9. Memory cache

**Decision**: `android.util.LruCache<MemoryKey, MemoryEntry>` with
`maxSize = Runtime.maxMemory() / 8` bytes and `sizeOf = entry.bitmap.allocationByteCount`.
`MemoryKey(url, width, height)` is the target size the bitmap was decoded for.
`MemoryEntry(bitmap, downloadedAtMillis)` keeps the download time from the `FetchResult` (R10). A
lookup returns the entry only if `DiskCache.isFresh(downloadedAtMillis, clock.nowMillis())`.
Otherwise it removes the entry, and the request continues as a miss. A bitmap larger than the
whole cache is simply not retained, which `LruCache` handles itself.

**Rationale**: this follows the platform's bitmap-caching guidance and is bounded (FR-014,
Constitution V). Keying by size keeps a small thumbnail from being reused for a large view. The
4-hour validity applies to every cache, not just the disk (FR-003, FR-005, G4). A process that
stays alive past 4 hours, or whose clock is changed, must not keep showing images from memory
(SC-003, quickstart row 9).

**Alternatives considered**: `androidx.collection.LruCache` was rejected because it adds a
dependency and Bitmap tests need Robolectric anyway. Keying by URL only would show
wrong-resolution bitmaps for views of different sizes. Storing bitmaps without a timestamp was
rejected because memory hits would outlive the 4-hour validity. Clearing the memory cache on a
timer was rejected because it needs a scheduled job and still misses clock changes.

---

## R10. Download deduplication and cancellation

**Decision**: `ImageFetcher.fetch(url, generation): FetchResult(bytes, source = DISK | NETWORK,
downloadedAtMillis)`. The download time is the disk header value for `DISK`, or the time the
download finished for `NETWORK`.

- An in-flight map `FetchKey(url, generation) → SharedFetch(deferred, waiters)` is guarded by
  `synchronized` (short, non-suspending critical sections).
- The first caller creates `scope.async { diskOrNetwork(url) }` in the library's `SupervisorJob`
  scope, and later callers join it. `diskOrNetwork` reads disk (on the serialized disk
  dispatcher) and, on a miss, downloads and then writes to disk.
- Each caller increments `waiters` and awaits. In `finally`, it decrements. When the count reaches
  0, the entry is removed and the deferred is cancelled if still running. A shared download
  therefore lives exactly as long as someone wants it (FR-008, FR-009).
- A failed deferred is removed with its last waiter, so nothing negative is cached and the next
  request retries (FR-012).

**Rationale**: sharing per URL (not per decoded size) guarantees "at most one download per
address" even for different view sizes. Reference counting stops abandoned downloads when rows
scroll away or the screen closes, without one waiter's cancellation hurting the others.

**Alternatives considered**: running shared work in the requester's scope means one cancelled
row cancels the download for others. Never cancelling shared work keeps downloading after the
screen is closed, against FR-008. A `Mutex` would work too, but the map operations never suspend,
so `synchronized` is simpler.

---

## R11. Manual invalidation semantics

**Decision**: `invalidateCache()` does the following:

1. Increments the `generation` counter (`AtomicInteger`).
2. Calls `memoryCache.evictAll()`, synchronously.
3. Launches `diskCache.clear()` on the serialized disk dispatcher. Because that dispatcher is
   FIFO, every disk read queued after this call runs after the clear.

Requests capture `generation` when they start, and the in-flight key includes it. Rules:

- A request started after the call uses a new `FetchKey`, so it never joins a pre-invalidation
  fetch. Its disk read runs after the clear and misses, so it downloads.
- A request already in flight whose result came from a cache (`source = DISK`) and whose
  generation is stale is not displayed. It restarts with the current generation and so
  downloads. A stale-generation network result is fresh data and may be shown (spec edge case
  "Invalidation during downloads").
- Bitmaps are put into the memory cache only if the request's generation is still current.

**Rationale**: this meets the spec edge case "nothing that was cached before the tap is shown
afterwards" (SC-004) without blocking the main thread on disk deletion, and without a registry of
active views.

**Alternatives considered**: deleting the directory synchronously is disk I/O on the main thread
(StrictMode violation). Cancelling all in-flight requests on invalidation leaves views that the
app does not rebind stuck on placeholders. A persisted "invalidated-at" timestamp needs main-thread
I/O and has clock-skew problems.

Accepted consequence: around an invalidation, an old-generation download and a new-generation
download of the same URL may overlap. FR-009 holds within a generation, and the spec asks for a
fresh download after invalidation anyway.

---

## R12. Test seams and test tooling

**Decision**:

- `internal fun interface Clock { fun nowMillis(): Long }`. Production uses
  `System.currentTimeMillis()` (wall time is required because entries persist across restarts).
- `ImageLoader`'s internal constructor takes `Downloader`, `DiskCache`, `Clock`, the memory-cache
  size, and the main, IO and Default dispatchers. `ImageLoader.get()` wires the production
  versions.
- Pure-JVM tests: `DiskCache` (JUnit `TemporaryFolder` and `FakeClock`), `ImageFetcher` (fake
  downloader with a `CompletableDeferred` gate, `StandardTestDispatcher`), and the sample-size
  math. `testOptions.unitTests.isReturnDefaultValues = true` lets `android.util.Log` calls no-op
  on the JVM.
- Robolectric tests (`@RunWith(RobolectricTestRunner::class)`,
  `@GraphicsMode(GraphicsMode.Mode.NATIVE)` for real `BitmapFactory` decoding,
  `unitTests.isIncludeAndroidResources = true`): `ImageLoaderTest`, `BitmapDecoderTest`'s decode
  cases, and the Java interop test. Lifecycle is faked with a hand-written `LifecycleOwner` backed
  by `LifecycleRegistry` and set via `setViewTreeLifecycleOwner`. The main dispatcher is the
  test's `StandardTestDispatcher`. View size is forced with `layout(0, 0, w, h)`.
- `HttpDownloaderTest` uses `mockwebserver3`: a 403 response, a body throttled past a 200 ms
  injected read timeout, and cancellation mid-body.

**Rationale**: Constitution VI requires deterministic tests with no real time or network, plus
hand-written fakes. Every time-, concurrency- and failure-dependent path has a test.

**Alternatives considered**: Mockito/MockK fakes were rejected because the constitution prefers
hand-written fakes. Instrumented tests were rejected because they are optional and slow, and
they need a device.

---

## R13. Example app architecture

**Decision**:

- `ImageListActivity` (AppCompat, ViewBinding, `enableEdgeToEdge()`) uses an `AppBarLayout` with
  `fitsSystemWindows` holding a `MaterialToolbar`, plus a `RecyclerView` with
  `clipToPadding=false` and bottom system-bar inset padding. Edge-to-edge is enforced at
  targetSdk 37, so insets must be handled. The layout also has a `CircularProgressIndicator` and
  an error group (message + Retry button). The visible group is chosen from the state.
- Toolbar menu item "Invalidate cache" with `app:showAsAction="always"` and no icon, so it
  renders as a labelled text button that stays visible (FR-021).
- `ImageListViewModel(repository, invalidateImageCache: () -> Unit)`:
  - `uiState: StateFlow<ImageListUiState>`, where `ImageListUiState` is a sealed interface of
    `Loading`, `Content(items: List<ImageListItem>)` and `Error`. The list loads in `init`, and
    `onRetry()` loads again. The ViewModel survives rotation, so there is no refetch (FR-022).
  - `onInvalidateCacheClicked()` calls the invalidator, increments `cacheGeneration`, re-maps
    `Content` items (each `ImageListItem` carries `cacheGeneration`), and sends
    `ImageListEvent.CacheInvalidated`.
  - `events: Flow<ImageListEvent>` is backed by `Channel(BUFFERED).receiveAsFlow()`. It carries
    one-off messages that must not reappear after rotation. The Activity collects it in
    `repeatOnLifecycle(STARTED)` and shows a `Snackbar`.
  - A `Factory` built with `viewModelFactory { initializer { … } }` creates the repository and
    passes `ImageLoader.get(app)::invalidateCache`.
- `ImageListAdapter(imageLoader)` is a `ListAdapter`. `DiffUtil` compares items by `id`. A content
  change caused only by `cacheGeneration` returns a `RELOAD` payload, so the visible rows rebind
  in place without a change animation and call `load()` again (placeholder, then fresh download,
  matching the spec clarification). `stateRestorationPolicy = PREVENT_WHEN_EMPTY` keeps the scroll
  position across rotation. Rows show the image (`centerCrop`, fixed height from `dimens.xml`,
  `contentDescription` "Image <id>") and an "ID: <id>" label.
- `ImageListRepository(dataSource, ioDispatcher)` provides
  `suspend fun loadImages(): List<ImageEntry>` and throws on failure. The ViewModel turns any
  `Exception` (rethrowing `CancellationException`) into `Error`.
  `HttpImageListDataSource` makes a `HttpURLConnection` GET with 30 s timeouts and fails on
  non-2xx.
- `SampleApplication` enables `StrictMode.setThreadPolicy(detectAll().penaltyLog())` when
  `BuildConfig.DEBUG` (`buildFeatures.buildConfig = true`).

**Rationale**: this follows Constitution IV exactly (sealed `StateFlow` state, unidirectional
flow, `repeatOnLifecycle`, `ListAdapter`, factory injection). Carrying the generation in the
items keeps "reload visible rows" state-driven and testable in the ViewModel without the View
tracking anything.

**Alternatives considered**:
- Putting the message in state with an acknowledgement (`onMessageShown()`) means more state and
  more code, and the message would also have to exist outside `Content`, because invalidation is
  allowed while Loading or Error.
- Having the View call `notifyItemRangeChanged()` after the click is an imperative UI rule
  outside the state.
- Retrofit/OkHttp for one GET is unnecessary weight.
- A Fragment adds lifecycle complexity for a single screen.
- A FAB for invalidation covers rows.

---

## R14. Parsing the image list

**Decision**: kotlinx.serialization with `Json { ignoreUnknownKeys = true }`.
`ImageListParser.parse(json: String): List<ImageEntry>` works as follows:

- The root must be a JSON array. Otherwise it throws `SerializationException`/
  `IllegalArgumentException`, which the ViewModel turns into the Error state.
- Each element is decoded separately as `ImageEntryDto(id: Long? = null, imageUrl: String? = null)`.
  An element that fails to decode, has a null id, or has a null or blank URL is skipped. Order and
  duplicates are kept (FR-018).
- The ids are `Long`, which tolerates any integer the server might use.

**Rationale**: this handles the spec edge case "Malformed list" at both levels: a broken
document becomes Error, and a broken entry is skipped. It is typed and runs on the plain JVM.

**Alternatives considered**: `org.json` (see the plan's Dependency Justification). Strict
whole-list decoding would turn one bad entry into a total failure.

---

## R15. Code-quality gates

**Decision**: the ktlint plugin is applied to both modules, with `.editorconfig` set to
`ktlint_official`-compatible defaults and the Android Studio import layout. Android Lint uses the
defaults (`abortOnError = true`), with `warningsAsErrors = false`. `./gradlew build` therefore
runs compile, unit tests, Lint and ktlint, which is the constitution's definition of done.
Explicit API mode applies only in `:imageloader`. KDoc is required on every public library
declaration.

**Rationale**: Constitution, Code standards and Development Workflow.

**Alternatives considered**: Spotless and detekt have more configuration than this scope needs.
