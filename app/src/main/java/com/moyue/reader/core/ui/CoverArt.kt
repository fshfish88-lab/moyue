package com.moyue.reader.core.ui

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Deterministic generated-cover artwork.
 *
 * Two rules this file exists to enforce:
 *
 * 1. The cover drawn on the shelf and the cover written to disk during import must be the same
 *    picture. They used to be two unrelated implementations (a Compose mountain path vs. a bitmap
 *    vertical gradient), so a book looked one way in the grid and another way in its file.
 * 2. Two different books must not look identical. The old generator picked one of four fixed
 *    palettes by `title.hashCode() % 4` and drew a fixed silhouette, so any two titles whose hashes
 *    landed on the same slot were pixel-identical.
 *
 * The fix is to derive every parameter — hue, ridge shape, moon position, ornaments, type scale —
 * from a seeded stream keyed on the title. Nothing here touches Android or Compose, so it is
 * unit-testable and cheap to call.
 */
internal object CoverArt {

    /** Reference design box. Everything below is expressed in these units and scaled at draw time. */
    const val WIDTH = 600f
    const val HEIGHT = 840f

    /** Title sits in the top 46% of the canvas; scenery never rises above [HORIZON_MIN]. */
    private const val TITLE_TOP_MIN = 0.09f
    private const val TITLE_TOP_MAX = 0.30f
    private const val HORIZON_MIN = 0.56f

    fun layout(title: String, author: String?): CoverLayout {
        val seed = seedOf(title, author)
        val ink = Lcg(seed)
        val palette = paletteFor(seed)
        val type = typeFor(title)

        // Horizon is derived from the title block, not the other way round: a long three-line title
        // pushes the scenery down instead of being overlapped by it.
        val titleTop = TITLE_TOP_MIN * HEIGHT
        val titleBlock = type.titleSize * type.lines * 1.34f
        val horizon = (titleTop / HEIGHT + titleBlock / HEIGHT + .09f).coerceAtLeast(HORIZON_MIN)

        val zone = compositionFor(seed, horizon)

        val ornaments = ArrayList<Ornament>()
        ornaments += Ornament.Moon(
            x = WIDTH * if (zone.moonLeft) .27f else .73f,
            y = HEIGHT * (titleTop / HEIGHT + .10f),
            radius = WIDTH * zone.moonRadius,
        )
        if (zone.band) {
            ornaments += Ornament.Band(y = HEIGHT * (titleTop / HEIGHT + .17f), alpha = .35f)
        }

        val ridges = buildRidges(zone, ink, palette)
        val rules = if (zone.showRules) {
            listOf(Ornament.Rule(HEIGHT * .925f, .16f))
        } else emptyList()

        val authorText = author?.trim()?.takeIf { it.isNotBlank() }?.take(12)

        return CoverLayout(
            palette = palette,
            ridges = ridges,
            ornaments = ornaments + rules,
            titleText = title,
            titleSize = type.titleSize,
            titleTop = titleTop,
            titleLineCount = type.lines,
            titleLineHeight = type.titleSize * 1.34f,
            // Baseline of the first line, shared by both renderers so the on-screen cover and the
            // PNG written at import time land on the same pixels.
            titleBaseline = titleTop + type.titleSize * .94f,
            subtitle = authorText,
            subtitleY = HEIGHT * .945f,
            subtitleSize = 15f,
            ink = palette.ink,
        )
    }

    // ---------------------------------------------------------------- composition

    private data class Zone(
        val horizon: Float,
        val moonLeft: Boolean,
        val moonRadius: Float,
        val band: Boolean,
        val showRules: Boolean,
        val backAmp: Float,
        val frontAmp: Float,
        val ghost: Boolean,
    )

