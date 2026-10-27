# Data Model: Image Loader Library and Example App

**Feature**: `001-image-loader-library` | **Date**: 2026-10-27 | **Plan**: [plan.md](plan.md)

This file lists the entities from the spec's Key Entities section and the internal types the
design adds. Every type except `ImageLoader` is `internal` in `:imageloader` (Constitution I).
Field types are Kotlin.

---

## Library (`:imageloader`)

### Image request (runtime concept, no public type)

One call to `ImageLoader.load(...)`. Spec entity: **Image request**.

| Field | Type | Rules |
|-------|------|-------|
| `url` | `String` | Passed through unchanged. An invalid URL fails like any other load error (FR-011) |
| `target` | `ImageView` | Has at most one active request, stored in tag `R.id.imageloader_request` (FR-007) |
| `placeholder` | `Drawable` | Required. A resource ID is resolved via `target.context.getDrawable(id)` |
| `error` | `Drawable?` | Optional. `null` (or resource ID `0`) keeps the placeholder on failure |
| `generation` | `Int` | The loader's cache generation when the request started (see Cache generation) |
| `job` | `Job` | Child of the target's `lifecycleScope` (or of the library scope when there is no lifecycle owner) |

**State transitions** (all applied on the main thread):

```text
load() ──► [memory hit with known size] ──► Displayed            (synchronous, no placeholder)
   │
   └─► PlaceholderShown ──► AwaitingSize ──► Fetching ──► Decoding ──► Displayed
                │                 │              │            │
                │                 │              │            └─► (source=DISK && generation stale) ──► restart at Fetching
                │                 │              │                                                      with current generation
                ├─────────────────┴──────────────┴──► Failed: show error, or keep placeholder (logged)
                └─► Cancelled (new load() on target, or lifecycle ON_DESTROY): view untouched, nothing applied
```

A result is applied only if the view's tag still holds this request's job. A cancelled request
releases every reference to the view.

### MemoryKey

| Field | Type | Rules |
|-------|------|-------|
| `url` | `String` | |
| `width` | `Int` | > 0. The target width the bitmap was decoded for |
| `height` | `Int` | > 0. The target height the bitmap was decoded for |

`data class`, the key of the memory cache.

### Memory cache

`android.util.LruCache<MemoryKey, Bitmap>`.

- Capacity: `Runtime.getRuntime().maxMemory() / 8` bytes. Entry size:
  `Bitmap.allocationByteCount`. Least recently used entries are evicted first (FR-014).
- A put happens only when the request's generation equals the current generation.
- `invalidateCache()` empties it with `evictAll()`.

### Disk cache entry (spec entity: **Cached image**)

One file per URL in `<cacheDir>/image_loader/`.

| Part | Type / size | Rules |
|------|-------------|-------|
| File name | `sha256(url)` as 64 lowercase hex chars + `.img` | Temp file while writing: same name + `.tmp` |
| Magic | 4 bytes, ASCII `ILC1` | Any mismatch → corrupt |
| `downloadedAtMillis` | 8 bytes, big-endian `Long` | Wall-clock time from the injected `Clock` when the download finished |
| Payload | rest of file | Original downloaded bytes. Must be non-empty |

**Validity rule** (FR-003, FR-005, edge cases "Exactly 4 hours old" and "Device clock set back"):

```text
valid  ⇔  downloadedAtMillis ≤ now  AND  now < downloadedAtMillis + 14_400_000
```

**Lifecycle**:

```text
Absent ──put()──► Writing (.tmp) ──rename──► Valid ──get() with expired time──► deleted ──► Absent
                       │                       │
                       └─ IOException ─────────┤──get(): bad magic / short / unreadable ──► deleted ──► Absent
                          (.tmp deleted)       ├──decode failure reported by loader (remove) ──► Absent
                                               ├──LRU eviction (total > 100 MB) ──► Absent
                                               └──clear() (invalidation) ──► Absent
```

**Index** (memory only, rebuilt on first use after start): `LinkedHashMap<String /*file name*/, Long
/*bytes*/>` in access order. It is seeded from a directory listing sorted by `lastModified()`, and
stray `.tmp` files are deleted during the scan. `totalBytes` is the sum of the values. After a put,
the eldest entries are deleted while `totalBytes > 100 MB`. A single payload larger than the limit
is not stored.

