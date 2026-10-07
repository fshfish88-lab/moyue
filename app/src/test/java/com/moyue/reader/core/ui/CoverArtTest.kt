package com.moyue.reader.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated cover is deterministic by design, so these are the guarantees the old
 * `title.hashCode() % 4` implementation could not make.
 */
class CoverArtTest {

    @Test
    fun sameBookAlwaysProducesSameLayout() {
        val first = CoverArt.layout("诡秘之主", "爱潜水的乌贼")
        val second = CoverArt.layout("诡秘之主", "爱潜水的乌贼")
        assertEquals(first.palette, second.palette)
        assertEquals(first.titleTop, second.titleTop, 0f)
        assertEquals(first.ridges, second.ridges)
        assertEquals(first.ornaments, second.ornaments)
    }

    /**
     * The old generator only had four palettes, so collisions were routine. Sample a realistic
     * shelf and require that nearly every book gets a distinct colour family.
     */
    @Test
    fun differentTitlesGetDifferentPalettes() {
        val titles = listOf(
            "诡秘之主", "三体", "凡人修仙传", "活着", "百年孤独", "红楼梦",
            "西游记", "水浒传", "三国演义", "平凡的世界", "白鹿原", "围城",
            "边城", "骆驼祥子", "子夜", "家", "春", "秋", "雷雨", "日出",
        )
        val palettes = titles.map { CoverArt.layout(it, null).palette.sky }.toSet()
        // A handful of near-collisions is fine; the four-palette version could only ever yield 4.
        assertTrue("expected >12 distinct skies, got ${palettes.size}", palettes.size > 12)
    }

    @Test
    fun authorAffectsTheArtwork() {
        val withAuthor = CoverArt.layout("同名书", "作者甲").palette
        val otherAuthor = CoverArt.layout("同名书", "作者乙").palette
        assertNotEquals(withAuthor, otherAuthor)
    }

    @Test
    fun ridgesStayBelowTheTitleBlock() {
        // The title occupies the top of the canvas; scenery must never intrude into it, or the
        // last title line renders on top of a ridge.
        val titles = listOf("城", "三体", "诡秘之主", "平凡的世界", "百年孤独长篇小说名", "a very long english novel title")
        for (title in titles) {
            val layout = CoverArt.layout(title, null)
            val lastBaseline = layout.titleBaseline + (layout.titleLineCount - 1) * layout.titleLineHeight
            val titleBottom = lastBaseline + layout.titleSize * .25f
            val highestRidge = layout.ridges.minOf { ridge -> ridge.points.minOf { it.second } * CoverArt.HEIGHT }
            assertTrue(
                "title '$title' bottom=$titleBottom overlaps ridge at $highestRidge",
                highestRidge > titleBottom,
            )
        }
    }

    @Test
    fun everyPaletteStaysInTheMutedBand() {
        for (title in listOf("诡秘之主", "三体", "凡人修仙传", "活着", "红楼梦", "围城", "abc")) {
            val palette = CoverArt.layout(title, null).palette
            for (color in listOf(palette.sky, palette.ghost, palette.far, palette.near)) {
                for (channel in listOf(color.r, color.g, color.b)) {
                    assertTrue("channel $channel out of range for $title", channel in 0f..1f)
                }
            }
            // Paper stock: the sky should be light, the near ridge should be dark.
            assertTrue("sky too dark for $title", palette.sky.luminance() > .80f)
            assertTrue("near ridge too light for $title", palette.near.luminance() < .60f)
        }
    }

    @Test
    fun wrappingRespectsTheLineBudget() {
        val lines = CoverArt.wrap("这是一个特别长的中文小说标题需要用三行排下来", 3)
        assertTrue(lines.size <= 3)
        assertEquals("这是一个特别长的中文小说标题需要用三行排下来", lines.joinToString(""))

        assertEquals(listOf("三体"), CoverArt.wrap("三体", 1))
        assertEquals(listOf("墨阅"), CoverArt.wrap("   ", 2))
    }

    @Test
    fun typeScaleStepsDownForLongTitles() {
        val short = CoverArt.layout("三体", null)
        val long = CoverArt.layout("这是一个特别长的中文小说标题需要用三行", null)
        assertTrue(short.titleSize > long.titleSize)
        assertEquals(1, short.titleLineCount)
        assertTrue(long.titleLineCount > 1)
    }

    @Test
    fun hslProducesExpectedPrimaries() {
        val red = CoverArt.hsl(0f, 1f, .5f)
        assertEquals(1f, red.r, .01f)
        assertEquals(0f, red.g, .01f)
        assertEquals(0f, red.b, .01f)

        val neutral = CoverArt.hsl(.3f, 0f, .5f)
        assertEquals(neutral.r, neutral.g, .001f)
        assertEquals(neutral.g, neutral.b, .001f)

        // Half lightness with full saturation is the pure hue, so this must be full red.
        assertEquals("#FF0000", CoverArt.toHex(red))
        assertEquals("#808080", CoverArt.toHex(CoverArt.hsl(.3f, 0f, .5f)))
    }

    @Test
    fun negativeAndOverflowingHuesWrap() {
        assertEquals(CoverArt.hsl(0f, .4f, .5f), CoverArt.hsl(1f, .4f, .5f))
        assertEquals(CoverArt.hsl(.25f, .4f, .5f), CoverArt.hsl(1.25f, .4f, .5f))
        assertEquals(CoverArt.hsl(.75f, .4f, .5f), CoverArt.hsl(-.25f, .4f, .5f))
    }
}
