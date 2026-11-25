# nuvio-libass patches

`nuvio-libass-release.aar` is a prebuilt library. These are classes rebuilt from its decompiled
bytecode with fixes, which `build.sh` compiles and swaps into the AAR's `classes.jar`. Nothing else
in the AAR changes: the other classes and every `libasskt.so` stay byte for byte as they were.

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

## Rebuilding

```
app/libs/nuvio-libass-patch/build.sh
```

If a new nuvio-libass AAR comes in, check its `AssCpuBlendSurfaceView` before running this script:
the patch replaces that class whole.