**Concurrency**: confined to one serialized dispatcher (`ioDispatcher.limitedParallelism(1)`).
Operations run in FIFO order, so a `clear()` enqueued by `invalidateCache()` runs before every
read enqueued after it.

### FetchKey / SharedFetch / FetchResult

| Type | Fields | Rules |
|------|--------|-------|
| `FetchKey` | `url: String`, `generation: Int` | `data class`. Key of the in-flight map |
| `SharedFetch` | `deferred: Deferred<FetchResult>`, `waiters: Int` | `waiters` ≥ 1 while present in the map. At 0 the entry is removed and `deferred` is cancelled if still active |
| `FetchResult` | `bytes: ByteArray`, `source: Source` | `Source` is `enum { DISK, NETWORK }`. A plain class, not a data class, because it holds an array |

Invariant (FR-009): for each `FetchKey`, at most one `SharedFetch` exists, so there is at most one
download per URL per generation. A failed `SharedFetch` is never reused after its waiters leave
(FR-012).

### Cache generation

`AtomicInteger` in `ImageLoader`, starting at 0. `invalidateCache()` increments it, then empties
the memory cache, then enqueues `diskCache.clear()`. Requests compare their captured generation
with the current value before reusing cache-sourced data and before writing to the memory cache.

### Clock

`internal fun interface Clock { fun nowMillis(): Long }`. Production uses
`System.currentTimeMillis()`. Tests use `FakeClock(var now: Long)`.

### Downloader

`internal fun interface Downloader { suspend fun download(url: String): ByteArray }`. The
production `HttpDownloader` has `connectTimeoutMillis = readTimeoutMillis = 30_000`. Responses
outside 2xx throw `IOException`.

---

## Example app (`:app`)

### ImageEntryDto (wire format)

| Field | Type | Rules |
|-------|------|-------|
| `id` | `Long?` | JSON `id`. Missing → `null` → entry skipped |
| `imageUrl` | `String?` | JSON `imageUrl`. Missing, `null` or blank → entry skipped |

`@Serializable`. Unknown keys are ignored. See [contracts/image-list-json.md](contracts/image-list-json.md).

### ImageEntry (domain; spec entity: **Image list entry**)

| Field | Type | Rules |
|-------|------|-------|
| `id` | `Long` | Shown in the row as "ID: <id>" |
| `imageUrl` | `String` | Not blank. May repeat across entries |

`data class`. The list keeps the source order and keeps duplicates.

### ImageListItem (UI model)

| Field | Type | Rules |
|-------|------|-------|
| `id` | `Long` | `DiffUtil` item identity |
| `imageUrl` | `String` | |
| `cacheGeneration` | `Int` | The ViewModel's invalidation count. A change rebinds the row with the `RELOAD` payload |

`data class`. `areContentsTheSame` uses equality. `getChangePayload` returns `RELOAD` when only
`cacheGeneration` differs.

### ImageListUiState

```kotlin
sealed interface ImageListUiState {
    data object Loading : ImageListUiState
    data class Content(val items: List<ImageListItem>) : ImageListUiState
    data object Error : ImageListUiState
}
```

**State transitions** (`ImageListViewModel`):

```text
          init                       success
(start) ───────► Loading ─────────────────────────► Content(items, generation g)
                    │                                   │  onInvalidateCacheClicked()
                    │ failure (any Exception;           ▼
                    │  CancellationException rethrown)  Content(items, generation g+1)  + event CacheInvalidated
                    ▼
                  Error ──onRetry()──► Loading
```

- `onInvalidateCacheClicked()` in `Loading` or `Error` still invalidates the cache, increments
  the generation and emits the event. The state itself does not change.
- Rotation keeps the same ViewModel and the same `StateFlow` value, so the list is not fetched
  again (FR-022).

### ImageListEvent

```kotlin
sealed interface ImageListEvent {
    data object CacheInvalidated : ImageListEvent   // shown as a Snackbar: "Image cache cleared"
}
```

Delivered once through `Channel(BUFFERED).receiveAsFlow()` and collected in
`repeatOnLifecycle(STARTED)`.
