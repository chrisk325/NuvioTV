// Native memory for nuvio-libass's Overlay atlas (com.nuvio.tv.libass.media.widget.AtlasMemory).
//
// The Overlay pipeline's atlas buffers were ByteBuffer.allocateDirect, which ART keeps on the Java
// heap: 16 MB a page, up to four pages a slot, three slots. These are anonymous mappings instead:
// outside the Java heap, zero-filled, and backed by memory only where libass has written. Pages can
// be handed back with MADV_DONTNEED while the mapping stays valid, so a late read of a buffer whose
// pages were handed back sees zeros instead of faulting. The Java side never unmaps them; it pools
// and reuses them.
#include <jni.h>
#include <stdint.h>
#include <sys/mman.h>
#include <unistd.h>

JNIEXPORT jobject JNICALL
Java_com_nuvio_tv_libass_media_widget_AtlasMemory_nativeMap(JNIEnv* env, jclass clazz, jint bytes) {
    (void)clazz;
    if (bytes <= 0) return NULL;
    void* base = mmap(NULL, (size_t)bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (base == MAP_FAILED) return NULL;
    jobject buffer = (*env)->NewDirectByteBuffer(env, base, (jlong)bytes);
    if (buffer == NULL) {
        munmap(base, (size_t)bytes);
        return NULL;
    }
    return buffer;
}

// Hands back the pages of `buffer` from `keepBytes` (rounded up to a page) to its end. The bytes
// before `keepBytes` are kept as they are; the rest read as zeros until written again.
JNIEXPORT jboolean JNICALL
Java_com_nuvio_tv_libass_media_widget_AtlasMemory_nativeRelease(
    JNIEnv* env, jclass clazz, jobject buffer, jlong keepBytes) {
    (void)clazz;
    uint8_t* base = (uint8_t*)(*env)->GetDirectBufferAddress(env, buffer);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    if (base == NULL || capacity <= 0 || keepBytes < 0) return JNI_FALSE;
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) return JNI_FALSE;
    jlong start = (keepBytes + page - 1) / page * page;
    if (start >= capacity) return JNI_TRUE;
    return madvise(base + start, (size_t)(capacity - start), MADV_DONTNEED) == 0 ? JNI_TRUE : JNI_FALSE;
}
