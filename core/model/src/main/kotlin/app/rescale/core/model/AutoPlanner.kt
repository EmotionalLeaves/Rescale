package app.rescale.core.model

data class AutoPlan(val settings: JobSettings, val description: String) {
    /** "De-JPEG → Upscale 4× (Photo)". */
    val chips: List<String>
        get() = settings.stages.map { it.label } + "Upscale ${settings.scale}× (${settings.mode.label})"
}

/**
 * Turns an [Analysis] into settings. [available] is the set of restoration stages whose
 * models are installed; wanted stages that aren't available are simply skipped.
 */
object AutoPlanner {
    fun plan(a: Analysis, available: Set<Stage> = emptySet()): AutoPlan {
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
        val tuning = Tuning(detail = detail, colorFix = true, lumaOnly = a.grayscale)
        // Header quality when it's a JPEG; otherwise guess from how blocky it looks.
        val quality = a.jpegQuality ?: if (a.heavyJpeg) 25 else 50
        val settings = JobSettings(
            mode, scale, tuning, stages,
            jpegQuality = quality,
            strongDenoise = a.noiseSigma > 12f,
        )
        return AutoPlan(settings, a.describe())
    }
}
