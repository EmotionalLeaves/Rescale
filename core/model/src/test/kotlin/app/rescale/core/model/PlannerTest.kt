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

    @Test fun estimate() {
        val e = OutputEstimate.of(1000, 500, 4)
        assertEquals(4000L, e.width)
        assertEquals(2000L, e.height)
        assertEquals(8.0, e.megapixels, 1e-9)
    }
}
