# Nuvio for Apple TV (tvOS)

A native SwiftUI tvOS app built on the Kotlin Multiplatform core in [`../shared`](../shared).

```
shared/   Kotlin Multiplatform: models, Stremio add-on protocol, add-on/catalog/meta/stream/search
          repositories, watch progress + library storage. Targets: jvm, tvosArm64, tvosSimulatorArm64.
          Ships to Swift as NuvioShared.xcframework.
tvos/     SwiftUI app (XcodeGen spec) that uses NuvioShared.
```

## What's ported

| Area | Apple TV implementation |
| --- | --- |
| Add-ons | Install (URL / `stremio://`), enable, reorder, remove, cached manifests; Cinemeta + OpenSubtitles defaults. |
| Home | Catalog rows (hideable), paging, "See all" with genre filters, Continue Watching, Jellyfin/Emby library rows. |
| Search | Every add-on search catalog in parallel. |
| Details | Add-on meta (Android candidate rules), TMDB enrichment (logo, cast/crew photos, person pages, collections, "More like this", certification), MDBList ratings, seasons/episodes, watched marks, trailers. |
| Streams | Add-ons in parallel, embedded streams, Jellyfin/Emby copies, local JS scraper plugins, debrid cache badges, auto-play regex. |
| Player | **mpv (MPVKit)**: MKV and every FFmpeg codec, **libass** ASS/SSA, audio/subtitle tracks, add-on subtitles (OpenSubtitles…), subtitle style/size/position/delay, audio delay, speed, resume, skip intro/recap/credits (IntroDB, AniSkip), auto-next episode with binge-group matching, HDR/DV passthrough, Match Content (frame rate + dynamic range). Apple's AVPlayer selectable in Settings. |
| Debrid | Torbox, Premiumize (device code or API key), Real-Debrid (API key): torrent / clientResolve streams resolved to direct links, cache checks. |
| Accounts | Nuvio account (QR or email): add-ons, library, progress and watched history synced with the Android app. Trakt (scrobble, history, progress, watchlist), Simkl, AniList. |
| Live TV | M3U, Xtream Codes, Stalker portals; XMLTV guide (gzip too), now/next, per-channel guide, catch-up, favourites, recents, ▲/▼ channel zapping. |
| Other | Profiles with PIN, release calendar, library (local + Trakt watchlist), settings for everything above. |

## Not ported

- **CloudStream extensions** (Android `.dex` code) and **torrent streaming** (no torrent engine; use debrid).
- Android-only player internals with no tvOS equivalent: ExoPlayer extractors, DV5→HDR10 GL tone mapper, NEON libass blending, ffmpeg downmix module, tunneling. mpv + tvOS handle these areas their own way.
- Smaller items not done yet: seek-bar thumbnails, watch party, ambient/screensaver mode, theme/layout variants, phone-assisted setup page (the iPhone keyboard works for typing), in-app updater (not applicable on tvOS), Supabase avatars/membership extras.

## Build

CI (`.github/workflows/tvos-build.yml`) runs on the `xcode-27` runner (override with the repository
variables `TVOS_RUNNER` / `XCODE_DEVELOPER_DIR`), takes API keys from the `LOCAL_PROPERTIES_BASE64`
(or `NUVIO_LOCAL_PROPERTIES_BASE64`) secret, and uploads:
`NuvioTV-tvOS-simulator` (`.app` for the Apple TV simulator) and `NuvioTV-tvOS-unsigned-ipa`.

Locally on a Mac (Xcode 16+, JDK 17):

```sh
./gradlew -p shared jvmTest assembleNuvioSharedReleaseXCFramework
brew install xcodegen
tvos/scripts/write-secrets.sh local.properties   # optional: TMDB/Trakt/Simkl/… keys
cd tvos && xcodegen generate && open NuvioTV.xcodeproj
```

Pick your team under *Signing & Capabilities* to run on a real Apple TV.

After changing Kotlin code, rebuild the XCFramework (the Xcode project links the prebuilt
`shared/build/XCFrameworks/release/NuvioShared.xcframework`).

## Installing the unsigned IPA

The CI IPA is unsigned. Re-sign it with your own Apple ID / developer certificate
(e.g. Xcode, Sideloadly or AltStore-style tools that support tvOS) before installing on an Apple TV.

## Swift interop notes

- Kotlin `suspend` functions are `async throws` in Swift and must be called from the main actor.
- Observations (`observeAddons`, `loadStreams`, …) take callbacks and return a `CoreSubscription`; call `cancel()`.
- Kotlin `description` properties appear as `description_`; Kotlin `Stream` is `AddonStream` in Swift
  (see `AppModel.swift`) to avoid clashing with `Foundation.Stream`.