    /**
     * Eight composition templates. Ridge amplitude stays low (0.04–0.11 of canvas height) because a
     * busy silhouette on a three-column grid tile reads as noise rather than as scenery.
     */
    private fun compositionFor(seed: Int, horizon: Float): Zone = when ((seed ushr 3 and 0x7) % 8) {
        0 -> Zone(horizon, false, .026f, false, false, .07f, .10f, false)
        1 -> Zone(horizon, true, .021f, true, true, .06f, .08f, true)
        2 -> Zone(horizon, false, .029f, false, true, .05f, .09f, false)
        3 -> Zone(horizon, true, .024f, true, false, .08f, .11f, true)
        4 -> Zone(horizon, false, .019f, false, true, .04f, .07f, false)
        5 -> Zone(horizon, true, .027f, true, true, .06f, .09f, true)
        6 -> Zone(horizon, false, .022f, false, false, .07f, .12f, false)
        else -> Zone(horizon, true, .025f, true, false, .05f, .10f, true)
    }

    private fun buildRidges(zone: Zone, ink: Lcg, palette: CoverPalette): List<Ridge> {
        val ridges = ArrayList<Ridge>(3)
        val start = .64f + ink.next() * .22f

        if (zone.ghost) {
            ridges += Ridge(palette.ghost, zone.horizon - .07f, crest(ink, zone.backAmp * .8f, 3, start))
        }
        ridges += Ridge(palette.far, zone.horizon, crest(ink, zone.backAmp, 4, start + .1f))
        ridges += Ridge(palette.near, zone.horizon + .12f, crest(ink, zone.frontAmp, 3, start + .2f))
        return ridges
    }

    /** A ridge polyline as (x, y) pairs across the full width, closed along the bottom edge. */
    private fun crest(ink: Lcg, amplitude: Float, segments: Int, base: Float): List<Pair<Float, Float>> {
        val points = ArrayList<Pair<Float, Float>>(segments + 2)
        points += 0f to base + ink.signed() * amplitude * .4f
        for (index in 1 until segments) {
            val x = index.toFloat() / segments
            // Alternate above/below the baseline so the ridge reads as peaks and saddles.
            val bias = if (index % 2 == 0) -.55f else .45f
            val y = base + (bias + ink.signed() * .7f) * amplitude
            points += x to y
        }
        points += 1f to base + ink.signed() * amplitude * .4f
        return points
    }

    // ---------------------------------------------------------------- colour

    private fun paletteFor(seed: Int): CoverPalette {
        val hue = (seed ushr 9 and 0x1FF) / 512f
        val warm = (seed ushr 5) and 1 == 1
        // Lock saturation into a narrow band so every cover stays low-saturation paper stock
        // regardless of which hue the seed lands on.
        val sky = hsl(hue, if (warm) .16f else .12f, .946f)
        val ghost = hsl(hue + .02f, .11f, .885f)
        val far = hsl(hue + .01f, .15f, .715f)
        val near = hsl(hue + .03f, .20f + (seed ushr 2 and 0x3) / 100f, .40f + (seed and 0x7) / 100f)
        val ink = if (sky.luminance() > .5f) hsl(hue, .22f, .17f) else hsl(hue, .10f, .90f)
        return CoverPalette(sky = sky, ghost = ghost, far = far, near = near, ink = ink)
    }

    // ---------------------------------------------------------------- typography

    private data class CoverType(val titleSize: Float, val lines: Int)

    /**
     * Short titles get room to breathe; long ones step down so three lines still fit above the
     * ridge. Wrapping is manual (chunked) rather than measured, so the bitmap and Compose paths
     * must not disagree about where the breaks fall.
     */
    private fun typeFor(title: String): CoverType {
        val glyphs = title.trim().ifEmpty { "墨阅" }
        return when {
            glyphs.length <= 3 -> CoverType(58f, 1)
            glyphs.length <= 5 -> CoverType(50f, 2)
            glyphs.length <= 8 -> CoverType(42f, 2)
            glyphs.length <= 12 -> CoverType(34f, 3)
            else -> CoverType(28f, 3)
        }
    }

    fun wrap(text: String, lines: Int): List<String> {
        val glyphs = text.trim()
        if (glyphs.isEmpty()) return listOf("墨阅")
        val perLine = kotlin.math.ceil(glyphs.length / lines.toFloat()).toInt().coerceAtLeast(1)
        return glyphs.chunked(perLine).take(lines)
    }

    // ---------------------------------------------------------------- seeded stream

