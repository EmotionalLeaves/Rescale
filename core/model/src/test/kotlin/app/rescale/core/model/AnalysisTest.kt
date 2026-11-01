package app.rescale.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.random.Random

class AnalysisTest {
    /** Smooth, photo-like content: overlapping soft gradients and blobs. */
    private fun photo(w: Int = 320, h: Int = 240): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val r = (128 + 90 * Math.sin(x / 23.0) * Math.cos(y / 31.0)).toInt()
            val g = (110 + 70 * Math.sin((x + y) / 41.0)).toInt()
            val b = (90 + 60 * Math.cos(x / 17.0 - y / 29.0)).toInt()
            img.setRGB(x, y, (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255))
        }
        return img
    }

    /** Cel-shaded "art": flat fills with dark outlines. */
    private fun art(w: Int = 320, h: Int = 240): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(250, 240, 220); g.fillRect(0, 0, w, h)
        g.stroke = BasicStroke(3f)
        val fills = listOf(Color(240, 120, 140), Color(90, 160, 230), Color(250, 210, 90), Color(120, 200, 140))
        for ((i, c) in fills.withIndex()) {
            g.color = c; g.fillOval(20 + i * 70, 40 + (i % 2) * 60, 90, 110)
            g.color = Color(30, 30, 40); g.drawOval(20 + i * 70, 40 + (i % 2) * 60, 90, 110)
        }
        g.dispose()
        return img
    }

    private fun jpeg(img: BufferedImage, quality: Float): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        writer.output = ImageIO.createImageOutputStream(out)
        writer.write(null, IIOImage(img, null, null), param)
        writer.dispose()
        return out.toByteArray()
    }

    private fun analyse(img: BufferedImage, jpegQ: Int? = null): Analysis {
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        return Analysis.of(px, img.width, img.height, img.width, img.height, jpegQ)
    }

    private fun addNoise(img: BufferedImage, sigma: Double): BufferedImage {
        val rnd = Random(7)
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            fun ch(v: Int) = (v + rnd.nextGaussianCompat() * sigma).toInt().coerceIn(0, 255)
            out.setRGB(x, y, (ch((p shr 16) and 255) shl 16) or (ch((p shr 8) and 255) shl 8) or ch(p and 255))
        }
        return out
    }

    private fun Random.nextGaussianCompat(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12); val u2 = nextDouble()
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2)
    }

    @Test fun cleanPhotoIsClean() {
        val a = analyse(photo())
        assertFalse(a.someJpeg); assertFalse(a.noisy); assertFalse(a.looksLikeArt); assertFalse(a.grayscale)
        assertTrue(a.tiny) // 240 px short side
        assertEquals(Mode.PHOTO, AutoPlanner.plan(a).settings.mode)
    }

    @Test fun jpegQualityIsReadFromHeader() {
        for (q in listOf(20, 50, 90)) {
            val est = JpegQuality.estimate(jpeg(photo(), q / 100f))
            assertNotNull(est)
            assertTrue("q=$q est=$est", kotlin.math.abs(est!! - q) <= 3)
        }
        assertNull(JpegQuality.estimate(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }

    @Test fun heavyJpegIsDetectedFromPixelsToo() {
        val decoded = ImageIO.read(ByteArrayInputStream(jpeg(photo(), 0.1f)))
        val a = analyse(decoded, jpegQ = null)
        assertTrue("blockiness=${a.blockiness}", a.someJpeg)
    }

    @Test fun noiseIsEstimated() {
        val a = analyse(addNoise(photo(), 15.0))
        assertTrue("sigma=${a.noiseSigma}", a.noiseSigma in 10f..20f)
        assertTrue(a.noisy)
        assertTrue(analyse(photo()).noiseSigma < 3f)
    }

    @Test fun artIsRecognised() {
        val a = analyse(art())
        assertTrue("coverage=${a.paletteCoverage} flat=${a.flatFraction}", a.looksLikeArt)
        assertEquals(Mode.ANIME, AutoPlanner.plan(a).settings.mode)
        // Still art after moderate JPEG compression.
        val j = analyse(ImageIO.read(ByteArrayInputStream(jpeg(art(), 0.8f))))
        assertTrue("jpeg art exact=${j.exactFlatFraction}", j.looksLikeArt)
    }

    @Test fun smoothSkyIsNotArt() {
        // A big smooth vertical gradient (sky) with a little sensor noise.
        val w = 320; val h = 240
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val v = 120 + y * 100 / h
            img.setRGB(x, y, ((v - 40) shl 16) or ((v - 10) shl 8) or v)
        }
        assertFalse(analyse(addNoise(img, 1.5)).looksLikeArt)
    }

    @Test fun grayscaleTurnsOnKeepColors() {
        val src = photo()
        val gray = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val p = src.getRGB(x, y); val v = ((p shr 16 and 255) + (p shr 8 and 255) + (p and 255)) / 3
            gray.setRGB(x, y, (v shl 16) or (v shl 8) or v)
        }
        val a = analyse(gray)
        assertTrue(a.grayscale)
        assertTrue(AutoPlanner.plan(a).settings.tuning.lumaOnly)
    }

    @Test fun planLowersDetailForDamagedPhotosAndUsesStagesWhenAvailable() {
        val a = analyse(addNoise(photo(), 15.0), jpegQ = 30)
        val without = AutoPlanner.plan(a)
        assertTrue(without.settings.tuning.detail <= 0.6f)
        assertTrue(without.settings.stages.isEmpty())
        val with = AutoPlanner.plan(a, setOf(Stage.DE_JPEG, Stage.DENOISE))
        assertEquals(listOf(Stage.DE_JPEG, Stage.DENOISE), with.settings.stages)
        assertEquals("De-JPEG", with.chips.first())
    }

    @Test fun bigImagesGet2x() {
        val a = analyse(photo()).copy(width = 4000, height = 3000)
        assertEquals(2, AutoPlanner.plan(a).settings.scale)
    }
}
