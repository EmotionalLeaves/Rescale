package app.rescale.core.model

/** What kind of image the user is upscaling. Picks the model family. */
enum class Mode(val label: String, val defaultDetail: Float) {
    // Photos look more natural with a little of the faithful bicubic blended back in.
    PHOTO("Photo", 0.8f),
    ANIME("Anime / Art", 1.0f),
}

/** User-facing artifact controls (see PostOptions in the native bridge). */
data class Tuning(
    val detail: Float,
    val colorFix: Boolean = true,
    val lumaOnly: Boolean = false,
) {
    init {
        require(detail in 0f..1f) { "detail must be in 0..1" }
    }

    companion object {
        fun defaultFor(mode: Mode) = Tuning(detail = mode.defaultDetail)

        /** For restoration passes: no blending or colour tricks. */
        val NEUTRAL = Tuning(detail = 1f, colorFix = false, lumaOnly = false)
    }
}

/**
 * A network the native engine can run. Bundled models live in assets/models; pack models
 * are downloaded/imported into filesDir/packs/<packId>/.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val scale: Int,
    val inputBlob: String,
    val outputBlob: String,
    val license: String,
    val source: String,
    /** null = bundled in the APK. */
    val packId: String? = null,
    /** Second input (e.g. FBCNN's quality control), fed the same value on every tile. */
    val extraInput: String? = null,
    /** Tile dimensions must be a multiple of this (UNets with 3 downsamples need 8). */
    val alignment: Int = 1,
    /** Preferred tile size and reflect padding (input px). Big-receptive-field models want more. */
    val tile: Int = 192,
    val pad: Int = 12,
    /** Runs natively without a network file (plain bicubic). */
    val builtin: Boolean = false,
    /** Network works in [-1,1] (feeds in*2-1). */
    val signedRange: Boolean = false,
    /** Match each output tile's mean/contrast to its input tile (generative models drift). */
    val statMatch: Boolean = false,
    /** Big network: skip ncnn's weight transforms (winograd/sgemm), which multiply memory 2–4×. */
    val lowMemory: Boolean = false,
) {
    val paramFile get() = "$id.param"
    val binFile get() = "$id.bin"
}

object BundledModels {
    private const val RESR = "https://github.com/xinntao/Real-ESRGAN"

    val GENERAL_X4V3 = ModelSpec("realesr-general-x4v3", "Real-ESRGAN general x4v3", 4, "in0", "out0", "BSD-3-Clause", RESR)
    val ANIME_X2 = ModelSpec("realesr-animevideov3-x2", "Real-ESRGAN animevideov3 x2", 2, "data", "output", "BSD-3-Clause", RESR)
    val ANIME_X4 = ANIME_X2.copy(id = "realesr-animevideov3-x4", displayName = "Real-ESRGAN animevideov3 x4", scale = 4)

    val all = listOf(GENERAL_X4V3, ANIME_X2, ANIME_X4)

    fun bicubic(scale: Int) = ModelSpec(
        "bicubic-x$scale", "Bicubic ×$scale", scale, "", "", "n/a", "", builtin = true, tile = 256, pad = 4,
    )
}

/** Optional downloadable models (see Packs for what each pack contains). */
object PackModels {
    private const val RESR = "https://github.com/xinntao/Real-ESRGAN"

    val FBCNN = ModelSpec(
        "fbcnn-color", "FBCNN (JPEG artifact removal)", 1, "in0", "out0", "Apache-2.0",
        "https://github.com/jiaxi-jiang/FBCNN", packId = Packs.JPEG, extraInput = "in1", alignment = 8, tile = 256, pad = 32,
    )
    val DENOISE_50 = ModelSpec(
        "realesr-general-dn50", "Real-ESRGAN general x4v3 + 50% denoise", 4, "in0", "out0", "BSD-3-Clause", RESR,
        packId = Packs.DENOISE,
    )
    val DENOISE_100 = DENOISE_50.copy(id = "realesr-general-dn100", displayName = "Real-ESRGAN general x4v3 + full denoise")
    val X4PLUS = ModelSpec(
        "realesrgan-x4plus", "Real-ESRGAN x4plus", 4, "data", "output", "BSD-3-Clause", RESR,
        packId = Packs.PHOTO_PRO, tile = 128, pad = 12,
    )
    val X4PLUS_ANIME = ModelSpec(
        "realesrgan-x4plus-anime", "Real-ESRGAN x4plus anime 6B", 4, "data", "output", "BSD-3-Clause", RESR,
        packId = Packs.ANIME_PRO, tile = 128, pad = 12,
    )

