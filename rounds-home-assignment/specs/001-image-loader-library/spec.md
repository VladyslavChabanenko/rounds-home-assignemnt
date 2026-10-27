# Feature Specification: Image Loader Library and Example App

**Feature Branch**: `001-image-loader-library`

**Created**: 2026-10-27

**Status**: Draft

**Input**: User description: "Implement the home assignment described in /Users/vlad/Downloads/instructions.md: an Android image downloading & caching library (no Glide or other external image libs) taking URL, placeholder and target ImageView; cached images valid for 4 hours; manual cache invalidation; Kotlin Coroutines; usable from both Java and Kotlin. Plus an example app in the same project that fetches the JSON image list from https://zipoapps-storage-test.nyc3.digitaloceanspaces.com/image_list.json, shows a list of images (image or placeholder while loading, plus its id), and has a button to invalidate the cache. App uses Android Views (not Compose), MVVM and Coroutines."

## Clarifications

### Session 2026-10-27

- Q: What should a row show when its image can't be loaded? → A: The caller may supply an
  optional error image. If none is supplied, the placeholder stays. The example app supplies a
  "broken image" icon.
- Q: After the cache is invalidated, what happens to the images already on screen? → A: Visible
  rows reload immediately: they show placeholders, then freshly downloaded images. The list
  itself is not fetched again.

## User Scenarios & Testing *(mandatory)*

Two kinds of people use this feature: **app users**, who browse images in the example app, and
**developers**, who add the image loader library to their own app. All four stories are required
by the assignment; priorities set the order of delivery, not whether a story is optional.

### User Story 1 - Browse the image list (Priority: P1)

An app user opens the example app and sees a scrollable list with one row per entry in the
published image list. Each row shows the entry's id and its image. While an image is still
loading, the row shows a placeholder instead.

**Why this priority**: This is the core of the assignment. It exercises the whole path, from
fetching the list to downloading and displaying images, and on its own it already shows that the
library works.

**Independent Test**: Launch the app on a device with network access. Confirm that every entry
in the published list appears with its id, that placeholders show while images load, and that
the images then replace them.

**Acceptance Scenarios**:

1. **Given** the device is online, **When** the user opens the app, **Then** a loading indicator
   shows until the list arrives, and then one row per entry appears in the source order, each
   with its id.
2. **Given** the list is shown, **When** a row's image has not finished loading, **Then** the row
   shows the placeholder, and the downloaded image replaces it once ready.
3. **Given** the list is shown, **When** the user scrolls quickly up and down through the whole
   list, **Then** no row ever shows an image that belongs to a different entry, and scrolling
   stays responsive.
4. **Given** an entry's image cannot be downloaded (for example, the server refuses it), **When**
   its row is shown, **Then** the row shows the error image (see FR-011), and the rest of the
   list keeps working.
5. **Given** the device is offline or the list cannot be read, **When** the user opens the app,
   **Then** an error message with a retry action appears, and retrying after the problem is
   fixed shows the list.
6. **Given** the list is shown, **When** the user rotates the device, **Then** the list stays
   visible without being fetched again.

---

### User Story 2 - Reuse cached images for 4 hours (Priority: P2)

Once an image has been downloaded, the app shows it again from the device's cache instead of
downloading it again, for 4 hours from the moment it was downloaded. This holds even after the
app has been closed and reopened.

**Why this priority**: Caching with a 4-hour validity is an explicit requirement and is what
separates an image *loader* from a plain download.

**Independent Test**: Load the list online and scroll through it. Turn off the network and scroll
back: the previously loaded images still appear. Restart the app online: those images appear
without being downloaded again. Then simulate the passage of more than 4 hours and confirm the
images are downloaded again.

**Acceptance Scenarios**:

1. **Given** an image was downloaded less than 4 hours ago, **When** it is requested again (in
   the same session or after the app restarts), **Then** it is shown without any network request.
2. **Given** an image was downloaded 4 hours ago or more, **When** it is requested again,
   **Then** it is downloaded again and the cache entry is refreshed. The expired copy is never
   shown.
3. **Given** two rows share the same image address, **When** both are shown at the same time,
   **Then** the image is downloaded only once and both rows display it.

---

### User Story 3 - Invalidate the cache manually (Priority: P3)

An app user taps a clearly visible "invalidate cache" control on the list screen. All cached
images are discarded, and images are downloaded afresh from then on.

**Why this priority**: Manual invalidation is an explicit requirement, and the button is the way
an evaluator checks it. It builds on Stories 1 and 2.

**Independent Test**: Load the list, tap the invalidate control, and observe (for example by
watching network traffic) that each image shown afterwards is downloaded again.

**Acceptance Scenarios**:

1. **Given** images are cached, **When** the user taps the invalidate control, **Then** all
   cached images are discarded, a brief confirmation is shown, and the visible rows immediately
   show placeholders and then freshly downloaded images. The list itself is not fetched again.
