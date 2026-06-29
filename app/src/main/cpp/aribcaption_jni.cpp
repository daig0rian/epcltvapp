#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <cinttypes>
#include <cstring>
#include "aribcaption/aribcaption.hpp"

#define LOG_TAG "AribCaptionJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace aribcaption;

struct AribCaptionSession {
    Context ctx;
    Decoder decoder;
    Renderer renderer;

    explicit AribCaptionSession() : decoder(ctx), renderer(ctx) {}
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_daigorian_epcltvapp_AribCaptionFilter_nativeCreate(
        JNIEnv*, jclass, jint frameWidth, jint frameHeight, jint captionType)
{
    auto* session = new AribCaptionSession();

    session->ctx.SetLogcatCallback([](LogLevel level, const char* msg) {
        int prio = (level == LogLevel::kError)   ? ANDROID_LOG_ERROR :
                   (level == LogLevel::kWarning)  ? ANDROID_LOG_WARN  : ANDROID_LOG_DEBUG;
        __android_log_print(prio, "AribCaption", "%s", msg);
    });

    CaptionType ct = (captionType == 1) ? CaptionType::kSuperimpose : CaptionType::kCaption;

    if (!session->decoder.Initialize(EncodingScheme::kAuto, ct)) {
        LOGE("Decoder::Initialize failed (type=%d)", captionType);
        delete session;
        return 0;
    }

    if (!session->renderer.Initialize(ct,
                                      FontProviderType::kAuto,
                                      TextRendererType::kAuto)) {
        LOGE("Renderer::Initialize failed (type=%d)", captionType);
        delete session;
        return 0;
    }

    session->renderer.SetFrameSize(frameWidth, frameHeight);
    LOGI("created session frame=%dx%d type=%d", frameWidth, frameHeight, captionType);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_daigorian_epcltvapp_AribCaptionFilter_nativeSetFrameSize(
        JNIEnv*, jclass, jlong handle, jint w, jint h)
{
    if (handle == 0) return;
    reinterpret_cast<AribCaptionSession*>(handle)->renderer.SetFrameSize(w, h);
    LOGI("setFrameSize %dx%d", w, h);
}

JNIEXPORT jboolean JNICALL
Java_com_daigorian_epcltvapp_AribCaptionFilter_nativeDecode(
        JNIEnv* env, jclass, jlong handle, jlong ptsMs,
        jbyteArray pesPayload, jint offset, jint len)
{
    if (handle == 0) return JNI_FALSE;
    auto* session = reinterpret_cast<AribCaptionSession*>(handle);

    jbyte* data = env->GetByteArrayElements(pesPayload, nullptr);
    if (!data) return JNI_FALSE;

    DecodeResult result;
    DecodeStatus status = session->decoder.Decode(
            reinterpret_cast<const uint8_t*>(data + offset),
            static_cast<size_t>(len),
            ptsMs,
            result);

    env->ReleaseByteArrayElements(pesPayload, data, JNI_ABORT);

    if (status == DecodeStatus::kGotCaption && result.caption) {
        session->renderer.AppendCaption(std::move(*result.caption));
        LOGI("decoded caption pts=%" PRId64 "ms", ptsMs);
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_daigorian_epcltvapp_AribCaptionFilter_nativeRender(
        JNIEnv* env, jclass, jlong handle, jlong ptsMs)
{
    jclass captionImageClass = env->FindClass(
            "com/daigorian/epcltvapp/CaptionImage");
    jmethodID ctor = env->GetMethodID(captionImageClass, "<init>",
            "(Landroid/graphics/Bitmap;IIJ)V");

    if (handle == 0) {
        return env->NewObjectArray(0, captionImageClass, nullptr);
    }
    auto* session = reinterpret_cast<AribCaptionSession*>(handle);

    RenderResult render_result;
    RenderStatus status = session->renderer.Render(ptsMs, render_result);

    if (status != RenderStatus::kGotImage && status != RenderStatus::kGotImageUnchanged) {
        return env->NewObjectArray(0, captionImageClass, nullptr);
    }

    const auto& images = render_result.images;
    jlong durationMs = (render_result.duration == DURATION_INDEFINITE || render_result.duration <= 0)
                       ? 3000L
                       : render_result.duration;

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmap = env->GetStaticMethodID(bitmapClass, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888",
            "Landroid/graphics/Bitmap$Config;");
    jobject argb8888Config = env->GetStaticObjectField(configClass, argb8888Field);

    jobjectArray arr = env->NewObjectArray(
            static_cast<jsize>(images.size()), captionImageClass, nullptr);

    for (size_t i = 0; i < images.size(); i++) {
        const Image& img = images[i];

        jobject bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmap,
                static_cast<jint>(img.width), static_cast<jint>(img.height), argb8888Config);

        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS) {
            AndroidBitmapInfo info;
            AndroidBitmap_getInfo(env, bitmap, &info);
            const uint8_t* src = img.bitmap.data();
            uint8_t* dst = static_cast<uint8_t*>(pixels);
            int rowBytes = img.width * 4;
            for (int row = 0; row < img.height; row++) {
                memcpy(dst + row * static_cast<int>(info.stride),
                       src + row * img.stride,
                       rowBytes);
            }
            AndroidBitmap_unlockPixels(env, bitmap);
        }

        jobject captionImage = env->NewObject(captionImageClass, ctor,
                bitmap,
                static_cast<jint>(img.dst_x),
                static_cast<jint>(img.dst_y),
                durationMs);
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), captionImage);
        env->DeleteLocalRef(bitmap);
        env->DeleteLocalRef(captionImage);
    }

    return arr;
}

JNIEXPORT void JNICALL
Java_com_daigorian_epcltvapp_AribCaptionFilter_nativeFlush(
        JNIEnv*, jclass, jlong handle)
{
    if (handle == 0) return;
    auto* session = reinterpret_cast<AribCaptionSession*>(handle);
    session->decoder.Flush();
    session->renderer.Flush();
    LOGI("flushed");
}

JNIEXPORT void JNICALL
Java_com_daigorian_epcltvapp_AribCaptionFilter_nativeDestroy(
        JNIEnv*, jclass, jlong handle)
{
    if (handle == 0) return;
    delete reinterpret_cast<AribCaptionSession*>(handle);
    LOGI("session destroyed");
}

} // extern "C"
