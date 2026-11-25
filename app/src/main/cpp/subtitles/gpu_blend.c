// The Overlay (GPU Blend) render mode: the AssHandler's libass renders each video frame's moment, whose
// images become a picture of masks copied into a store (libass keeps its own only until it renders
// again), and the GPU blends them over each other as tinted quads into a surface of its own, which the
// compositor lays over the video as a single layer. Each buffer carries the time its video frame shows
// at, so both show together. Where GL can't start, the masks are blended on the CPU into the window's
// buffers instead, as the CPU Blend mode does.
//
// One thread owns a handle: the view's worker, which also has its EGL context current.

#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "../ass/ass.h"
#include "blend.h"
#include "gl_masks.h"
#include "masks.h"

#define TAG "AssGpuBlend"

// libass as nuvio-libass carries it, loaded by its Ass class before any renderer exists.
static __typeof__(ass_render_frame) *render_frame;
static __typeof__(ass_set_frame_size) *set_frame_size;
static __typeof__(ass_set_storage_size) *set_storage_size;

static int load_libass(void) {
    if (render_frame) return 1;
    void *library = dlopen("libasskt.so", RTLD_NOW);
    if (!library) return 0;
    set_frame_size = (__typeof__(ass_set_frame_size) *) dlsym(library, "ass_set_frame_size");
    set_storage_size = (__typeof__(ass_set_storage_size) *) dlsym(library, "ass_set_storage_size");
    if (!set_frame_size || !set_storage_size) return 0;
    render_frame = (__typeof__(ass_render_frame) *) dlsym(library, "ass_render_frame");
    return render_frame != NULL;
}

typedef struct {
    ANativeWindow *window;
    int width;
    int height;
    int small;          // a device short of memory: a smaller atlas, fewer masks kept
    MaskStore *masks;
    Picture *shown;
    // What the picture holds images on, which the next one has to clear.
    ARect drawn;
    int must_draw;      // a new window, which has nothing on it yet
    ANativeWindow_Buffer buffer;

    EGLDisplay display;
    EGLContext context;
    EGLSurface surface;
    PFNEGLPRESENTATIONTIMEANDROIDPROC presentation_time;
    GlMasks *masks_gl;
} Surface;

static int empty(const ARect *rect) { return rect->right <= rect->left || rect->bottom <= rect->top; }

static void include(ARect *rect, int left, int top, int right, int bottom) {
    if (empty(rect)) {
        *rect = (ARect) {left, top, right, bottom};
        return;
    }
    if (left < rect->left) rect->left = left;
    if (top < rect->top) rect->top = top;
    if (right > rect->right) rect->right = right;
    if (bottom > rect->bottom) rect->bottom = bottom;
}

static void stop_gl(Surface *surface) {
    if (surface->display == EGL_NO_DISPLAY) return;
    if (surface->context != EGL_NO_CONTEXT && eglGetCurrentContext() == surface->context) {
        gl_masks_free(surface->masks_gl);
    }
    eglMakeCurrent(surface->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (surface->surface != EGL_NO_SURFACE) eglDestroySurface(surface->display, surface->surface);
    if (surface->context != EGL_NO_CONTEXT) eglDestroyContext(surface->display, surface->context);
    // The display stays initialised, as the app's own drawing uses it.
    surface->display = EGL_NO_DISPLAY;
    surface->surface = EGL_NO_SURFACE;
    surface->context = EGL_NO_CONTEXT;
    surface->presentation_time = NULL;
    surface->masks_gl = NULL;
}

static int start_gl(Surface *surface, int width, int height) {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, NULL, NULL)) return 0;
    surface->display = display;
    const EGLint attributes[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE,
    };
    const EGLint version[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLConfig config;
    EGLint configs = 0;
    if (!eglChooseConfig(display, attributes, &config, 1, &configs) || configs < 1) return stop_gl(surface), 0;
    surface->context = eglCreateContext(display, config, EGL_NO_CONTEXT, version);
    if (surface->context == EGL_NO_CONTEXT) return stop_gl(surface), 0;
    ANativeWindow_setBuffersGeometry(surface->window, width, height, WINDOW_FORMAT_RGBA_8888);
    surface->surface = eglCreateWindowSurface(display, config, surface->window, NULL);
    if (surface->surface == EGL_NO_SURFACE ||
        !eglMakeCurrent(display, surface->surface, surface->surface, surface->context)) {
        return stop_gl(surface), 0;
    }
    const char *extensions = eglQueryString(display, EGL_EXTENSIONS);
    if (extensions && strstr(extensions, "EGL_ANDROID_presentation_time")) {
        surface->presentation_time = (PFNEGLPRESENTATIONTIMEANDROIDPROC) eglGetProcAddress("eglPresentationTimeANDROID");
    }
    glDisable(GL_DITHER);
    surface->masks_gl = gl_masks_new(surface->masks, surface->small);
    if (!surface->masks_gl || glGetError() != GL_NO_ERROR) return stop_gl(surface), 0;
    return 1;
}

