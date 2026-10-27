# Contract: Image List JSON (consumed by `:app`)

**Feature**: `001-image-loader-library` | **Date**: 2026-10-27 | **Plan**: [../plan.md](../plan.md)

## Source

`GET https://zipoapps-storage-test.nyc3.digitaloceanspaces.com/image_list.json`

The response is `200` with `Content-Type: application/json`, about 6 KB, and needs no
authentication. Observed on 2026-10-27: last modified 2023-10-03.

## Shape

A top-level JSON array of objects:

```json
[
  { "id": 0, "imageUrl": "https://zipoapps-storage-test.nyc3.digitaloceanspaces.com/17_4691_besplatnye_kartinki_volkswagen_golf_1920x1080.jpg" },
  { "id": 1, "imageUrl": "https://zipoapps-storage-test.nyc3.digitaloceanspaces.com/17_5001_besplatnye_kartinki_volkswagen_golf_1920x1080.jpg" }
]
```

| Key | JSON type | Required | Meaning |
|-----|-----------|----------|---------|
| `id` | integer | yes | Entry id shown in the row |
| `imageUrl` | string (absolute URL) | yes | Image address passed to `ImageLoader.load` |

Other keys are ignored.

## Parsing rules (`ImageListParser`)

| Input | Result |
|-------|--------|
| Not valid JSON, or the root is not an array | Parse failure → `Error` state with Retry |
| Element that is not an object, or whose `id` is not an integer | Element skipped |
| Element missing `id` or `imageUrl`, or with a `null` or blank `imageUrl` | Element skipped |
| Valid elements | Kept in source order, including repeated `imageUrl`s |
| Empty array | `Content` with no rows |

## Current data (checked 2026-10-27, used as test fixture facts)

- 50 entries, ids 0–49 in order, all valid.
- 45 distinct `imageUrl`s, so 5 URLs appear twice.
- The ids 4, 13, 24, 37 and 49 point to URLs that answer `403 Forbidden` with an XML body.
- Successful images are JPEGs totalling about 40 MB. One is 23.6 MB (`wallpaperflare.com_wallpaper.jpg`)
  and every other one is at most 0.75 MB.

`ImageListParserTest` uses a trimmed copy of the real document plus synthetic malformed cases.
It does not fetch the live URL.
