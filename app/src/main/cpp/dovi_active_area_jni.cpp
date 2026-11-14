// Reads the Dolby Vision L5 active area (the letterbox the picture was mastered with) from an RPU.

#include <jni.h>
#include <cstdint>
#include <vector>

#ifndef DOVI_REAL_LINKED
#define DOVI_REAL_LINKED 0
#endif

#if DOVI_REAL_LINKED
#include <libdovi/rpu_parser.h>

namespace {

bool hasError(const DoviRpuOpaque* rpu) {
    const char* error = dovi_rpu_get_error(rpu);
    return error != nullptr && error[0] != '\0';
}

DoviRpuOpaque* parseRpu(const std::vector<uint8_t>& buf) {
    DoviRpuOpaque* rpu = dovi_parse_unspec62_nalu(buf.data(), buf.size());
    if (rpu != nullptr && !hasError(rpu)) return rpu;
    if (rpu != nullptr) dovi_rpu_free(rpu);
    rpu = dovi_parse_rpu(buf.data(), buf.size());
    if (rpu != nullptr && !hasError(rpu)) return rpu;
    if (rpu != nullptr) dovi_rpu_free(rpu);
    return nullptr;
}

}  // namespace
#endif

/**
 * Fills [out] with the L5 offsets {left, right, top, bottom} in pixels and returns true when the
 * RPU in data[offset, offset + length) carries an L5 block.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_tv_core_player_DoviBridge_nativeReadActiveArea(
    JNIEnv* env,
    jclass /* clazz */,
    jbyteArray data,
    jint offset,
    jint length,
    jintArray out) {
#if DOVI_REAL_LINKED
    if (data == nullptr || out == nullptr || offset < 0 || length <= 0) return JNI_FALSE;
    if (env->GetArrayLength(data) < offset + length || env->GetArrayLength(out) < 4) return JNI_FALSE;
    std::vector<uint8_t> buf(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, offset, length, reinterpret_cast<jbyte*>(buf.data()));

    DoviRpuOpaque* rpu = parseRpu(buf);
    if (rpu == nullptr) return JNI_FALSE;
    bool found = false;
    jint offsets[4] = {0, 0, 0, 0};
    const DoviVdrDmData* dm = dovi_rpu_get_vdr_dm_data(rpu);
    if (dm != nullptr) {
        const DoviExtMetadataBlockLevel5* level5 = dm->dm_data.level5;
        if (level5 != nullptr) {
            offsets[0] = level5->active_area_left_offset;
            offsets[1] = level5->active_area_right_offset;
            offsets[2] = level5->active_area_top_offset;
            offsets[3] = level5->active_area_bottom_offset;
            found = true;
        }
        dovi_rpu_free_vdr_dm_data(dm);
    }
    dovi_rpu_free(rpu);
    if (found) env->SetIntArrayRegion(out, 0, 4, offsets);
    return found ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}