#define METHOD(name) Java_com_nuvio_tv_core_player_libass_AssGpuBlendNative_##name

// Zero when nuvio-libass's libass can't be found.
JNIEXPORT jlong JNICALL METHOD(create)(JNIEnv *env, jclass clazz, jboolean small) {
    if (!load_libass()) return 0;
    Surface *surface = calloc(1, sizeof(Surface));
    if (!surface) return 0;
    surface->masks = masks_new();
    if (!surface->masks) {
        free(surface);
        return 0;
    }
    surface->small = small;
    surface->display = EGL_NO_DISPLAY;
    surface->context = EGL_NO_CONTEXT;
    surface->surface = EGL_NO_SURFACE;
    return (jlong) surface;
}

// The window to draw into, `width` by `height` buffers as libass's frame is; null to let go of it.
// False when the GPU can't draw into it, and the CPU blends the masks into its buffers instead.
JNIEXPORT jboolean JNICALL METHOD(setWindow)(
        JNIEnv *env, jclass clazz, jlong handle, jobject view, jint width, jint height) {
    Surface *surface = (Surface *) handle;
    stop_gl(surface);
    if (surface->window) ANativeWindow_release(surface->window);
    surface->window = view ? ANativeWindow_fromSurface(env, view) : NULL;
    surface->drawn = (ARect) {0, 0, 0, 0};
    if (!surface->window) return JNI_FALSE;
    surface->width = width;
    surface->height = height;
    surface->must_draw = 1;
    if (start_gl(surface, width, height)) return JNI_TRUE;
    __android_log_print(ANDROID_LOG_WARN, TAG, "no GL for subtitles: blending them on the CPU");
    ANativeWindow_setBuffersGeometry(surface->window, width, height, WINDOW_FORMAT_RGBA_8888);
    return JNI_FALSE;
}

// Without GL: blends into the window's next buffer, clearing only what changed.
static int draw_window(Surface *surface, const Picture *picture, ARect next) {
    ARect dirty = surface->drawn;
    if (!empty(&next)) include(&dirty, next.left, next.top, next.right, next.bottom);
    if (empty(&dirty)) dirty = (ARect) {0, 0, surface->width, surface->height};
    // The window copies the rest from the buffer before, and may widen the rectangle.
    if (ANativeWindow_lock(surface->window, &surface->buffer, &dirty) != 0) return 0;
    ANativeWindow_Buffer *buffer = &surface->buffer;
    if (dirty.left < 0) dirty.left = 0;
    if (dirty.top < 0) dirty.top = 0;
    if (dirty.right > buffer->width) dirty.right = buffer->width;
    if (dirty.bottom > buffer->height) dirty.bottom = buffer->height;
    uint32_t *bits = buffer->bits;
    for (int y = dirty.top; y < dirty.bottom; y++) {
        memset(bits + y * buffer->stride + dirty.left, 0, (dirty.right - dirty.left) * 4);
    }
    for (int i = 0; picture && i < picture->count; i++) {
        const Placed *placed = &picture->placed[i];
        if (placed->x >= 0 && placed->y >= 0 &&
            placed->x + placed->mask->w <= buffer->width && placed->y + placed->mask->h <= buffer->height) {
            blend(placed, bits, buffer->stride, 0, 0);
        }
    }
    ANativeWindow_unlockAndPost(surface->window);
    return 1;
}

static long long now_nanos(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return now.tv_sec * 1000000000LL + now.tv_nsec;
}

