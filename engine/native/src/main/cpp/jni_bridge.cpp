// JNI surface for app.rescale.engine.nativebridge.NativeEngine.
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <memory>
#include <string>

#include "core/engine.h"
#include "core/sinks.h"
#include "ncnn_runner.h"

#define LOG_TAG "RescaleNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace rescale;

namespace {

struct Handle {
    std::unique_ptr<NcnnRunner> runner;
    std::atomic<bool> cancel{false};
};

std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

Handle* from(jlong h) { return reinterpret_cast<Handle*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeGpuCount(JNIEnv*, jobject) {
    return ensureGpuInstance();
}

JNIEXPORT jlong JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeCreate(JNIEnv* env, jobject, jstring param, jstring bin,
                                                               jstring inBlob, jstring outBlob, jint scale,
                                                               jboolean gpu, jint threads) {
    auto h = std::make_unique<Handle>();
    h->runner = std::make_unique<NcnnRunner>(scale, gpu, threads, str(env, inBlob), str(env, outBlob));
    if (!h->runner->load(str(env, param), str(env, bin))) {
        LOGE("failed to load model %s", str(env, param).c_str());
        return 0;
    }
    LOGI("model loaded, gpu=%d", h->runner->usingGpu());
    return reinterpret_cast<jlong>(h.release());
}

JNIEXPORT jboolean JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeUsingGpu(JNIEnv*, jobject, jlong handle) {
    return handle && from(handle)->runner->usingGpu();
}

JNIEXPORT void JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete from(handle);
}

JNIEXPORT void JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeCancel(JNIEnv*, jobject, jlong handle) {
    if (handle) from(handle)->cancel = true;
}

// Runs a fixed, deterministic 24x24 tile and returns the raw output. Used by the startup
// self-test to compare GPU against CPU (catches drivers that return black/garbage).
JNIEXPORT jfloatArray JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeProbe(JNIEnv* env, jobject, jlong handle) {
    if (!handle) return nullptr;
    const int n = 24;
    std::vector<float> in(3 * n * n), out;
    uint32_t seed = 12345;
    for (float& v : in) {
        seed = seed * 1664525u + 1013904223u;
        v = static_cast<float>((seed >> 8) & 0xFFFF) / 65535.0f;
    }
    if (!from(handle)->runner->run(in, n, n, out)) return nullptr;
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(out.size()));
    env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

JNIEXPORT jint JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeUpscale(JNIEnv* env, jobject, jlong handle, jobject bitmap,
                                                                jint orientation, jint tile, jint downsample,
                                                                jfloat detail, jboolean lumaOnly, jboolean colorFix,
                                                                jlong maxBandBytes, jstring outPath,
                                                                jstring previewPath, jint previewMax,
                                                                jobject listener) {
    if (!handle) return kModelError;
    Handle* h = from(handle);
    h->cancel = false;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return kBadInput;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return kBadInput;

    Status st;
    {
        Image img(static_cast<const uint8_t*>(pixels), static_cast<int>(info.width), static_cast<int>(info.height),
                  static_cast<int>(info.stride), orientation);
        const int s = h->runner->scale();
        const int ds = std::max(1, static_cast<int>(downsample));
        const int C = outputChannels(img);
        const int OW = img.width() * s, OH = img.height() * s;

        EngineOptions opt;
        opt.tile = std::max(32, static_cast<int>(tile));
        opt.post.detail = detail;
        opt.post.lumaOnly = lumaOnly;
        opt.post.colorFix = colorFix;
        // Band accumulator is OW * tile*s rows * 3 floats. Shrink the tile until it fits.
        while (opt.tile > 32 && static_cast<long long>(OW) * opt.tile * s * 12 > maxBandBytes) opt.tile -= 16;

        BoxDownsample tmpProbe(OW, OH, C, ds, nullptr);  // only for output size
        const int FW = tmpProbe.outWidth(), FH = tmpProbe.outHeight();

        PngWriter main(str(env, outPath), FW, FH, C);
        if (!main.ok()) {
            AndroidBitmap_unlockPixels(env, bitmap);
            return kWriteError;
        }
        const std::string prevPath = str(env, previewPath);
        const int pmax = std::max(1, static_cast<int>(previewMax));
        const int pk = std::max(1, (std::max(FW, FH) + pmax - 1) / pmax);
        std::unique_ptr<PngWriter> prevPng;
        std::unique_ptr<BoxDownsample> prevDown;
        RowSink* previewSink = nullptr;
        if (!prevPath.empty()) {
            prevPng = std::make_unique<PngWriter>(prevPath, (FW + pk - 1) / pk, (FH + pk - 1) / pk, C, 3);
            prevDown = std::make_unique<BoxDownsample>(FW, FH, C, pk, prevPng.get());
            previewSink = prevDown.get();
        }
        Tee tee(&main, previewSink);
        std::unique_ptr<BoxDownsample> down;
        RowSink* sink = &tee;
        if (ds > 1) {
            down = std::make_unique<BoxDownsample>(OW, OH, C, ds, &tee);
            sink = down.get();
        }

        jmethodID onProgress = nullptr;
        if (listener) {
            jclass cls = env->GetObjectClass(listener);
            onProgress = env->GetMethodID(cls, "onProgress", "(II)V");
        }
        auto progress = [&](int done, int total) {
            if (onProgress) {
                env->CallVoidMethod(listener, onProgress, done, total);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    h->cancel = true;
                }
            }
        };
        LOGI("upscale %dx%d x%d /%d tile=%d gpu=%d", img.width(), img.height(), s, ds, opt.tile,
             h->runner->usingGpu());
        st = upscale(img, *h->runner, opt, *sink, progress, &h->cancel);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return st;
}

}  // extern "C"