    /** GFPGAN v1.4 (512² aligned faces, values in [-1,1]); not a tiled pass. */
    val GFPGAN = ModelSpec(
        "gfpgan-v1.4", "GFPGAN v1.4 (face restoration)", 1, "in0", "out0", "Apache-2.0",
        "https://github.com/TencentARC/GFPGAN", packId = Packs.FACES, tile = 512, pad = 0,
    )
    val RETINAFACE = ModelSpec(
        "retinaface-mnet", "RetinaFace MobileNet-0.25 (face detection)", 1, "in0", "out0", "MIT",
        "https://github.com/xinntao/facexlib", packId = Packs.FACES,
    )

    /**
     * AdcSR: Stable Diffusion 2.1 distilled into a one-step, prompt-free 4× upscaler. Trained on
     * 128-px tiles, so tiles are 96 + 2×16 padding.
     */
    val ADCSR = ModelSpec(
        "adcsr-x4", "AdcSR (generative, one-step diffusion)", 4, "in0", "out0", "CreativeML Open RAIL++-M",
        "https://github.com/Guaishou74851/AdcSR", packId = Packs.GENERATIVE, alignment = 16, tile = 96, pad = 16,
        signedRange = true, statMatch = true, lowMemory = true,
    )

    /** Scratch/tear detector from Microsoft's "Bringing Old Photos Back to Life" (not a tiled pass). */
    val SCRATCH = ModelSpec(
        "scratch-detect", "Old-photo damage detector", 1, "in0", "out0", "MIT",
        "https://github.com/microsoft/Bringing-Old-Photos-Back-to-Life", packId = Packs.REPAIR,
    )

    /** MI-GAN inpainting on 512² windows (not a tiled pass). */
    val MIGAN = ModelSpec(
        "migan-512", "MI-GAN (inpainting)", 1, "in0", "out0", "MIT",
        "https://github.com/Picsart-AI-Research/MI-GAN", packId = Packs.REPAIR,
    )

    val all = listOf(FBCNN, DENOISE_50, DENOISE_100, X4PLUS, X4PLUS_ANIME, GFPGAN, RETINAFACE, ADCSR, SCRATCH, MIGAN)
}

object Packs {
    const val JPEG = "jpeg"
    const val DENOISE = "denoise"
    const val PHOTO_PRO = "photo-pro"
    const val ANIME_PRO = "anime-pro"
    const val FACES = "faces"
    const val REPAIR = "repair"
    const val GENERATIVE = "generative"

    data class Info(
        val id: String,
        val name: String,
        val description: String,
        val enables: String,
        /** Terms the user must accept before downloading (model licence with use restrictions). */
        val terms: String? = null,
        /** Devices with less RAM can't load it. */
        val minRamGb: Int = 0,
    )