2. **Given** the cache has just been invalidated, **When** any image is requested, **Then** it is
   downloaded again rather than taken from the old cache.

---

### User Story 4 - Use the library from Kotlin or Java (Priority: P4)

A developer adds the library to an app written in Kotlin or in Java. With one call they ask it to
show the image at a given address in a given image view, with a given placeholder and,
optionally, an error image. They can also invalidate the cache with one call.

**Why this priority**: The assignment requires the library to support both languages. The
example app covers Kotlin, so this story ensures Java callers get the same capability.

**Independent Test**: Compile and run a small Java caller and a small Kotlin caller against the
library. Each loads an image into a view with a placeholder and invalidates the cache, with no
workaround code.

**Acceptance Scenarios**:

1. **Given** a Kotlin caller, **When** it requests an image with an address, a placeholder and a
   target view, **Then** the placeholder shows immediately and the image follows when ready.
2. **Given** a Java caller, **When** it makes the same request in plain Java (no coroutine
   constructs), **Then** the behavior is identical to the Kotlin caller's.
3. **Given** either caller, **When** it invalidates the cache, **Then** the next request for any
   image downloads it again.

---

### Edge Cases

- **Image unavailable**: the server refuses or cannot serve the image (5 of the 50 entries
  currently return "forbidden"), or the data is not a valid image. The row shows the error image,
  nothing is cached, and the app does not crash.
- **Repeated image addresses**: the published list contains 5 addresses that appear twice. Each
  address is downloaded once; every row that uses it shows the image.
- **Very large source images**: one image in the list is about 24 MB. It is displayed scaled to
  its row without running the device out of memory.
- **Row reuse during fast scrolling**: a row that is reassigned to another entry before its image
  arrives never shows the earlier entry's image.
- **Screen closed mid-load**: pending downloads for that screen stop, and the screen is not held
  in memory.
- **Offline with a valid cache**: cached images that are still valid are shown. Uncached or
  expired images show the error image.
- **Exactly 4 hours old**: an entry expires at its download time plus 4 hours. From that moment
  it is treated as absent.
- **Device clock set back**: if the device clock reads earlier than an entry's download time,
  the entry is treated as expired.
- **Interrupted or corrupt cache data** (for example, the app was killed while saving): the entry
  is treated as absent and downloaded again.
- **Storage full**: the image is still displayed after downloading. It is simply not cached.
- **Stalled download**: a download that receives no data within the time limit fails and shows
  the error image, instead of leaving the placeholder up forever.
- **Invalidation during downloads**: downloads already in progress may finish and be shown,
  because their data is fresh. Nothing that was cached before the tap is shown afterwards.
- **Malformed list**: if the list cannot be read at all, the error state with retry is shown.
  Individual entries missing an id or an image address are skipped.

## Requirements *(mandatory)*

### Functional Requirements

**Image loader library**

- **FR-001**: The library MUST let a caller request that the image at a given web address be
  shown in a given target image view, with a given placeholder shown in that view until the image
  is ready.
- **FR-002**: The placeholder and the optional error image (FR-011) MUST each be specifiable
  either as an app resource or as a ready-made drawable.
- **FR-003**: The library MUST cache every successfully downloaded image. A cached image is valid
  for 4 hours from the moment it was downloaded. While valid, requests for the same address MUST
  be served from the cache without a network request.
- **FR-004**: The cache MUST persist across app restarts, so the 4-hour validity applies beyond a
  single session.
- **FR-005**: An expired cache entry MUST NOT be displayed. The next request for its address MUST
  download the image again and replace the entry.
- **FR-006**: The library MUST provide manual invalidation that discards every cached image, so
  the next request for any address downloads it again.
- **FR-007**: When a target view receives a new request before its previous one finishes (for
  example, a reused list row), the earlier request MUST be cancelled and its result MUST NOT be
  shown in that view.
- **FR-008**: Pending work for a target view MUST stop when the screen containing the view is
  destroyed, and the library MUST NOT keep that view or screen in memory afterwards.
- **FR-009**: Concurrent requests for the same address MUST result in at most one download.
- **FR-010**: Images MUST be scaled down to the size the target view needs before display, so
  very large source images do not exhaust device memory.
- **FR-011**: When an image cannot be loaded (network failure, server error, invalid image data,
  or the time limit expires), the target view MUST show the error image supplied by the caller,
  or keep showing the placeholder if none was supplied. The host app MUST NOT crash.
- **FR-012**: Failed loads MUST NOT be cached. A later request for the same address MUST try the
  download again.
- **FR-013**: A download that receives no data for 30 seconds MUST be abandoned and treated as a
  failure.
- **FR-014**: Memory and device storage used by the cache MUST each have a size limit. When a
  limit is reached, the least recently used images MUST be removed first.
