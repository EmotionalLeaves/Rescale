package app.rescale.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlannerTest {
    @Test fun photo4xRunsGeneralModelWithoutDownsample() {
        val p = Planner.plan(Mode.PHOTO, 4)
        assertEquals(BundledModels.GENERAL_X4V3, p.model)
        assertEquals(1, p.downsample)
        assertEquals(4, p.outputScale)
    }

    @Test fun photo2xRuns4xThenHalves() {
        val p = Planner.plan(Mode.PHOTO, 2)
        assertEquals(4, p.model.scale)
        assertEquals(2, p.downsample)
        assertEquals(2, p.outputScale)
    }

    @Test fun animeUsesNativeFactorModels() {
        assertEquals(BundledModels.ANIME_X2, Planner.plan(Mode.ANIME, 2).model)
        assertEquals(BundledModels.ANIME_X4, Planner.plan(Mode.ANIME, 4).model)
        assertEquals(1, Planner.plan(Mode.ANIME, 4).downsample)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedScale() {
        Planner.plan(Mode.PHOTO, 3)
    }

    @Test fun deJpegPassRunsFirstWithQualityInput() {
        val s = JobSettings(Mode.PHOTO, 4, Tuning.defaultFor(Mode.PHOTO), listOf(Stage.DE_JPEG), jpegQuality = 20)
        val p = Planner.plan(s, setOf(Packs.JPEG))
        assertEquals(2, p.passes.size)
        assertEquals(PackModels.FBCNN, p.passes[0].model)
        assertEquals(0.8f, p.passes[0].extra!![0], 1e-6f)
        assertEquals(Tuning.NEUTRAL, p.passes[0].tuning)
        assertEquals(BundledModels.GENERAL_X4V3, p.model)
        assertEquals(4, p.outputScale)
    }

    @Test fun missingPacksAreSkipped() {
        val s = JobSettings(Mode.PHOTO, 2, Tuning.defaultFor(Mode.PHOTO), listOf(Stage.DE_JPEG, Stage.DENOISE), pro = true)
        val p = Planner.plan(s, emptySet())
        assertEquals(1, p.passes.size)
        assertEquals(BundledModels.GENERAL_X4V3, p.model)
        assertEquals(2, p.outputScale)
    }

    @Test fun denoiseAndProSwapTheUpscaler() {
        val base = JobSettings(Mode.PHOTO, 4, Tuning.defaultFor(Mode.PHOTO))
        val all = setOf(Packs.DENOISE, Packs.PHOTO_PRO, Packs.ANIME_PRO)
        assertEquals(PackModels.DENOISE_50, Planner.plan(base.copy(stages = listOf(Stage.DENOISE)), all).model)
        assertEquals(PackModels.DENOISE_100, Planner.plan(base.copy(stages = listOf(Stage.DENOISE), strongDenoise = true), all).model)
        assertEquals(PackModels.X4PLUS, Planner.plan(base.copy(pro = true), all).model)
        val anime = Planner.plan(JobSettings(Mode.ANIME, 2, Tuning.defaultFor(Mode.ANIME), pro = true), all)
        assertEquals(PackModels.X4PLUS_ANIME, anime.model)
        assertEquals(2, anime.downsample)
    }

    @Test fun estimate() {
        val e = OutputEstimate.of(1000, 500, 4)
        assertEquals(4000L, e.width)
        assertEquals(2000L, e.height)
        assertEquals(8.0, e.megapixels, 1e-9)
    }
}

class TuningTest {
    @Test fun defaultsPerMode() {
        assertEquals(0.8f, Tuning.defaultFor(Mode.PHOTO).detail, 1e-6f)
        assertEquals(1.0f, Tuning.defaultFor(Mode.ANIME).detail, 1e-6f)
        assertEquals(true, Tuning.defaultFor(Mode.PHOTO).colorFix)
        assertEquals(false, Tuning.defaultFor(Mode.PHOTO).lumaOnly)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeDetail() {
        Tuning(detail = 1.5f)
    }
}
