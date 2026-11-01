package app.rescale.engine.pipeline

import android.content.Context
import android.util.Log
import app.rescale.core.model.ModelSpec
import app.rescale.engine.nativebridge.NativeEngine
import java.io.File
import kotlin.math.abs

enum class BackendKind { VULKAN, CPU }

/**
 * Picks Vulkan or CPU per model. On first use we run the same probe tile on both and only
 * trust the GPU if its output matches the CPU (some old Mali/PowerVR drivers return black or
 * garbage). The verdict is cached per model and app version.
 */
class BackendSelector(context: Context, private val appVersion: Long) {
    private val prefs = context.getSharedPreferences("backend", Context.MODE_PRIVATE)

    var forceCpu: Boolean
        get() = prefs.getBoolean("force_cpu", false)
        set(v) = prefs.edit().putBoolean("force_cpu", v).apply()

    fun open(spec: ModelSpec, param: File, bin: File): NativeEngine.Session {
        val threads = cpuThreads()
        val key = "gpu_ok_${spec.id}_$appVersion"
        val wantGpu = !forceCpu && NativeEngine.gpuCount() > 0 && prefs.getBoolean(key, true)
        if (wantGpu) {
            val gpu = NativeEngine.open(param.path, bin.path, spec.inputBlob, spec.outputBlob, spec.scale, true, threads)
            if (gpu != null && gpu.usingGpu) {
                if (prefs.contains(key)) return gpu
                val ok = selfTest(gpu, spec, param, bin, threads)
                prefs.edit().putBoolean(key, ok).apply()
                if (ok) return gpu
                gpu.close()
            } else {
                gpu?.close()
            }
        }
        return NativeEngine.open(param.path, bin.path, spec.inputBlob, spec.outputBlob, spec.scale, false, threads)
            ?: error("Couldn't load model ${spec.id}")
    }

    private fun selfTest(gpu: NativeEngine.Session, spec: ModelSpec, param: File, bin: File, threads: Int): Boolean {
        val g = gpu.probe() ?: return false
        val cpu = NativeEngine.open(param.path, bin.path, spec.inputBlob, spec.outputBlob, spec.scale, false, threads)
            ?: return false
        val c = cpu.use { it.probe() } ?: return false
        if (g.size != c.size) return false
        var maxDiff = 0f
        var sum = 0.0
        for (i in g.indices) {
            if (g[i].isNaN()) return false
            maxDiff = maxOf(maxDiff, abs(g[i].coerceIn(0f, 1f) - c[i].coerceIn(0f, 1f)))
            sum += g[i]
        }
        val mean = sum / g.size
        Log.i("Rescale", "GPU self-test ${spec.id}: maxDiff=$maxDiff mean=$mean")
        // FP16 differences are tiny; a broken driver is way off (or all zeros).
        return maxDiff < 0.06f && mean > 0.05
    }

    fun resetSelfTests() {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("gpu_ok_") }.forEach { e.remove(it) }
        e.apply()
    }

    companion object {
        fun cpuThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
