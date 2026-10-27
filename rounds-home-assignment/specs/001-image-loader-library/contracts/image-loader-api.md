# Contract: `:imageloader` Public API

**Feature**: `001-image-loader-library` | **Date**: 2026-10-27 | **Plan**: [../plan.md](../plan.md)

This is the complete public surface of the library. Everything else in the module is `internal`
(explicit API mode). Any change to this file is a breaking-change review item.

Package: `io.github.vladyslavchabanenko.imageloader`

## Declarations

```kotlin
/**
 * Downloads images into [ImageView]s, with a memory cache and a disk cache valid for 4 hours.
 * Obtain the process-wide instance with [get].
 */
public class ImageLoader private constructor(/* internal collaborators */) {

    /**
     * Shows [placeholderResId] in [target] immediately, then the image at [url] once it is ready.
     * On failure shows [errorResId], or keeps the placeholder when [errorResId] is 0.
     */
    @MainThread
    @JvmOverloads
    public fun load(
        url: String,
        target: ImageView,
        @DrawableRes placeholderResId: Int,
        @DrawableRes errorResId: Int = 0,
    )

    /** Same as the resource overload, with ready-made drawables. [error] `null` keeps the placeholder. */
    @MainThread
    @JvmOverloads
    public fun load(
        url: String,
        target: ImageView,
        placeholder: Drawable,
        error: Drawable? = null,
    )

    /** Discards every cached image (memory and disk). Subsequent loads download again. */
    @AnyThread
    public fun invalidateCache()

    public companion object {
        /** Returns the process-wide [ImageLoader], creating it on first use. */
        @JvmStatic
        public fun get(context: Context): ImageLoader
    }
}
```

Java sees these signatures:

```java
public static ImageLoader get(Context context);                 // ImageLoader.get(...)
public void load(String url, ImageView target, int placeholderResId);
public void load(String url, ImageView target, int placeholderResId, int errorResId);
public void load(String url, ImageView target, Drawable placeholder);
public void load(String url, ImageView target, Drawable placeholder, Drawable error); // error nullable
public void invalidateCache();
```

## Usage

**Kotlin**

```kotlin
val imageLoader = ImageLoader.get(context)
imageLoader.load(url, binding.image, R.drawable.ic_image_placeholder, R.drawable.ic_broken_image)
imageLoader.invalidateCache()
```

**Java**

```java
ImageLoader imageLoader = ImageLoader.get(context);
imageLoader.load(url, imageView, R.drawable.ic_image_placeholder, R.drawable.ic_broken_image);
imageLoader.invalidateCache();
```

## Behavioral guarantees

| # | Guarantee | Spec |
|---|-----------|------|
| G1 | `load` must be called on the main thread. When it returns, `target` shows either the placeholder or, if a decoded image for this URL and view size is already in memory, the final image | FR-001, FR-007 |
| G2 | A later `load` on the same `target` cancels the earlier request. The earlier result is never applied to `target` | FR-007 |
| G3 | If `target` (or, before attachment, its context) has a `LifecycleOwner`, reaching `ON_DESTROY` cancels the request, and its download stops unless another live request shares it. After a request completes or is cancelled, the library holds no reference to `target` | FR-008 |
| G4 | A successfully downloaded image is served from cache, with no network request, for 4 hours from the moment it was downloaded. This holds across process restarts. At or after 4 hours, or if the device clock reads earlier than the download time, the entry is not used and the image is downloaded again | FR-003–005 |
| G5 | Concurrent requests for the same URL share one download | FR-009 |
| G6 | Images are decoded at no more than the size needed to cover `target` (never upscaled) | FR-010 |
| G7 | Network failure, non-2xx HTTP status, 30 s without data, undecodable data, or disk errors never throw into the caller. The view shows the error image, or keeps the placeholder. Failures are logged with tag `ImageLoader` and are not cached | FR-011–013, FR-015 |
| G8 | `invalidateCache()` may be called from any thread and returns without blocking on I/O. No load started after it returns uses data cached before it. A load in flight at that moment does not display cache-sourced data. It may display data it is downloading, which is fresh | FR-006; spec edge case "Invalidation during downloads"; SC-004 |
| G9 | Memory use is bounded to 1/8 of the max heap, and disk use to 100 MB. Least recently used entries are removed first. Files live in app-private `cacheDir/image_loader/` and are written atomically. Unreadable or corrupt files are treated as absent | FR-014, FR-015 |
| G10 | Downloading, disk access and decoding never run on the main thread | FR-016 |
| G11 | Each network download is logged at DEBUG with tag `ImageLoader` | Verification aid (SC-005) |

## Explicitly not provided

These are out of scope per the spec and Constitution VII:

- request handles or completion callbacks
- `suspend` variants
- transformations or crossfades
- per-URL invalidation
- a configurable TTL or cache sizes
- HTTP cache headers
- custom headers or authentication
- non-`ImageView` targets
