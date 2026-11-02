package app.rescale.core.model

data class AutoPlan(
    val settings: JobSettings,
    val description: String,
    /** Damage was detected: offer Repair (never switched on silently, see [Analysis.damaged]). */
    val suggestRepair: Boolean = false,
) {
    /** "De-JPEG → Upscale 4× (Photo)". */
    val chips: List<String>
        get() = settings.stages.map { it.label } +
            "Upscale ${settings.scale}× (${if (settings.generative) "Generative" else settings.mode.label})"
}

/**
 * Turns an [Analysis] into settings. [available] is the set of restoration stages whose
 * models are installed; wanted stages that aren't available are simply skipped.
 * [generativeOk]: the generative pack is installed and this device can run it.
 */
object AutoPlanner {
    /** Generative upscaling is slow (~1–3 s per 96-px tile on GPU); Auto only picks it for small inputs. */
    const val GENERATIVE_MAX_PIXELS = 400_000

    fun plan(a: Analysis, available: Set<Stage> = emptySet(), generativeOk: Boolean = false): AutoPlan {
        val mode = if (a.looksLikeArt) Mode.ANIME else Mode.PHOTO
        val longSide = maxOf(a.width, a.height)
        val scale = if (!a.tiny && longSide >= 2000) 2 else 4

        val stages = buildList {
            if (a.someJpeg) add(Stage.DE_JPEG)
            if (a.noisy && mode == Mode.PHOTO) add(Stage.DENOISE)
            if (a.smallFaces > 0 && mode == Mode.PHOTO) add(Stage.FACES)
        }.filter { it in available }

        // Generative detail goes down when the source is damaged: the model would
        // otherwise sharpen artifacts into fake texture.
        var detail = mode.defaultDetail
        if (mode == Mode.PHOTO) {
            if (a.heavyJpeg && Stage.DE_JPEG !in stages) detail = 0.5f
            else if (a.someJpeg && Stage.DE_JPEG !in stages) detail = 0.65f
            if (a.noisy && Stage.DENOISE !in stages) detail = minOf(detail, 0.6f)
        }
        // Small photos are where generated detail impresses most and costs least.
        val generative = generativeOk && mode == Mode.PHOTO && a.width.toLong() * a.height <= GENERATIVE_MAX_PIXELS &&
            Generative.fits(a.width, a.height)
        if (generative) detail = maxOf(detail, 0.9f)
        val tuning = Tuning(detail = detail, colorFix = true, lumaOnly = a.grayscale)
        // Header quality when it's a JPEG; otherwise guess from how blocky it looks.
        val quality = a.jpegQuality ?: if (a.heavyJpeg) 25 else 50
        val settings = JobSettings(
            mode, scale, tuning, stages,
            jpegQuality = quality,
            strongDenoise = a.noiseSigma > 12f,
            generative = generative,
        )
        return AutoPlan(settings, a.describe(), suggestRepair = a.damaged && Stage.REPAIR in available)
    }
}
