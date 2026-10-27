# Quickstart: Image Loader Library and Example App

**Feature**: `001-image-loader-library` | **Date**: 2026-10-27 | **Plan**: [plan.md](plan.md)

All commands run from the Gradle root, `rounds-home-assignment/`.

## Prerequisites

- JDK 17 or newer (Android Studio's bundled JBR works)
- Android SDK with platform `android-37` and recent build tools. `local.properties` points to it
  (`sdk.dir=...`) or `ANDROID_HOME` is set
- For running the app: an emulator or device with API 24 or newer and internet access

## Build, test, lint

```bash
./gradlew build
```

This runs compilation of both modules, all JVM unit tests (including the Java interop test),
Android Lint and ktlint. It is the definition of done.

```bash
./gradlew test
```

```bash
./gradlew :imageloader:testDebugUnitTest --tests "*DiskCacheTest"
```

```bash
./gradlew ktlintFormat
```

## Run the example app

```bash
./gradlew :app:installDebug
```

Then launch "Image Loader Sample", or press Run in Android Studio with the `app` configuration.

Watch the library's downloads and failures:

```bash
adb logcat -s ImageLoader
```

## Manual acceptance walkthrough

Each line maps to the spec's acceptance scenarios and success criteria.

| # | Steps | Expected | Spec |
|---|-------|----------|------|
| 1 | Fresh install, online, open app | Progress indicator, then 50 rows ("ID: 0" … "ID: 49") in order. Placeholders are replaced by images | US1-1, US1-2, SC-001 |
| 2 | Count `download` lines in logcat after scrolling the whole list once | 45 downloads in total (one per distinct URL). Duplicated URLs are not downloaded twice | US2-3, SC-005 |
| 3 | Look at ids 4, 13, 24, 37, 49 | Broken-image icon. The rest of the list keeps working | US1-4, SC-007 |
| 4 | Fling top to bottom and back 10 times | No wrong image in any row, no crash, no ANR, no OOM (including the 23.6 MB image) | US1-3, SC-006 |
| 5 | Rotate the device | Same list and scroll position. No new list fetch | US1-6, FR-022 |
| 6 | Enable airplane mode, kill and reopen the app | Error message with Retry (the list is not stored offline). Disable airplane mode, tap Retry → list shows | US1-5, spec Assumptions |
| 7 | With the list loaded online, enable airplane mode and scroll through rows seen before | Previously loaded images show from cache. Rows never loaded show the broken-image icon | US2-1, SC-002 |
| 8 | Kill and reopen online within 4 hours, then scroll | No `download` lines for images seen before | US2-1, FR-004 |
| 9 | Set the device clock forward 4 h or more (or earlier than now), reopen, scroll | Every image is downloaded again (logcat) | US2-2, SC-003 |
| 10 | Tap **Invalidate cache** in the toolbar | Snackbar "Image cache cleared". Visible rows flash placeholders, then reload with new `download` lines. No list refetch | US3-1, US3-2, SC-004 |

Network traffic can also be watched in Android Studio's **App Inspection → Network Inspector**.

## Using the library in another app

```kotlin
// settings.gradle.kts includes ":imageloader"; module build.gradle.kts:
dependencies { implementation(project(":imageloader")) }
```

Kotlin:

```kotlin
ImageLoader.get(context).load(url, imageView, R.drawable.placeholder, R.drawable.error)
ImageLoader.get(context).invalidateCache()
```

Java:

```java
ImageLoader.get(context).load(url, imageView, R.drawable.placeholder, R.drawable.error);
ImageLoader.get(context).invalidateCache();
```

The full contract and guarantees are in [contracts/image-loader-api.md](contracts/image-loader-api.md).