    /**
     * A tiny LCG. `seed + index` style hashing was the old bug: `"凡人修仙传".hashCode()` and
     * similar strings can share residues, so titles collided onto the same cover. The generator is
     * mixed once and then advanced, so neighbouring seeds diverge immediately.
     */
    private class Lcg(seed: Int) {
        private var state = seed.toLong() * 0x2545F4914F6CDD1DL
        fun next(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            val bits = (state ushr 33).toInt() and 0x7FFFFFFF
            return bits / 0x7FFFFFFF.toFloat()
        }
        /** Uniform in [-1, 1). */
        fun signed(): Float = next() * 2f - 1f
    }

    fun seedOf(title: String, author: String?): Int {
        var hash = 0x811C9DC5.toInt()
        for (character in title.trim()) {
            hash = (hash xor character.code) * 16777619
        }
        for (character in author.orEmpty().trim()) {
            hash = (hash xor character.code) * 16777619
        }
        // Final avalanche so short titles that differ by one code point do not land adjacent.
        hash = hash xor (hash ushr 15)
        return hash * 0x2545F491.toInt()
    }

    // ---------------------------------------------------------------- HSL

    /**
     * HSL to sRGB. Working in HSL is what lets one seed produce a coherent family (sky, two ridge
     * tones, ink) that varies smoothly across books but never leaves the muted band.
     */
    fun hsl(hue: Float, saturation: Float, lightness: Float): RGB {
        val h = ((hue % 1f) + 1f) % 1f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        val chroma = (1f - abs(2f * l - 1f)) * s
        val sector = h * 6f
        val x = chroma * (1f - abs(sector % 2f - 1f))
        val (r, g, b) = when (sector.toInt()) {
            0 -> Triple(chroma, x, 0f)
            1 -> Triple(x, chroma, 0f)
            2 -> Triple(0f, chroma, x)
            3 -> Triple(0f, x, chroma)
            4 -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val m = l - chroma / 2f
        return RGB((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
    }

    fun toHex(rgb: RGB): String {
        val r = (rgb.r * 255f).roundToInt().coerceIn(0, 255)
        val g = (rgb.g * 255f).roundToInt().coerceIn(0, 255)
        val b = (rgb.b * 255f).roundToInt().coerceIn(0, 255)
        // Locale-independent by construction: String.format would render non-ASCII digits under
        // locales that use them.
        return "#" + hex(r) + hex(g) + hex(b)
    }

    private fun hex(value: Int): String = value.toString(16).uppercase().padStart(2, '0')
}

/** Platform-neutral colour, so this file stays free of Compose and Android types. */
internal data class RGB(val r: Float, val g: Float, val b: Float) {
    /** Rec. 709 relative luminance, used to decide whether cover text should be light or dark. */
    fun luminance(): Float = .2126f * r + .7152f * g + .0722f * b
}

internal data class CoverPalette(
    val sky: RGB,
    val ghost: RGB,
    val far: RGB,
    val near: RGB,
    val ink: RGB,
)

internal data class Ridge(
    val color: RGB,
    /** Baseline of this ridge as a fraction of canvas height. */
    val baseline: Float,
    /** Full-width polyline: normalised x paired with y as a fraction of canvas height. */
    val points: List<Pair<Float, Float>>,
)

internal sealed interface Ornament {
    data class Moon(val x: Float, val y: Float, val radius: Float) : Ornament
    /** A soft horizontal wash under the title block, standing in for a distant haze. */
    data class Band(val y: Float, val alpha: Float) : Ornament
    /** A hairline at the given absolute y, spanning the given fraction of the width. */
    data class Rule(val y: Float, val width: Float) : Ornament
}

internal data class CoverLayout(
    val palette: CoverPalette,
    val ridges: List<Ridge>,
    val ornaments: List<Ornament>,
    val titleText: String,
    val titleSize: Float,
    val titleTop: Float,
    val titleLineCount: Int,
    val titleLineHeight: Float,
    /** Baseline y of the first title line, in reference units. */
    val titleBaseline: Float,
    val subtitle: String?,
    val subtitleY: Float,
    val subtitleSize: Float,
    val ink: RGB,
)