- **FR-015**: Cached images MUST be stored where only the host app can read them. Unreadable or
  corrupt entries MUST be treated as absent.
- **FR-016**: Downloading, saving, reading and decoding images MUST NOT freeze the user
  interface.
- **FR-017**: Every library capability (loading an image, invalidating the cache) MUST be
  available to both Kotlin and Java callers in each language's natural style. Java callers MUST
  NOT need coroutine-specific code.

**Example app**

- **FR-018**: On launch, the app MUST fetch the image list from
  `https://zipoapps-storage-test.nyc3.digitaloceanspaces.com/image_list.json` and show one row per
  valid entry, in source order, including entries that share an image address.
- **FR-019**: Each row MUST show the entry's id and its image through the library, with a
  placeholder while the image loads and an error image if it fails.
- **FR-020**: While the list is being fetched, the screen MUST show a loading indicator. If the
  fetch fails or the list cannot be read, the screen MUST show an error message with a retry
  action.
- **FR-021**: The list screen MUST provide a clearly visible control that invalidates the image
  cache when tapped. The visible rows MUST then reload their images immediately (placeholder
  first, then a fresh download) without the list being fetched again, and the app MUST confirm
  the action with a brief message.
- **FR-022**: After a configuration change such as rotation, the screen MUST show the same list
  without fetching it again.

### Constraints *(set by the assignment)*

- Image downloading and caching MUST be implemented without third-party image-loading libraries
  (such as Glide).
- The library MUST use current frameworks, including Kotlin Coroutines.
- The example app MUST use Android Views (not Jetpack Compose), the MVVM architecture, and
  Coroutines.
- The library and the example app MUST live in the same project.

### Key Entities

- **Image list entry**: one item of the published list. It has a numeric **id** and an **image
  address**. Entries keep the source order, and addresses may repeat across entries.
- **Cached image**: a downloaded image stored on the device, identified by its image address and
  stamped with the time it was downloaded. It is valid until that time plus 4 hours, or until the
  cache is invalidated.
- **Image request**: an address, a placeholder, an optional error image and a target view. Each
  target view has at most one active request at a time.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a typical mobile connection, the list of ids appears within 3 seconds of opening
  the app, and from then on every visible row shows either a placeholder or its own image, never
  an empty slot or another entry's image.
- **SC-002**: Within 4 hours of loading the list, (a) with the network turned off while the list
  is shown, 100% of the previously loaded images still display, and (b) after the app restarts
  online, 0 previously loaded images are downloaded again.
- **SC-003**: No image older than 4 hours is ever shown from the cache. This is verified by
  simulating the passage of time, with zero failures.
- **SC-004**: After the invalidate control is tapped, 0 images cached before the tap are shown.
  Every image displayed afterwards comes from a new download.
- **SC-005**: Scrolling once through the full published list, letting each visible row finish,
  starts exactly one download per distinct image address (45 for the current 50 entries).
  Repeated addresses and rows scrolled back to never start a second download while the image is in
  flight or cached. Only failed addresses (retried per FR-012) and downloads abandoned because no
  row needs them any more (FR-008) start again.
- **SC-006**: Scrolling the full list top to bottom and back 10 times in a row causes no crash,
  no out-of-memory failure (including the ~24 MB image) and no "app not responding" prompt.
- **SC-007**: Entries whose images cannot be loaded show the error image no more than 30 seconds
  after the connection attempt or the last data received (a refused request shows it at once),
  and the app does not crash in any of these cases.
- **SC-008**: Sample callers written in Kotlin and in Java each load an image with an address, a
  placeholder and a target view in a single call, and each invalidate the cache in a single call.

## Assumptions

- **Users**: app users are evaluators browsing the example app on a phone. Developers use the
  library from Kotlin or Java apps.
- **Validity window**: fixed at 4 hours from download, not extended when an image is viewed again,
  and not configurable (the assignment specifies one value).
- **Invalidation scope**: invalidation discards all cached images. Invalidating a single image is
  not required.
- **List storage**: the image list is fetched every time the app starts. It survives
  configuration changes but is not stored for offline use, so an offline start shows the error
  state even when images are cached.
- **Network**: image and list addresses are public and need no authentication.
- **Image formats**: formats the device decodes natively. Every entry in the current list is a
  JPEG.
- **Cache size limits**: concrete limits are chosen during planning, sized for a typical phone.
- **Screens**: the example app has a single list screen. Image detail views, pull-to-refresh and
  paging are out of scope (the list has 50 entries).
- **Published list contents** (checked on 2026-10-27): 50 entries with ids 0–49, 45 distinct
  image addresses, and 5 addresses that return "forbidden" (ids 4, 13, 24, 37 and 49).
- **Delivery**: the project is submitted as a public repository or a zip file.
