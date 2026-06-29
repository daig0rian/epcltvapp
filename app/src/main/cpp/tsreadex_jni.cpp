#include <jni.h>
#include <android/log.h>
#include <cstring>
#include "tsreadex/servicefilter.hpp"

#define LOG_TAG "TsReadexJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const int TS_PACKET_SIZE = 188;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_daigorian_epcltvapp_TsReadexFilter_nativeCreate(
        JNIEnv *env, jclass clazz,
        jint programNumberOrIndex, jint audio1Mode, jint audio2Mode, jint captionMode, jint superimposeMode)
{
    auto *filter = new CServiceFilter();
    filter->SetProgramNumberOrIndex(programNumberOrIndex);
    filter->SetAudio1Mode(audio1Mode);
    filter->SetAudio2Mode(audio2Mode);
    filter->SetCaptionMode(captionMode);
    filter->SetSuperimposeMode(superimposeMode);
    LOGI("created filter: prog=%d audio1=%d audio2=%d caption=%d superimpose=%d", programNumberOrIndex, audio1Mode, audio2Mode, captionMode, superimposeMode);
    return reinterpret_cast<jlong>(filter);
}

JNIEXPORT jbyteArray JNICALL
Java_com_daigorian_epcltvapp_TsReadexFilter_nativeProcessPackets(
        JNIEnv *env, jclass clazz,
        jlong handle, jbyteArray inputArray, jint inputLen)
{
    if (handle == 0) {
        LOGE("processPackets called with null handle");
        return env->NewByteArray(0);
    }

    auto *filter = reinterpret_cast<CServiceFilter *>(handle);

    jbyte *input = env->GetByteArrayElements(inputArray, nullptr);
    if (!input) return env->NewByteArray(0);

    filter->ClearPackets();

    int packetCount = inputLen / TS_PACKET_SIZE;
    for (int i = 0; i < packetCount; i++) {
        const uint8_t *packet = reinterpret_cast<const uint8_t *>(input) + i * TS_PACKET_SIZE;
        if (packet[0] != 0x47) {
            LOGE("bad sync byte at packet %d: 0x%02X", i, packet[0]);
            continue;
        }
        filter->AddPacket(packet);
    }

    env->ReleaseByteArrayElements(inputArray, input, JNI_ABORT);

    const auto &out = filter->GetPackets();
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    if (!out.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()),
                                reinterpret_cast<const jbyte *>(out.data()));
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_daigorian_epcltvapp_TsReadexFilter_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle)
{
    if (handle == 0) return;
    delete reinterpret_cast<CServiceFilter *>(handle);
    LOGI("filter destroyed");
}

} // extern "C"
