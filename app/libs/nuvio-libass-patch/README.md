# nuvio-libass patches

`nuvio-libass-release.aar` is a prebuilt library. These are classes rebuilt from its decompiled
bytecode with fixes, which `build.sh` compiles and swaps into the AAR's `classes.jar`, plus a
one-byte priority patch to two more classes (below). `build.sh` leaves everything else in the AAR,
including every `libasskt.so`, byte for byte as it is.

## Base AAR

The classes come from nuvio-libass `feature/cpu-blend-overlay` (8abf5d3) merged onto 3bada55,
which is why this AAR has `AssCpuBlendSurfaceView`, `replaceTrack` and `AssHandlerConfig.useCpuBlend`.
`AssFrameTimestamps.class` comes from nuvio-libass v0.0.14 (c9879b8: it loads `libasskt` before
`nativeInit`, so the GL thread no longer throws `UnsatisfiedLinkError`).

`libasskt.so` (all four ABIs) is built from nuvio-libass `main` at 3b1aab1 (v0.0.14) merged with
`feature/cpu-blend-overlay`, which merges without conflicts. NDK r29 (29.0.14206865), minSdk 21,
`c++_shared`, RelWithDebInfo, then stripped. So it has:

- **Laskco's libass fork** ([mpvNova-libass](https://github.com/Laskco/mpvNova-libass) 0.17.5-laskco-b620927,
  in place of edde746's 0.18.4), in its performance mode (`LIBASS_EXPERIMENTAL_EDGES=1`). Upstream
  measured heavy typesetting at about half the time per frame on an armeabi-v7a TV.
- the `sans-serif` fontconfig fallback (Roboto, then Noto Sans, then Droid Sans; 34b11d6).
- the `nativeCpuBlend*` natives that `AssCpuBlendSurfaceView` needs, which upstream's v0.0.14 AAR lacks.

The fork has no `ass_set_threads`. It runs its own event threads, at most 4 and never more than the
cores, and only on expensive frames, so the app no longer sets libass's thread count. The worker
priority patch below still applies: the fork's threads are pthreads started from the rendering
worker, and they take its priority.

## `AssCpuBlendSurfaceView` (Overlay (CPU Blend))

Same public API and the same `AssRender.nativeCpuBlend*` natives. The patched class fixes four things:

- **Double delay.** `AssHandler` already gives `videoFrameCallback` the subtitle time, with the
  subtitle delay, audio delay and calibration applied. The view applied the subtitle and audio
  delays a second time, so a delay you set moved subtitles twice as far.
- **Forced redraw at time 0.** `invalidateSubtitles()` with no new frame rendered time 0, so a layout
  or style change while paused showed the file's first subtitle, or none. It now shows the last frame's moment.
- **Render scale ignored.** Window buffers were full size while libass renders at
  `surface × renderScale`, so below 1:1 the subtitles were small in the top-left corner. Buffers are
  now libass's frame size, and the compositor scales them to the view.
- **Frame size not pinned.** `AssHandler.setOverlaySurfaceSize` returns early when the size matches
  its cache, and `onSurfaceSizeChanged` from the video surface changes that cache without telling the
  renderer. So libass could lay out on the video surface's size and put subtitles off the video. The
  view now pins libass's frame to its buffers and its storage to the video, as the atlas overlay
  pins its frame. It re-pins only when `AssRender.stateGeneration` moves.

The worker also starts at the default thread priority, not `THREAD_PRIORITY_DISPLAY`: see below.

## Worker priority (`AtlasLibassThread`, `AssVideoEffectAsyncProgram`)

libass's render threads start from the worker that renders and take that worker's priority. At `THREAD_PRIORITY_DISPLAY` they took the CPU the stream's loader
needs, and heavy .ass tracks made playback buffer. `patch_aar.py` changes the `bipush -4` that
`AssLibassThread` (Overlay) and `AssRenderGL` (Video Effect) are created with to `bipush 0`, the same
length, so nothing else in those classes moves. `AssSurfaceGlThread` keeps the display priority: it
only draws, and never runs libass. The app's own GPU Blend worker starts at the same priority
(`LibassWorker`).

## Rebuilding

```
app/libs/nuvio-libass-patch/build.sh
```

If a new nuvio-libass AAR comes in, check its `AssCpuBlendSurfaceView` before running this script:
the patch replaces that class whole. The priority patch fails rather than guess if a worker's
`new HandlerThread(name, priority)` can't be found once.
