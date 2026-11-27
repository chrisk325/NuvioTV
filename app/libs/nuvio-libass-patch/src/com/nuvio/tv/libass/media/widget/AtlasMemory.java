package com.nuvio.tv.libass.media.widget;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Native memory for the Overlay pipeline's atlas and vertex buffers, in place of
 * ByteBuffer.allocateDirect, which ART keeps on the Java heap. The Overlay atlas was 16 MB a page,
 * up to four pages a slot and three slots: up to 192 MB of Java heap, never given back while
 * subtitles were on. Media3's buffer is sized from that same heap (85% of it on a large heap), so
 * on heavy typesetting the two did not fit, garbage collection ran without end and the stream
 * stopped buffering ahead until subtitles were turned off.
 *
 * Buffers are anonymous mappings made by the app's libnuvio_ass_gpu (subtitles/atlas_memory.c):
 * outside the Java heap, zero-filled, and backed by memory only where libass writes. Each slot gets
 * its full four pages up front, so it is never reallocated, and {@link #release} hands back the pages
 * it no longer uses. A mapping is never unmapped: a buffer a playback is done with returns to a pool
 * the next playback takes from, with all its pages handed back first, so a late read of it sees
 * zeros instead of faulting and the address space they take stays bounded.
 *
 * Without the native library {@link #take} returns null and the pipeline keeps its Java heap buffers.
 */
public final class AtlasMemory {
    private static final boolean AVAILABLE = load();
    private static final Map<Integer, ArrayDeque<ByteBuffer>> POOL = new HashMap<>();
    private static final Set<ByteBuffer> OWNED = Collections.newSetFromMap(new IdentityHashMap<>());

    private AtlasMemory() {}

    private static boolean load() {
        try {
            System.loadLibrary("nuvio_ass_gpu");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** A native buffer of exactly {@code bytes}, in native byte order, or null to use the Java heap. */
    public static ByteBuffer take(int bytes) {
        if (!AVAILABLE || bytes <= 0) return null;
        synchronized (POOL) {
            ArrayDeque<ByteBuffer> pooled = POOL.get(bytes);
            ByteBuffer reused = pooled == null ? null : pooled.pollFirst();
            if (reused != null) {
                reused.clear();
                return reused.order(ByteOrder.nativeOrder());
            }
        }
        ByteBuffer mapped;
        try {
            mapped = nativeMap(bytes);
        } catch (Throwable t) {
            return null;
        }
        if (mapped == null) return null;
        synchronized (POOL) {
            OWNED.add(mapped);
        }
        return mapped.order(ByteOrder.nativeOrder());
    }

    /** True for buffers {@link #take} made. */
    public static boolean isNative(ByteBuffer buffer) {
        synchronized (POOL) {
            return OWNED.contains(buffer);
        }
    }

    /** Hands back the memory of {@code buffer} past its first {@code keepBytes}; the buffer stays usable. */
    public static void release(ByteBuffer buffer, long keepBytes) {
        if (!isNative(buffer)) return;
        try {
            nativeRelease(buffer, keepBytes);
        } catch (Throwable ignored) {
            // The buffer keeps its memory; nothing else changes.
        }
    }

    /** Returns a buffer {@link #take} made, with all its memory handed back, for a later take. */
    public static void give(ByteBuffer buffer) {
        if (buffer == null || !isNative(buffer)) return;
        release(buffer, 0);
        synchronized (POOL) {
            ArrayDeque<ByteBuffer> pooled = POOL.get(buffer.capacity());
            if (pooled == null) {
                pooled = new ArrayDeque<>();
                POOL.put(buffer.capacity(), pooled);
            }
            for (ByteBuffer existing : pooled) {
                if (existing == buffer) return;
            }
            pooled.addLast(buffer);
        }
    }

    private static native ByteBuffer nativeMap(int bytes);

    private static native boolean nativeRelease(ByteBuffer buffer, long keepBytes);
}
