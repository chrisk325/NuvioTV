# Nuvio for Apple TV (tvOS)

A native SwiftUI tvOS app built on the Kotlin Multiplatform core in [`../shared`](../shared).

```
shared/   Kotlin Multiplatform: models, Stremio add-on protocol, add-on/catalog/meta/stream/search
          repositories, watch progress + library storage. Targets: jvm, tvosArm64, tvosSimulatorArm64.
          Ships to Swift as NuvioShared.xcframework.
tvos/     SwiftUI app (XcodeGen spec) that uses NuvioShared.
```

## What works

| Area | Status |
| --- | --- |
| Add-ons | Install by URL (also `stremio://`), enable/disable, reorder, remove, manifest refresh/cache. Defaults: Cinemeta + OpenSubtitles (same as Android). |
| Home | Catalog rows from all enabled add-ons, lazy loading + paging, "See all" grid, Continue Watching row. |
| Search | Every add-on catalog with a `search` extra, in parallel. |
| Details | Backdrop/logo/info, cast, seasons + episodes, Resume / next-episode, Library toggle. Meta add-on selection ported from Android `MetaRepositoryImpl`. |
| Streams | All stream add-ons queried in parallel, results appear as they arrive (port of `StreamRepositoryImpl`). Embedded (`videos[].streams`) streams included. |
| Playback | AVPlayer (`AVPlayerViewController`) with add-on `proxyHeaders.request`, resume, progress saved every 10 s. |
| Library | Saved on device. |

## Not ported yet (Android-only today)

- **Player engines**: AVPlayer handles MP4/MOV/HLS. MKV, ASS subtitles, DV P5→HDR10, AC-3/DTS downmix
  etc. come from ExoPlayer/libmpv on Android. Next step: add [MPVKit](https://github.com/mpvkit/MPVKit) or KSPlayer behind a player abstraction.
- External/add-on subtitles (OpenSubtitles results aren't shown in the player yet).
- Torrent streams (no torrent engine on tvOS), YouTube trailers/streams, debrid resolving.
- Cloud account sync (Supabase), Trakt/Simkl/AniList/MDBList, profiles, Live TV/IPTV, calendar, watch party.
- CloudStream/JS plugins: can't run on tvOS (Android `.dex` code); downloaded executable code also conflicts with App Store rules.
- Phone-assisted setup page (typing URLs with the Siri Remote is slow; the iPhone keyboard via Continuity works).

## Build

CI (`.github/workflows/tvos-build.yml`) builds everything on a macOS runner and uploads:
`NuvioTV-tvOS-simulator` (`.app` for the Apple TV simulator) and `NuvioTV-tvOS-unsigned-ipa`.

Locally on a Mac (Xcode 16+, JDK 17):

```sh
./gradlew -p shared jvmTest assembleNuvioSharedReleaseXCFramework
brew install xcodegen
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