// Renders `track` at `timeMs` with `renderer`, both the AssHandler's and held under its lock, into a
// picture of masks copied out of libass, so drawing it needs no lock. 1 when the picture changed, 0
// when it didn't, -1 when it couldn't be made. `videoWidth` by `videoHeight` is the video's storage
// size, or 0 when it isn't known yet.
JNIEXPORT jint JNICALL METHOD(render)(
        JNIEnv *env, jclass clazz, jlong handle, jlong renderer, jlong track, jlong timeMs,
        jint videoWidth, jint videoHeight) {
    Surface *surface = (Surface *) handle;
    int changed = 0;
    ASS_Image *images = NULL;
    if (renderer && surface->width > 0 && surface->height > 0) {
        // libass lays out on the frame these buffers draw, as the atlas overlay pins it: the
        // AssHandler's own frame size can still be the video surface's, which would place the
        // subtitles outside the video. Neither call does anything when the size is unchanged.
        set_frame_size((ASS_Renderer *) renderer, surface->width, surface->height);
        if (videoWidth > 0 && videoHeight > 0) set_storage_size((ASS_Renderer *) renderer, videoWidth, videoHeight);
    }
    if (renderer && track) {
        images = render_frame((ASS_Renderer *) renderer, (ASS_Track *) track, timeMs, &changed);
    } else {
        // No track: what shows goes.
        changed = surface->shown && surface->shown->count;
    }
    if (!changed && surface->shown) return 0;
    Picture *picture = masks_picture(surface->masks, images);
    if (!picture) return -1;
    masks_release(surface->masks, surface->shown);
    surface->shown = picture;
    return 1;
}

// Draws the rendered picture and posts it for the compositor to show at `atNanos` (System.nanoTime's
// clock), or at once when 0. True when a picture was posted.
JNIEXPORT jboolean JNICALL METHOD(post)(JNIEnv *env, jclass clazz, jlong handle, jlong atNanos) {
    Surface *surface = (Surface *) handle;
    Picture *picture = surface->shown;
    if (!surface->window || !picture) return JNI_FALSE;
    ARect next = {0, 0, 0, 0};
    for (int i = 0; i < picture->count; i++) {
        const Placed *placed = &picture->placed[i];
        include(&next, placed->x, placed->y, placed->x + placed->mask->w, placed->y + placed->mask->h);
    }
    // Nothing shows, nor showed.
    if (empty(&next) && empty(&surface->drawn) && !surface->must_draw) return JNI_FALSE;

    if (surface->context == EGL_NO_CONTEXT) {
        if (!draw_window(surface, picture, next)) return JNI_FALSE;
    } else {
        gl_masks_draw(surface->masks_gl, picture, surface->width, surface->height);
        if (surface->presentation_time) {
            // The window keeps the last time it was given: a picture for no frame, or one far off, shows now.
            long long now = now_nanos();
            if (atNanos <= 0 || atNanos > now + 1000000000LL) atNanos = now;
            surface->presentation_time(surface->display, surface->surface, atNanos);
        }
        if (!eglSwapBuffers(surface->display, surface->surface)) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "eglSwapBuffers failed: 0x%x", eglGetError());
            return JNI_FALSE;
        }
    }
    surface->drawn = next;
    surface->must_draw = 0;
    return JNI_TRUE;
}

// Whether a posted picture carries the time its video frame shows at.
JNIEXPORT jboolean JNICALL METHOD(stamped)(JNIEnv *env, jclass clazz, jlong handle) {
    return ((Surface *) handle)->presentation_time != NULL;
}

// How many threads `renderer` renders with, from its next frame: nuvio-libass's libass is the threaded
// build, and asks for one per core. The count it took, or 0 when this libass has no threads.
JNIEXPORT jint JNICALL METHOD(setThreads)(JNIEnv *env, jclass clazz, jlong renderer, jint threads) {
    static int (*set_threads)(ASS_Renderer *, int);
    if (!set_threads) {
        void *library = dlopen("libasskt.so", RTLD_NOW);
        if (library) set_threads = (int (*)(ASS_Renderer *, int)) dlsym(library, "ass_set_threads");
    }
    if (!set_threads || !renderer || threads < 1) return 0;
    return set_threads((ASS_Renderer *) renderer, threads);
}

// Once the worker no longer uses it.
JNIEXPORT void JNICALL METHOD(release)(JNIEnv *env, jclass clazz, jlong handle) {
    Surface *surface = (Surface *) handle;
    stop_gl(surface);
    if (surface->window) ANativeWindow_release(surface->window);
    masks_release(surface->masks, surface->shown);
    masks_free(surface->masks);
    free(surface);
}
