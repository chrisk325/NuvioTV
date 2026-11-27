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

- **Laskco's libass fork** ([mpvNova-libass](https://github.com/Laskco/mpvNova-libass) 0.17.5-laskco-6a7c6c9,
  in place of edde746's 0.18.4), in its performance mode (`LIBASS_EXPERIMENTAL_EDGES=1`). Upstream
  measured heavy typesetting at about half the time per frame on an armeabi-v7a TV.
- the `sans-serif` fontconfig fallback (Roboto, then Noto Sans, then Droid Sans; 34b11d6).
- the `nativeCpuBlend*` natives that `AssCpuBlendSurfaceView` needs, which upstream's v0.0.14 AAR lacks.

### libass 6a7c6c9 (from 0e8b5ee)

Two fork commits, built the same way (`scripts/libass/build-android.sh`, submodule at
6a7c6c98d713cdd07a8b5efdfc08c67b1c7d2841, NDK r29), with `LIBASS_VERSION "0.17.5-laskco-6a7c6c9"` and
`LIBASS_SHA256 "8fcb560d5893a1ba6d1c4cde942554e0593c286cec3f718ac6c05082099104dd"`. nuvio-libass has
no release for it yet. The commit after it, 2598476, only changes the fork's README.

- **6a7c6c9** trims the bitmap, composite and outline caches by age, not only by size: an entry no
  frame has used for 120 frames (about 5 s at 24 fps) goes once its cache is above a warm floor
  (16 MB of bitmaps, 8 MB of composites, 2048 outlines, divided among the render threads).
- **5583460**, its parent, caches transparent stretches of events and the shadowless base of blurred
  shadowed composites, so a shadow that moves reuses it; tightens vector-clipped composites; and, in
  performance mode, skips images at 1/255 alpha (the last frame of a fade).

Measured as for 0e8b5ee below, plus a 2-minute episode-style track (karaoke that fades in from
transparent, static signs with shadows and fades that recur, dialogue, a frame-by-frame burst):

| Track | Resident memory (performance mode, 4 threads) | CPU instructions, performance / exact |
| --- | --- | ---: |
| episode | peak 76 -> 47 MB, at the end 76 -> 44 MB | -8.6% / -8.1% |
| mix | peak 245 -> 216 MB, after the heavy part 197 -> 173 MB | +2.5% / +5.3% |
| karaoke | | +4.5% / +4.3% |
| signs | | +1.2% / +1.0% |

The extra work on the generated FX tracks, where every frame is new, is mostly 5583460 copying
composites from their shadowless base, which pays off only when the base is reused; 6a7c6c9's own
cost is re-rendering what it evicted, 0.4% to 1.1%. Exact mode renders every frame bit for bit as
0e8b5ee did, on the host and in the arm64 and armeabi-v7a packages under qemu. In performance mode
the frames that change are the last frames of fades and the first frame of some transforms; on
the karaoke and episode tracks no pixel moves by more than 9/255. The upstream regression, fuzz
and checkasm tests, and the four tracks with 4 threads in modes 0, 1 and 2, pass under ASan and
UBSan. JNI exports, linked libraries and page alignment are unchanged.

### libass 0e8b5ee (from b620927)

The fork's four commits after b620927 (fd53bbe, 95f8e7b, ead772d, 0e8b5ee) add exact-mode shaping
and font caches, a cache of pre-simplified outlines for performance mode, a moving vector-clip guard,
and faster vector clips and small blurs. nuvio-libass has no `libass-0.17.5-laskco-0e8b5ee` release
yet, so the package was built locally with its `scripts/libass/build-android.sh` (submodule at
0e8b5ee, same pinned wraps, NDK r29) and `libasskt.so` from the same merge as before, with
`LIBASS_VERSION "0.17.5-laskco-0e8b5ee"` and
`LIBASS_SHA256 "dd19a2eaf23965495f92c1b5da5b843fa4a9e7c3fde6feb18946a6853ef23943"`. Rebuilding
b620927 the same way reproduces the previous `libasskt.so` byte for byte apart from its build ID.
The JNI exports are unchanged, so `classes.jar` and the patches below are untouched.

Measured with the fork's `profile/fle-bench.c` on generated heavy typesetting (karaoke FX with
layered blurs and frame-by-frame particles; signs with vector, moving and animated clips and large
blurred shapes; both mixed), single-threaded, in CPU instructions (valgrind), with the new vector-clip
scan's NEON path ported to SSE2 so the x86 host does the work ARM does:

| Track | Performance mode | Exact mode |
| --- | ---: | ---: |
| karaoke | -19.2% | -13.0% |
| signs | -9.4% | -11.5% |
| mix | -14.5% | -12.4% |

Exact mode renders every frame bit for bit as b620927 did, on the host and in the arm64 package
under qemu. Performance mode moves some edge antialiasing by a step (0.5% of pixels on the signs
track, not visible at 4x zoom). Peak memory is within -1% to +9%. The upstream regression, fuzz and
checkasm tests, and the three tracks with 4 threads in modes 0, 1 and 2, pass under ASan and UBSan.
On x86 and x86_64 (no NEON) the vector-clip scan runs bytewise, so tracks heavy in vector clips can
cost those ABIs more than before.

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
