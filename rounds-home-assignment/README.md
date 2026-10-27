# Image Loader

An Android image downloading and caching library, with an example app that uses it. The library
shows the image at a URL in an `ImageView`, with a placeholder while it loads and an optional
error image. It keeps downloaded images in memory and on disk for 4 hours, shares one download
between concurrent requests, and works the same from Kotlin and Java.

The project has two Gradle modules:

| Module | What it is |
|--------|------------|
| `:imageloader` | The library. Built on the Android SDK, kotlinx.coroutines and AndroidX Lifecycle only. No third-party image or HTTP libraries |
| `:app` | The example app: a list of images fetched from a published JSON file |

## The library

### Use it

Add the module to your app:

```kotlin
dependencies {
    implementation(project(":imageloader"))
}
```

`minSdk` is 24. The library needs the `INTERNET` permission and declares it in its manifest.

**Kotlin**

```kotlin
val imageLoader = ImageLoader.get(context)
imageLoader.load(url, imageView, R.drawable.placeholder, R.drawable.error)
imageLoader.invalidateCache()
```

**Java**

```java
ImageLoader imageLoader = ImageLoader.get(context);
imageLoader.load(url, imageView, R.drawable.placeholder, R.drawable.error);
imageLoader.invalidateCache();
```

The whole public API is one class:

| Call | What it does |
|------|--------------|
| `ImageLoader.get(context)` | The process-wide instance, created on first use. Thread-safe |
| `load(url, target, placeholderResId[, errorResId])` | Shows the placeholder at once, then the image. `errorResId` 0 or omitted means no error image |
| `load(url, target, placeholder[, error])` | The same with ready-made `Drawable`s. `error` may be `null` |
| `invalidateCache()` | Discards every cached image, in memory and on disk |

Everything else in the module is `internal`. Kotlin compiles `internal` to a public JVM class, so
`javap` lists those classes, but they are not API and may change. The Java test
`ImageLoaderJavaInteropTest` calls every public method with no coroutine code, and the complete
contract is in [specs/001-image-loader-library/contracts/image-loader-api.md](specs/001-image-loader-library/contracts/image-loader-api.md).

### What it guarantees

**Threading**

- `load` must be called on the main thread. When it returns, the target shows the placeholder or,
  if the decoded image for this URL and view size is already in memory and the view has been laid
  out, the final image.
- Downloading, disk access and decoding never run on the main thread.
- `invalidateCache()` may be called from any thread and returns without waiting for I/O.

**Errors**

- Nothing is thrown into the caller. A network failure, a non-2xx status, no data for 30 seconds,
  data that is not an image, or a disk error shows the error image, or keeps the placeholder when
  there is none. The failure is logged at `WARN` with tag `ImageLoader`.
- A failed load is never cached, so the next request for that URL tries again.
- Each network download is logged at `DEBUG` with tag `ImageLoader` as `download <url>`. Count
  those lines to see how many downloads happened.

**Lifecycle**

- A later `load` on the same view cancels the earlier request, and the earlier result is never
  shown in that view. This is what makes recycled list rows safe.
- When the `LifecycleOwner` that owns a request reaches `ON_DESTROY`, the request is cancelled and
  its download stops, unless another live request shares it. After a request finishes or is
  cancelled, the library holds no reference to the view.
- Concurrent requests for the same URL share one download. The download is cancelled when the last
  request waiting for it goes away.

**Which owner counts.** A request belongs to the view-tree owner (`findViewTreeLifecycleOwner()`)
once the view is laid out. Before that it belongs to the owner found by unwrapping the view's
context, normally the hosting Activity. With neither, it belongs to the library's own scope. So a
list row that is bound but never laid out starts no download, and is released when the row is
rebound or the Activity is destroyed.

**Caching**

- A downloaded image is served from cache, with no network request, for 4 hours from the moment
  it was downloaded. This holds across process restarts. At 4 hours or later, or when the device
  clock reads earlier than the download time, the entry is not used and the image is downloaded
  again. The validity is fixed and is not extended when an image is viewed again.
- Images are decoded at no more than the size the view needs and never upscaled, so a 24 MB
  source does not exhaust memory.
- Memory use is limited to 1/8 of the maximum heap, and disk use to 100 MB. The least recently
  used entries go first.
- Files live in the app-private `cacheDir/image_loader/` and are written through a temporary file
  and then renamed. An unreadable or corrupt file counts as absent.
- `invalidateCache()` means that no load started after it returns uses anything cached before it.
  A load already in flight does not show cache-sourced data, but may show the image it is
  downloading, which is fresh.

### What it does not provide

These are out of scope:

- request handles or completion callbacks
- `suspend` variants
- transformations or crossfades
- invalidating a single URL
- a configurable TTL or cache sizes
- HTTP cache headers, custom headers or authentication
- targets other than `ImageView`
