package app.rescale.core.model

/** What kind of image the user is upscaling. Picks the model family. */
enum class Mode(val label: String) {
    PHOTO("Photo"),
    ANIME("Anime / Art"),
}

/** A network the native engine can run. Files live in assets/models/<id>.param/.bin. */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val scale: Int,
    val inputBlob: String,
    val outputBlob: String,
    val license: String,
    val source: String,
) {
    val paramFile get() = "$id.param"
    val binFile get() = "$id.bin"
}

object BundledModels {
    val GENERAL_X4V3 = ModelSpec(
        id = "realesr-general-x4v3",
        displayName = "Real-ESRGAN general x4v3",
        scale = 4,
        inputBlob = "in0",
        outputBlob = "out0",
        license = "BSD-3-Clause",
        source = "https://github.com/xinntao/Real-ESRGAN",
    )
    val ANIME_X2 = ModelSpec(
        id = "realesr-animevideov3-x2",
        displayName = "Real-ESRGAN animevideov3 x2",
        scale = 2,
        inputBlob = "data",
        outputBlob = "output",
        license = "BSD-3-Clause",
        source = "https://github.com/xinntao/Real-ESRGAN",
    )
    val ANIME_X4 = ANIME_X2.copy(
        id = "realesr-animevideov3-x4",
        displayName = "Real-ESRGAN animevideov3 x4",
        scale = 4,
    )

    val all = listOf(GENERAL_X4V3, ANIME_X2, ANIME_X4)
}

/**
 * How a job is executed: run [model] (native factor model.scale), then box-downsample
 * the stream by [downsample] so the final factor is model.scale / downsample.
 */
data class PipelinePlan(val model: ModelSpec, val downsample: Int) {
    val outputScale: Int get() = model.scale / downsample
}

object Planner {
    val supportedScales = listOf(2, 4)

    fun plan(mode: Mode, scale: Int): PipelinePlan {
        require(scale in supportedScales) { "Unsupported scale $scale" }
        return when (mode) {
            // general-x4v3 is 4x only; for 2x we run 4x and area-downsample by 2,
            // which is the same thing Real-ESRGAN's --outscale does.
            Mode.PHOTO -> PipelinePlan(BundledModels.GENERAL_X4V3, 4 / scale)
            Mode.ANIME -> if (scale == 2) {
                PipelinePlan(BundledModels.ANIME_X2, 1)
            } else {
                PipelinePlan(BundledModels.ANIME_X4, 1)
            }
        }
    }
}

/** Rough output size estimate for the editor ("12,288 × 16,384 · ~180 MB PNG"). */
data class OutputEstimate(val width: Long, val height: Long) {
    val megapixels: Double get() = width * height / 1_000_000.0

    /** Upscaled photos compress to roughly 1.5 bytes/pixel as RGB PNG. */
    val approxPngBytes: Long get() = (width * height * 3 / 2)

    companion object {
        fun of(inW: Int, inH: Int, scale: Int) = OutputEstimate(inW.toLong() * scale, inH.toLong() * scale)
    }
}

object Limits {
    /** Largest input we decode in one go (MVP). Bigger inputs need region decoding (v1.0). */
    const val MAX_INPUT_PIXELS = 50_000_000L

    /** Many gallery apps can't open images above this, so we warn. */
    const val GALLERY_WARN_PIXELS = 100_000_000L
}
