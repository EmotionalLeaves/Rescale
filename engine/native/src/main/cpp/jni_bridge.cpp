// JNI surface for app.rescale.engine.nativebridge.NativeEngine.
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <atomic>
#include <memory>
#include <string>

#include "core/chain.h"
#include "core/transcode.h"
#include "core/engine.h"
#include "core/sinks.h"
#include "face_restorer.h"
#include "ncnn_runner.h"

#define LOG_TAG "RescaleNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace rescale;

namespace {

struct Handle {
    std::unique_ptr<TileRunner> runner;
    NcnnRunner* ncnn = nullptr;  // same object as runner when it's an ncnn model
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
    auto r = std::make_unique<NcnnRunner>(scale, gpu, threads, str(env, inBlob), str(env, outBlob));
    if (!r->load(str(env, param), str(env, bin))) {
        LOGE("failed to load model %s", str(env, param).c_str());
        return 0;
    }
    LOGI("model loaded, gpu=%d", r->usingGpu());
    h->ncnn = r.get();
    h->runner = std::move(r);
    return reinterpret_cast<jlong>(h.release());
}

JNIEXPORT jlong JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeCreateDetector(JNIEnv* env, jobject, jstring param, jstring bin,
                                                                       jboolean gpu, jint threads) {
    auto d = std::make_unique<FaceDetector>(gpu, threads);
    if (!d->load(str(env, param), str(env, bin))) return 0;
    return reinterpret_cast<jlong>(d.release());
}

JNIEXPORT void JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeDestroyDetector(JNIEnv*, jobject, jlong h) {
    delete reinterpret_cast<FaceDetector*>(h);
}

// Returns [count, largest face size in px, smallest eye distance in px] for Auto mode.
JNIEXPORT jfloatArray JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeDetectFaces(JNIEnv* env, jobject, jlong h, jobject bitmap,
                                                                    jint orientation) {
    if (!h) return nullptr;
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return nullptr;
    std::vector<Face> faces;
    {
        Image img(static_cast<const uint8_t*>(pixels), static_cast<int>(info.width), static_cast<int>(info.height),
                  static_cast<int>(info.stride), orientation);
        faces = reinterpret_cast<FaceDetector*>(h)->detect(img);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    float largest = 0, minEyes = 1e9f;
    for (const Face& f : faces) {
        largest = std::max(largest, std::max(f.box[2] - f.box[0], f.box[3] - f.box[1]));
        minEyes = std::min(minEyes, std::hypot(f.lm[2] - f.lm[0], f.lm[3] - f.lm[1]));
    }
    const float r[3] = {static_cast<float>(faces.size()), largest, faces.empty() ? 0.f : minEyes};
    jfloatArray arr = env->NewFloatArray(3);
    env->SetFloatArrayRegion(arr, 0, 3, r);
    return arr;
}

JNIEXPORT void JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeSetNormalization(JNIEnv*, jobject, jlong handle, jfloat mul,
                                                                         jfloat add) {
    if (handle && from(handle)->ncnn) from(handle)->ncnn->setNormalization(mul, add);
}

JNIEXPORT jlong JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeCreateBicubic(JNIEnv*, jobject, jint scale) {
    auto h = std::make_unique<Handle>();
    h->runner = std::make_unique<BicubicRunner>(scale);
    return reinterpret_cast<jlong>(h.release());
}

JNIEXPORT jboolean JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeUsingGpu(JNIEnv*, jobject, jlong handle) {
    return handle && from(handle)->ncnn && from(handle)->ncnn->usingGpu();
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

JNIEXPORT void JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeSetExtraInput(JNIEnv* env, jobject, jlong handle, jstring blob,
                                                                      jfloatArray values) {
    if (!handle) return;
    std::vector<float> v(env->GetArrayLength(values));
    env->GetFloatArrayRegion(values, 0, static_cast<jsize>(v.size()), v.data());
    if (from(handle)->ncnn) from(handle)->ncnn->setExtraInput(str(env, blob), std::move(v));
}

JNIEXPORT jboolean JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativePngToJpeg(JNIEnv* env, jobject, jstring png, jstring jpg,
                                                                  jint quality) {
    return pngToJpeg(str(env, png), str(env, jpg), quality);
}

JNIEXPORT void JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeSetAlignment(JNIEnv*, jobject, jlong handle, jint align) {
    if (handle && from(handle)->ncnn) from(handle)->ncnn->setAlignment(align);
}

// Runs a chain of passes. Arrays are parallel, one entry per pass. The first handle's
// cancel flag cancels the whole chain.
JNIEXPORT jint JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeRunChain(
    JNIEnv* env, jobject, jlongArray jHandles, jintArray jTiles, jintArray jPads, jintArray jDownsamples,
    jfloatArray jDetails, jbooleanArray jLuma, jbooleanArray jColorFix, jfloatArray jPreBlur, jobject bitmap,
    jint orientation,
    jlong maxBandBytes, jstring tmpDir, jstring outPath, jstring previewPath, jint previewMax, jobject listener,
    jlong detector, jlong gfpgan, jfloat faceStrength, jfloat maxFacePx) {
    const jsize n = env->GetArrayLength(jHandles);
    if (n <= 0) return kModelError;
    std::vector<jlong> handles(n);
    std::vector<jint> tiles(n), pads(n), downs(n);
    std::vector<jfloat> details(n), preBlur(n);
    std::vector<jboolean> luma(n), cfix(n);
    env->GetLongArrayRegion(jHandles, 0, n, handles.data());
    env->GetIntArrayRegion(jTiles, 0, n, tiles.data());
    env->GetIntArrayRegion(jPads, 0, n, pads.data());
    env->GetIntArrayRegion(jDownsamples, 0, n, downs.data());
    env->GetFloatArrayRegion(jDetails, 0, n, details.data());
    env->GetFloatArrayRegion(jPreBlur, 0, n, preBlur.data());
    env->GetBooleanArrayRegion(jLuma, 0, n, luma.data());
    env->GetBooleanArrayRegion(jColorFix, 0, n, cfix.data());
    for (jlong h : handles)
        if (!h) return kModelError;
    Handle* first = from(handles[0]);
    first->cancel = false;

    std::vector<PassSpec> passes(n);
    for (jsize i = 0; i < n; ++i) {
        passes[i].runner = from(handles[i])->runner.get();
        passes[i].opt.tile = std::max(32, static_cast<int>(tiles[i]));
        passes[i].opt.pad = std::max(4, static_cast<int>(pads[i]));
        passes[i].opt.overlap = std::max(16, passes[i].opt.pad);
        passes[i].opt.post.detail = details[i];
        passes[i].opt.post.lumaOnly = luma[i];
        passes[i].opt.post.colorFix = cfix[i];
        passes[i].opt.post.preBlur = preBlur[i];
        passes[i].downsample = std::max(1, static_cast<int>(downs[i]));
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return kBadInput;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return kBadInput;

    ChainResult res;
    {
        Image img(static_cast<const uint8_t*>(pixels), static_cast<int>(info.width), static_cast<int>(info.height),
                  static_cast<int>(info.stride), orientation);
        const std::string out = str(env, outPath), prevPath = str(env, previewPath);
        const int pmax = std::max(1, static_cast<int>(previewMax));
        std::unique_ptr<PngWriter> main, prevPng;
        std::unique_ptr<BoxDownsample> prevDown;
        std::unique_ptr<Tee> tee;
        FinalSinkFactory makeFinal = [&](int fw, int fh, int c) -> RowSink* {
            main = std::make_unique<PngWriter>(out, fw, fh, c);
            if (!main->ok()) return nullptr;
            RowSink* previewSink = nullptr;
            if (!prevPath.empty()) {
                const int pk = std::max(1, (std::max(fw, fh) + pmax - 1) / pmax);
                prevPng = std::make_unique<PngWriter>(prevPath, (fw + pk - 1) / pk, (fh + pk - 1) / pk, c, 3);
                prevDown = std::make_unique<BoxDownsample>(fw, fh, c, pk, prevPng.get());
                previewSink = prevDown.get();
            }
            tee = std::make_unique<Tee>(main.get(), previewSink);
            return tee.get();
        };

        jmethodID onProgress = nullptr;
        if (listener) onProgress = env->GetMethodID(env->GetObjectClass(listener), "onProgress", "(II)V");
        auto progress = [&](int done, int total) {
            if (!onProgress) return;
            env->CallVoidMethod(listener, onProgress, done, total);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                first->cancel = true;
            }
        };
        std::vector<RestoredFace> faces;
        if (detector && gfpgan && faceStrength > 0.f) {
            int found = 0;
            faces = restoreFaces(img, *reinterpret_cast<FaceDetector*>(detector), *from(gfpgan)->runner, 12, maxFacePx,
                                 &found);
            LOGI("faces: %d detected, %d restored", found, static_cast<int>(faces.size()));
        }
        LOGI("chain of %d pass(es) on %dx%d", static_cast<int>(n), img.width(), img.height());
        res = runChain(img, passes, str(env, tmpDir), maxBandBytes, makeFinal, progress, &first->cancel,
                       std::move(faces), faceStrength);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return res.status;
}

}  // extern "C"
