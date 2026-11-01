package app.rescale.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cheap degradation analysis on a full-resolution crop of the input (block grid preserved:
 * the crop must start at a multiple of 8 in stored pixel coordinates).
 */
data class Analysis(
    val width: Int,
    val height: Int,
    /** Estimated JPEG quality from the quantisation table, null if not a JPEG. */
    val jpegQuality: Int?,
    /** Ratio of luma steps on 8×8 block boundaries vs elsewhere (≈1 clean, >1.3 blocky). */
    val blockiness: Float,
    /** Estimated Gaussian noise sigma, in 0-255 levels (Immerkær). */
    val noiseSigma: Float,
    /** Mean absolute Laplacian on luma (higher = sharper). */
    val sharpness: Float,
    /** Fraction of the crop covered by its 32 most common (quantised) colours. */
    val paletteCoverage: Float,
    /** Fraction of pixels with almost no local gradient. */
    val flatFraction: Float,
    /** Fraction of pixels identical (±0.5 level) to their 4 neighbours: digital art has lots. */
    val exactFlatFraction: Float,
    val grayscale: Boolean,
) {
    val tiny: Boolean get() = min(width, height) < 256
    val heavyJpeg: Boolean get() = (jpegQuality != null && jpegQuality < 50) || blockiness > 1.45f
    val someJpeg: Boolean get() = (jpegQuality != null && jpegQuality < 80) || blockiness > 1.2f
    val noisy: Boolean get() = noiseSigma > 6f
    val blurry: Boolean get() = sharpness < 3f
    val looksLikeArt: Boolean
        get() = paletteCoverage > 0.55f && flatFraction > 0.45f && exactFlatFraction > 0.25f

    /** One-line, human description for the editor ("Small, heavily compressed photo"). */
    fun describe(): String {
        val adjectives = buildList {
            if (tiny) add("small")
            when {
                heavyJpeg -> add("heavily compressed")
                someJpeg -> add("compressed")
            }
            if (noisy) add("noisy")
            if (blurry) add("soft")
            if (grayscale) add("black-and-white")
        }
        val noun = if (looksLikeArt) "illustration" else "photo"
        val text = if (adjectives.isEmpty()) "clean $noun" else adjectives.joinToString(", ") + " $noun"
        return text.replaceFirstChar { it.uppercase() }
    }

    companion object {
        /**
         * @param argb packed ARGB pixels of the crop (row-major, [w]×[h]).
         * @param fullWidth/fullHeight size of the whole image (for "tiny").
         */
        fun of(argb: IntArray, w: Int, h: Int, fullWidth: Int, fullHeight: Int, jpegQuality: Int?): Analysis {
            require(argb.size >= w * h && w >= 3 && h >= 3)
            val y = FloatArray(w * h)
            var chromaSum = 0.0
            for (i in 0 until w * h) {
                val p = argb[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                y[i] = 0.299f * r + 0.587f * g + 0.114f * b
                chromaSum += max(abs(r - g), max(abs(g - b), abs(r - b)))
            }
            val grayscale = chromaSum / (w * h) < 3.0

            // Blockiness: horizontal neighbour differences at x%8==7 vs the rest.
            var bSum = 0.0; var bN = 0; var oSum = 0.0; var oN = 0
            for (row in 0 until h) for (x in 0 until w - 1) {
                val d = abs(y[row * w + x + 1] - y[row * w + x]).toDouble()
                if (x % 8 == 7) { bSum += d; bN++ } else { oSum += d; oN++ }
            }
            for (row in 0 until h - 1) for (x in 0 until w) {
                val d = abs(y[(row + 1) * w + x] - y[row * w + x]).toDouble()
                if (row % 8 == 7) { bSum += d; bN++ } else { oSum += d; oN++ }
            }
            val blockiness = if (bN == 0 || oSum < 1e-6) 1f else ((bSum / bN) / max(oSum / oN, 0.05)).toFloat()

            // Immerkær noise estimate, skipping strong edges; plus Laplacian sharpness
            // and flat fraction from the same 3×3 neighbourhoods.
            var nSum = 0.0; var nCount = 0
            var lapSum = 0.0
            var flat = 0
            var exactFlat = 0
            for (row in 1 until h - 1) for (x in 1 until w - 1) {
                val c = row * w + x
                val gx = abs(y[c + 1] - y[c - 1])
                val gy = abs(y[c + w] - y[c - w])
                val lap = abs(4 * y[c] - y[c - 1] - y[c + 1] - y[c - w] - y[c + w])
                lapSum += lap
                if (gx + gy < 2f) flat++
                if (abs(y[c] - y[c - 1]) < 0.5f && abs(y[c] - y[c + 1]) < 0.5f &&
                    abs(y[c] - y[c - w]) < 0.5f && abs(y[c] - y[c + w]) < 0.5f
                ) exactFlat++
                if (gx + gy < 20f) {
                    val m = y[c - w - 1] - 2 * y[c - w] + y[c - w + 1] -
                        2 * y[c - 1] + 4 * y[c] - 2 * y[c + 1] +
                        y[c + w - 1] - 2 * y[c + w] + y[c + w + 1]
                    nSum += abs(m); nCount++
                }
            }
            val inner = (w - 2) * (h - 2)
            val noise = if (nCount == 0) 0f else (sqrt(Math.PI / 2) * nSum / (6.0 * nCount)).toFloat()

            // Palette coverage: 4 bits per channel histogram.
            val hist = IntArray(4096)
            for (i in 0 until w * h) {
                val p = argb[i]
                hist[((p shr 20) and 0xF shl 8) or ((p shr 12) and 0xF shl 4) or ((p shr 4) and 0xF)]++
            }
            hist.sort()
            var top = 0L
            for (i in 0 until 32) top += hist[hist.size - 1 - i]

            return Analysis(
                width = fullWidth,
                height = fullHeight,
                jpegQuality = jpegQuality,
                blockiness = blockiness,
                noiseSigma = noise,
                sharpness = (lapSum / inner).toFloat(),
                paletteCoverage = top.toFloat() / (w * h),
                flatFraction = flat.toFloat() / inner,
                exactFlatFraction = exactFlat.toFloat() / inner,
                grayscale = grayscale,
            )
        }
    }
}

/** Reads the luminance quantisation table from a JPEG header and estimates quality (libjpeg scale). */
object JpegQuality {
    private val STD_LUMA = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
    )

    /** [header]: the first bytes of the file (64 KB is plenty). */
    fun estimate(header: ByteArray): Int? {
        if (header.size < 4 || header[0] != 0xFF.toByte() || header[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 4 <= header.size) {
            if (header[i] != 0xFF.toByte()) return null
            val marker = header[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { i += 2; continue }
            val len = ((header[i + 2].toInt() and 0xFF) shl 8) or (header[i + 3].toInt() and 0xFF)
            if (marker == 0xDA) return null // start of scan, no DQT found
            if (marker == 0xDB) {
                var p = i + 4
                val end = i + 2 + len
                while (p < end && p < header.size) {
                    val pq = (header[p].toInt() shr 4) and 0xF
                    val tq = header[p].toInt() and 0xF
                    p++
                    val size = if (pq == 0) 64 else 128
                    if (tq == 0 && p + size <= header.size) {
                        // DQT stores entries in zigzag order; compare against zigzag of the std table.
                        var scaleSum = 0.0
                        for (k in 0 until 64) {
                            val q = if (pq == 0) header[p + k].toInt() and 0xFF
                            else ((header[p + 2 * k].toInt() and 0xFF) shl 8) or (header[p + 2 * k + 1].toInt() and 0xFF)
                            scaleSum += q * 100.0 / STD_LUMA[ZIGZAG[k]]
                        }
                        val s = scaleSum / 64
                        val quality = if (s <= 100) (200 - s) / 2 else 5000 / s
                        return quality.toInt().coerceIn(1, 100)
                    }
                    p += size
                }
            }
            i += 2 + len
        }
        return null
    }

    private val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
    )
}