    val all = listOf(
        Info(JPEG, "JPEG repair", "FBCNN removes blocks, ringing and colour bleeding from compressed images before upscaling.", "De-JPEG stage"),
        Info(DENOISE, "Denoise", "Real-ESRGAN general with its denoise twin blended in, for grainy low-light photos.", "Denoise stage"),
        Info(PHOTO_PRO, "Photo Pro", "Real-ESRGAN x4plus: stronger texture than the built-in model, but about 15–20× slower.", "Pro quality (Photo)"),
        Info(FACES, "Face restore", "GFPGAN restores small or blurry faces (RetinaFace finds them). Restored faces are partly invented.", "Faces stage"),
        Info(ANIME_PRO, "Anime Pro", "Real-ESRGAN x4plus anime 6B: crisper line art than the built-in model, slower.", "Pro quality (Anime)"),
        Info(
            REPAIR, "Repair",
            "Finds scratches, cracks, tears and spots on old photos and fills them in (MI-GAN). You can also paint over anything you want removed.",
            "Repair stage",
        ),
        Info(
            GENERATIVE, "Generative detail",
            "AdcSR, a one-step diffusion model: invents realistic hair, skin, fabric and foliage where other models smear. " +
                "Much slower (about 1–3 s per tile on a recent GPU) and it changes details, so don't use it where accuracy matters.",
            "Generative (Photo)",
            terms = GENERATIVE_TERMS,
            minRamGb = 6,
        ),
    )

    /** Shown before downloading the generative pack: its weights inherit Stable Diffusion's licence. */
    const val GENERATIVE_TERMS =
        "AdcSR's weights are derived from Stable Diffusion 2.1 and come under the CreativeML Open RAIL++-M licence. " +
            "By downloading you agree not to use the model or its output: to break the law; to exploit or harm minors; " +
            "to generate or spread verifiably false information meant to harm others; to generate or spread personal " +
            "information that could harm someone; to defame, disparage or harass people; for fully automated decisions " +
            "that affect someone's legal rights; to discriminate against people or groups; to exploit vulnerable groups; " +
            "to provide medical advice or interpret medical results; or for law enforcement, justice, immigration or " +
            "asylum processes such as predicting that someone will commit a crime. The full licence is included in the pack " +
            "(LICENSE.txt) and at huggingface.co/stabilityai/stable-diffusion-2-1-base."


    fun models(packId: String) = PackModels.all.filter { it.packId == packId }
}

/** Optional restoration stages that run before (or change) the upscale. */
enum class Stage(val label: String, val packId: String) {
    REPAIR("Repair", Packs.REPAIR),
    DE_JPEG("De-JPEG", Packs.JPEG),
    DENOISE("Denoise", Packs.DENOISE),
    FACES("Faces", Packs.FACES),
}

/** Everything a job needs beyond the input. */
data class JobSettings(
    val mode: Mode,
    val scale: Int,
    val tuning: Tuning,
    val stages: List<Stage> = emptyList(),
    /** Use the Pro upscaler for this mode (if its pack is installed). */
    val pro: Boolean = false,
    /** Quality fed to the De-JPEG stage (1..100, lower = stronger repair). */
    val jpegQuality: Int = 50,
    /** Use full-strength denoise (else 50%). */
    val strongDenoise: Boolean = false,
    /** Long chains (3+ passes): do the last factor with plain bicubic instead of a model. */
    val finishWithBicubic: Boolean = false,
    /** Blend of restored faces over the upscale (Faces stage), 0..1. */
    val faceStrength: Float = 0.6f,
    /** Use the generative upscaler for the first 4× (Photo mode, if its pack is installed). */
    val generative: Boolean = false,
    /** Repair stage: detect damage automatically (else only the painted mask is filled). */
    val autoDamage: Boolean = true,
    /** Repair stage: path of a painted mask (ALPHA_8 PNG over the oriented image, any size). */
    val repairMask: String? = null,
)

/** One pass of the native chain. */
data class PassPlan(
    val model: ModelSpec,
    val downsample: Int,
    val tuning: Tuning,
    val extra: FloatArray? = null,
    val preBlur: Float = 0f,
) {
    override fun equals(other: Any?) = other is PassPlan && model == other.model && downsample == other.downsample &&
        tuning == other.tuning && (extra?.toList() == other.extra?.toList())
    override fun hashCode() = model.hashCode() * 31 + downsample
}

/**
 * How a job is executed: the last pass runs the upscaler (native factor model.scale) and then
 * box-downsamples by [downsample]; restoration passes come before it.
 */
