package com.moyue.reader.parser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadabilityExtractorTest {
    @org.junit.Test fun chapterHeadingOutsideBodyWinsOverSiteTitle() {
        val page = ReadabilityExtractor().extract("<title>第一章论坛里的鬼故事。_神秘复苏_修真小说_蚂蚁文学</title><h1>第一章论坛里的鬼故事。</h1><div>" + "这是小说正文。".repeat(30) + "</div>", "https://example.com/1")
        org.junit.Assert.assertEquals("第一章论坛里的鬼故事。", page.title)
    }
    @org.junit.Test fun selfLinkIsNotNextChapter() {
        val page = ReadabilityExtractor().extract("<article>" + "这是小说正文。".repeat(30) + "</article><a href='#'>下一章</a>", "https://example.com/1")
        org.junit.Assert.assertNull(page.nextUrl)
    }

    @Test
    fun extractsArticleAndRemovesNavigationAdsCommentsAndScripts() {
        val html = """
            <html><head><title>风雪夜 · 第一章</title><script>steal()</script></head><body>
            <nav>首页 排行 登录</nav><div class="advert">立即充值</div>
            <article><h1>第一章 风起</h1><p>夜色渐深，远处的灯一点点亮起。</p><p>这是连续的第二段正文，足够用于识别。</p></article>
            <div class="comments">评论区 推荐小说</div>
            <a rel="next" href="/novel/2">下一章</a>
            </body></html>
        """.trimIndent()

        val result = ReadabilityExtractor().extract(html, "https://example.com/novel/1")

        assertEquals("第一章 风起", result.title)
        assertTrue(result.text.contains("夜色渐深"))
        assertFalse(result.text.contains("立即充值"))
        assertFalse(result.text.contains("评论区"))
        assertFalse(result.text.contains("steal"))
        assertEquals("https://example.com/novel/2", result.nextUrl)
    }

    @Test
    fun rejectsPageWithoutReadableBody() {
        val html = "<html><body><nav><a href='/'>首页</a></nav></body></html>"
        val result = runCatching { ReadabilityExtractor().extract(html, "https://example.com") }
        assertTrue(result.exceptionOrNull() is NoReadableContentException)
    }

    @Test
    fun keepsTextAfterNestedDivInDenseContentContainer() {
        val html = """
            <html><head><title>第三章</title></head><body>
            <div class="chapter-content">
              <p>开头正文足够长，用来模拟没有 article 标签的小说站点。</p>
              <div class="paragraph-group"><p>中间正文位于嵌套容器中。</p></div>
              <p>末尾正文必须保留，不能在内层 div 闭合处提前截断。</p>
            </div>
            </body></html>
        """.trimIndent()

        val result = ReadabilityExtractor().extract(html, "https://example.com/book/3")

        assertTrue(result.text.contains("开头正文"))
        assertTrue(result.text.contains("中间正文"))
        assertTrue(result.text.contains("末尾正文必须保留"))
    }
}
