package app.rescale.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun chainsPrefer4xThen2x() {
        for (scale in Planner.supportedScales) {
            for (mode in Mode.entries) {
                val p = Planner.plan(mode, scale)
                assertEquals("$mode $scale", scale, p.outputScale)
            }
        }
        val p8 = Planner.plan(Mode.PHOTO, 8)
        assertEquals(2, p8.passes.size)
        assertEquals(1, p8.passes[0].downsample)
        assertEquals(2, p8.passes[1].downsample)
        assertEquals(BundledModels.ANIME_X2, Planner.plan(Mode.ANIME, 32).passes[2].model)
    }

    @Test fun laterPassesDecayDetailAndPreBlur() {
        val s = JobSettings(Mode.PHOTO, 32, Tuning(detail = 0.8f))
        val p = Planner.plan(s, emptySet())
        assertEquals(listOf(0.8f, 0.56f, 0.392f), p.passes.map { Math.round(it.tuning.detail * 1000) / 1000f })
        assertEquals(listOf(0f, Planner.CHAIN_PRE_BLUR, Planner.CHAIN_PRE_BLUR), p.passes.map { it.preBlur })
    }

    @Test fun longChainsCanFinishWithBicubic() {
        val s = JobSettings(Mode.PHOTO, 32, Tuning(detail = 0.8f), finishWithBicubic = true)
        val last = Planner.plan(s, emptySet()).passes.last()
        assertTrue(last.model.builtin)
        assertEquals(2, last.model.scale)
        assertEquals(1, last.downsample)
        assertEquals(Tuning.NEUTRAL, last.tuning)
        // Ignored for short chains.
        assertFalse(Planner.plan(s.copy(scale = 8), emptySet()).passes.last().model.builtin)
    }

    @Test fun denoiseOnlyOnFirstUpscalePass() {
        val s = JobSettings(Mode.PHOTO, 16, Tuning(0.8f), listOf(Stage.DENOISE, Stage.DE_JPEG))
        val p = Planner.plan(s, setOf(Packs.DENOISE, Packs.JPEG))
        assertEquals(listOf(PackModels.FBCNN, PackModels.DENOISE_50, BundledModels.GENERAL_X4V3), p.passes.map { it.model })
        assertEquals(16, p.outputScale)
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

    @Test fun generativeReplacesOnlyTheFirstPhotoPass() {
        val s = JobSettings(Mode.PHOTO, 8, Tuning.defaultFor(Mode.PHOTO), listOf(Stage.DENOISE), generative = true, pro = true)
        val all = setOf(Packs.GENERATIVE, Packs.DENOISE, Packs.PHOTO_PRO)
        val p = Planner.plan(s, all)
        assertEquals(listOf(PackModels.ADCSR, PackModels.X4PLUS), p.passes.map { it.model })
        assertEquals(8, p.outputScale)
        // 2× = generative 4× then area-downsample.
        val two = Planner.plan(s.copy(scale = 2), all)
        assertEquals(PackModels.ADCSR, two.model)
        assertEquals(2, two.downsample)
        // Without the pack, or in Anime mode, nothing changes.
        assertEquals(PackModels.DENOISE_50, Planner.plan(s.copy(scale = 4), all - Packs.GENERATIVE).model)
        assertEquals(BundledModels.ANIME_X4, Planner.plan(s.copy(mode = Mode.ANIME, scale = 4, pro = false), all).model)
    }

    @Test fun textModeUsesRealEsrNetWhenInstalled() {
        val s = JobSettings(Mode.TEXT, 2, Tuning.defaultFor(Mode.TEXT))
        val p = Planner.plan(s, setOf(Packs.TEXT))
        assertEquals(PackModels.REALESRNET, p.model)
        assertEquals(2, p.downsample)
        assertEquals(BundledModels.GENERAL_X4V3, Planner.plan(s, emptySet()).model)
        for (scale in Planner.supportedScales) assertEquals(scale, Planner.plan(s.copy(scale = scale), setOf(Packs.TEXT)).outputScale)
    }

    @Test fun generativeTileLimit() {
        assertEquals(1, Generative.tiles(96, 50))
        assertEquals(4 * 3, Generative.tiles(300, 200))   // matches the engine's tile plan
        assertTrue(Generative.fits(880, 720))
        assertFalse(Generative.fits(900, 700))
        assertFalse(Generative.fits(4080, 3072))           // a 12 MP camera photo: ~1,900 tiles
        assertTrue(Generative.tiles(4080, 3072) > 1800)
    }

    @Test fun repairIsNotAPass() {
        val s = JobSettings(Mode.PHOTO, 4, Tuning.defaultFor(Mode.PHOTO), listOf(Stage.REPAIR))
        assertEquals(listOf(BundledModels.GENERAL_X4V3), Planner.plan(s, setOf(Packs.REPAIR)).passes.map { it.model })
    }

    @Test fun generativeModelSpec() {
        val m = PackModels.ADCSR
        assertEquals(0, (m.tile + 2 * m.pad) % m.alignment)  // full tiles need no internal padding
        assertTrue(m.signedRange && m.statMatch && m.lowMemory)
        assertEquals(Packs.GENERATIVE, m.packId)
        assertTrue(Packs.all.first { it.id == Packs.GENERATIVE }.terms!!.contains("Open RAIL++-M"))
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
