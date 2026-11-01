package app.rescale.engine.pipeline

import android.content.Context
import app.rescale.core.model.ModelSpec
import java.io.File

/**
 * Bundled models ship in assets/models. ncnn loads from file paths, so on first use they're
 * copied into filesDir/models (a few MB, done once per app version).
 */
class ModelStore(private val context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    fun install(spec: ModelSpec): Pair<File, File> {
        val param = File(dir, spec.paramFile)
        val bin = File(dir, spec.binFile)
        copyIfNeeded("models/${spec.paramFile}", param)
        copyIfNeeded("models/${spec.binFile}", bin)
        return param to bin
    }

    private fun copyIfNeeded(asset: String, target: File) {
        val expected = context.assets.openFd(asset).use { it.length }
        if (target.exists() && target.length() == expected) return
        val tmp = File(target.parentFile, target.name + ".tmp")
        context.assets.open(asset).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(target)) error("Couldn't install $asset")
    }
}