data class PipelinePlan(val passes: List<PassPlan>) {
    /** The first upscaling pass (after any 1× restoration passes). */
    val upscale: PassPlan get() = passes.first { it.model.scale > 1 }
    val model: ModelSpec get() = upscale.model
    val downsample: Int get() = upscale.downsample
    val upscalePasses: Int get() = passes.count { it.model.scale > 1 }
    val outputScale: Int get() = passes.fold(1) { acc, p -> acc * p.model.scale / p.downsample }
}

object Planner {
    val supportedScales = listOf(2, 4, 8, 16, 32)

    /** Per-pass factors: prefer 4× then 2× (never 2×2×2). */
    fun factors(scale: Int): List<Int> = when (scale) {
        2 -> listOf(2)
        4 -> listOf(4)
        8 -> listOf(4, 2)
        16 -> listOf(4, 4)
        32 -> listOf(4, 4, 2)
        else -> throw IllegalArgumentException("Unsupported scale $scale")
    }

    /** Each further pass invents more, so Detail decays (0.8 → 0.56 → 0.39). */
    const val DETAIL_DECAY = 0.7f

    /** Light blur before passes 2+ so the model doesn't "upscale its own textures". */
    const val CHAIN_PRE_BLUR = 0.4f

    fun plan(mode: Mode, scale: Int): PipelinePlan = plan(JobSettings(mode, scale, Tuning.defaultFor(mode)), emptySet())

    /** [installedPacks]: ids of packs on the device; stages/pro without their pack are skipped. */
    fun plan(s: JobSettings, installedPacks: Set<String>): PipelinePlan {
        require(s.scale in supportedScales) { "Unsupported scale ${s.scale}" }
        val passes = mutableListOf<PassPlan>()
        if (Stage.DE_JPEG in s.stages && Packs.JPEG in installedPacks) {
            val qf = 1f - s.jpegQuality.coerceIn(1, 100) / 100f
            passes += PassPlan(PackModels.FBCNN, 1, Tuning.NEUTRAL, floatArrayOf(qf))
        }
        val factors = factors(s.scale)
        for ((n, f) in factors.withIndex()) {
            val last = n == factors.lastIndex
            val model = if (last && n >= 2 && s.finishWithBicubic) BundledModels.bicubic(f)
            else upscaler(s, installedPacks, f, firstPass = n == 0)
            val decay = Math.pow(DETAIL_DECAY.toDouble(), n.toDouble()).toFloat()
            val tuning = if (model.builtin) Tuning.NEUTRAL else s.tuning.copy(detail = (s.tuning.detail * decay).coerceIn(0f, 1f))
            // 4× models make 2× by area-downsampling (same as Real-ESRGAN's --outscale).
            passes += PassPlan(model, model.scale / f, tuning, preBlur = if (n > 0 && !model.builtin) CHAIN_PRE_BLUR else 0f)
        }
        return PipelinePlan(passes)
    }

    private fun upscaler(s: JobSettings, packs: Set<String>, factor: Int, firstPass: Boolean): ModelSpec = when (s.mode) {
        Mode.PHOTO -> when {
            // One generative pass invents the texture; further passes would hallucinate on top of it.
            firstPass && s.generative && Packs.GENERATIVE in packs -> PackModels.ADCSR
            firstPass && Stage.DENOISE in s.stages && Packs.DENOISE in packs ->
                if (s.strongDenoise) PackModels.DENOISE_100 else PackModels.DENOISE_50
            s.pro && Packs.PHOTO_PRO in packs -> PackModels.X4PLUS
            else -> BundledModels.GENERAL_X4V3
        }
        Mode.ANIME -> when {
            s.pro && Packs.ANIME_PRO in packs -> PackModels.X4PLUS_ANIME
            factor == 2 -> BundledModels.ANIME_X2
            else -> BundledModels.ANIME_X4
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
    /** Largest input we decode in one go. Bigger inputs need region decoding. */
    const val MAX_INPUT_PIXELS = 50_000_000L

    /** Many gallery apps can't open images above this, so we warn. */
    const val GALLERY_WARN_PIXELS = 100_000_000L
}
