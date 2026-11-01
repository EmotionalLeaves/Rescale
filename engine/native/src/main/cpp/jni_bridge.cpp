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
#include "repairer.h"

#include <sys/stat.h>
#include <cstdio>
#include <cstring>

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

// Applies a painted mask bitmap (any size, over the oriented image) to `mask` (W×H).
// ALPHA_8: opaque = add. RGBA_8888: opaque red = add, opaque green = erase (keep, even if the
// detector flagged it). Nearest-neighbour: brush strokes are already soft enough.
bool addMaskBitmap(JNIEnv* env, jobject bitmap, int W, int H, std::vector<uint8_t>& mask) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
    const bool alpha8 = info.format == ANDROID_BITMAP_FORMAT_A_8;
    if (!alpha8 && info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
    const auto* px = static_cast<const uint8_t*>(pixels);
    const int mw = static_cast<int>(info.width), mh = static_cast<int>(info.height);
    for (int y = 0; y < H; ++y) {
        const int sy = std::min(mh - 1, static_cast<int>((y + 0.5f) * mh / H));
        const uint8_t* row = px + static_cast<size_t>(sy) * info.stride;
        for (int x = 0; x < W; ++x) {
            const int sx = std::min(mw - 1, static_cast<int>((x + 0.5f) * mw / W));
            const uint8_t a = alpha8 ? row[sx] : row[sx * 4 + 3];
            if (a < 128) continue;
            const bool erase = !alpha8 && row[sx * 4 + 1] >= 128 && row[sx * 4] < 128;
            mask[static_cast<size_t>(y) * W + x] = erase ? 0 : 1;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

bool readFile(const std::string& path, std::vector<uint8_t>& buf) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    const bool ok = std::fread(buf.data(), 1, buf.size(), f) == buf.size();
    std::fclose(f);
    return ok;
}

bool writeFile(const std::string& path, const std::vector<uint8_t>& buf) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    bool ok = std::fwrite(buf.data(), 1, buf.size(), f) == buf.size();
    ok = std::fclose(f) == 0 && ok;
    return ok;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeGpuCount(JNIEnv*, jobject) {
    return ensureGpuInstance();
}

JNIEXPORT jlong JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeCreate(JNIEnv* env, jobject, jstring param, jstring bin,
                                                               jstring inBlob, jstring outBlob, jint scale,
                                                               jboolean gpu, jint threads, jboolean lowMemory) {
    auto h = std::make_unique<Handle>();
    auto r = std::make_unique<NcnnRunner>(scale, gpu, threads, str(env, inBlob), str(env, outBlob));
    r->setLowMemory(lowMemory);
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

JNIEXPORT void JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeSetStatMatch(JNIEnv*, jobject, jlong handle, jboolean on) {
    if (handle && from(handle)->ncnn) from(handle)->ncnn->setStatMatch(on);
}

JNIEXPORT jlong JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeCreateScratchDetector(JNIEnv* env, jobject, jstring param,
                                                                              jstring bin, jboolean gpu, jint threads) {
    auto d = std::make_unique<ScratchDetector>(gpu, threads);
    if (!d->load(str(env, param), str(env, bin))) return 0;
    return reinterpret_cast<jlong>(d.release());
}

JNIEXPORT void JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeDestroyScratchDetector(JNIEnv*, jobject, jlong h) {
    delete reinterpret_cast<ScratchDetector*>(h);
}

// Damage mask of a (preview) bitmap into `out` (ALPHA_8, same size; 255 = damage). Returns the
// share flagged by the fine pass (Auto's damage score), or -1 on failure. The mask is shown in
// the mask editor.
JNIEXPORT jfloat JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeScratchMask(JNIEnv* env, jobject, jlong h, jobject bitmap,
                                                                    jint orientation, jobject outBitmap) {
    if (!h) return -1.f;
    AndroidBitmapInfo info, oinfo;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, outBitmap, &oinfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || oinfo.format != ANDROID_BITMAP_FORMAT_A_8)
        return -1.f;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return -1.f;
    std::vector<uint8_t> mask;
    int W, H;
    bool ok;
    float fine = 0.f;
    {
        Image img(static_cast<const uint8_t*>(pixels), static_cast<int>(info.width), static_cast<int>(info.height),
                  static_cast<int>(info.stride), orientation);
        W = img.width();
        H = img.height();
        ok = reinterpret_cast<ScratchDetector*>(h)->detect(img, mask, 512, 0.4f, &fine);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    if (!ok || static_cast<int>(oinfo.width) != W || static_cast<int>(oinfo.height) != H) return -1.f;
    void* out = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &out) != ANDROID_BITMAP_RESULT_SUCCESS) return -1.f;
    for (int y = 0; y < H; ++y) {
        uint8_t* row = static_cast<uint8_t*>(out) + static_cast<size_t>(y) * oinfo.stride;
        for (int x = 0; x < W; ++x) row[x] = mask[static_cast<size_t>(y) * W + x] ? 255 : 0;
    }
    AndroidBitmap_unlockPixels(env, outBitmap);
    return fine;
}

JNIEXPORT jlong JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeCreateInpainter(JNIEnv* env, jobject, jstring param,
                                                                        jstring bin, jboolean gpu, jint threads) {
    auto p = std::make_unique<NcnnInpainter>(gpu, threads);
    if (!p->load(str(env, param), str(env, bin))) return 0;
    LOGI("inpainter loaded, gpu=%d", p->usingGpu());
    return reinterpret_cast<jlong>(p.release());
}

JNIEXPORT void JNICALL
Java_app_rescale_engine_nativebridge_NativeEngine_nativeDestroyInpainter(JNIEnv*, jobject, jlong h) {
    delete reinterpret_cast<NcnnInpainter*>(h);
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
    jlong detector, jlong gfpgan, jfloat faceStrength, jfloat maxFacePx, jlong scratchDetector, jlong inpainter,
    jobject repairMask) {
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
        Image oriented(static_cast<const uint8_t*>(pixels), static_cast<int>(info.width), static_cast<int>(info.height),
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

        jmethodID onProgress = nullptr, onPhase = nullptr;
        if (listener) {
            jclass cls = env->GetObjectClass(listener);
            onProgress = env->GetMethodID(cls, "onProgress", "(II)V");
            if (env->ExceptionCheck()) env->ExceptionClear();
            onPhase = env->GetMethodID(cls, "onPhase", "(I)V");
            if (env->ExceptionCheck()) env->ExceptionClear();  // optional: keep going without phases
        }
        auto progress = [&](int done, int total) {
            if (!onProgress) return;
            env->CallVoidMethod(listener, onProgress, done, total);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                first->cancel = true;
            }
        };
        auto phase = [&](int p) {
            if (!onPhase) return;
            env->CallVoidMethod(listener, onPhase, p);
            if (env->ExceptionCheck()) env->ExceptionClear();
        };

        // Repair stage: fill damage on an oriented copy of the input, which then feeds faces and
        // the chain. The result is kept in tmpDir so a resumed job skips it.
        std::vector<uint8_t> repaired;
        std::unique_ptr<Image> repairedImg;
        if (inpainter) {
            const int W = oriented.width(), H = oriented.height();
            const std::string rawPath = str(env, tmpDir) + "/repaired.rgba";
            const size_t bytes = static_cast<size_t>(W) * H * 4;
            repaired.resize(bytes);
            struct stat st{};
            const bool resumed = ::stat((rawPath + ".done").c_str(), &st) == 0 && readFile(rawPath, repaired);
            if (!resumed) {
                phase(1);
                std::vector<uint8_t> mask(static_cast<size_t>(W) * H, 0);
                if (scratchDetector && !reinterpret_cast<ScratchDetector*>(scratchDetector)->detect(oriented, mask)) {
                    LOGE("scratch detection failed");
                }
                if (repairMask && !addMaskBitmap(env, repairMask, W, H, mask)) LOGE("unsupported mask bitmap");
                for (int y = 0; y < H; ++y)
                    for (int x = 0; x < W; ++x) std::memcpy(&repaired[(static_cast<size_t>(y) * W + x) * 4], oriented.at(x, y), 4);
                size_t holes = 0;
                for (uint8_t m : mask) holes += m;
                LOGI("repair: %.2f%% of pixels", 100.0 * holes / mask.size());
                const Status rs = repairImage(repaired, W, H, mask, *reinterpret_cast<NcnnInpainter*>(inpainter), progress,
                                              &first->cancel);
                if (rs != kOk) {
                    AndroidBitmap_unlockPixels(env, bitmap);
                    return rs;
                }
                if (writeFile(rawPath, repaired)) {
                    if (FILE* f = std::fopen((rawPath + ".done").c_str(), "w")) std::fclose(f);
                }
            }
            repairedImg = std::make_unique<Image>(repaired.data(), W, H, W * 4, 1);
        }
        const Image& img = repairedImg ? *repairedImg : oriented;
        phase(0);
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
