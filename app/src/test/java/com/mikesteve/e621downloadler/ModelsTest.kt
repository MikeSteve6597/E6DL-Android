package com.mikesteve.e621downloadler

import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.data.Quality
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Post.from` 的解析兼容性测试。
 *
 * 背景：v1.0.0 就是因为 e621 把列表结构从 `{posts:[…]}` + `file/sample/preview`
 * 改成顶层数组 + `files.*` 而整页报错；这里用两份真实结构的样例把两条解析路径都钉住，
 * 以后站点再改版会先在单元测试里失败，而不是在用户手机上。
 */
class ModelsTest {

    private val newFormat = """
        {
          "id": 4005902,
          "created_at": "2026-11-20T12:00:00.000-05:00",
          "rating": "e",
          "score": { "up": 10, "down": 1, "total": 9 },
          "fav_count": 321,
          "is_favorited": true,
          "files": {
            "meta": { "md5": "0123456789abcdef0123456789abcdef", "ext": "png" },
            "original": { "width": 1920, "height": 1080, "url": "https://static.e621.net/original/a.png" },
            "sample": { "width": 850, "height": 478, "jpg": "https://static.e621.net/sample/a.jpg", "webp": "https://static.e621.net/sample/a.webp" },
            "preview": { "width": 300, "height": 169, "jpg": "https://static.e621.net/preview/a.jpg", "webp": null }
          },
          "tags": { "general": ["cat", "solo"], "artist": ["someone"] }
        }
    """.trimIndent()

    private val oldFormat = """
        {
          "id": 123,
          "score": 7,
          "rating": "s",
          "fav_count": 5,
          "md5": "aaaabbbbccccddddeeeeffff00001111",
          "file": { "width": 800, "height": 1200, "ext": "webm", "url": "https://static.e621.net/data/b.webm" },
          "sample": { "url": "https://static.e621.net/sample/b.jpg" },
          "preview": { "url": "https://static.e621.net/preview/b.jpg" },
          "tags": { "general": ["video"] }
        }
    """.trimIndent()

    @Test
    fun `parses new e621 format`() {
        val post = Post.from(JSONObject(newFormat))
        assertEquals(4005902L, post.id)
        assertEquals("0123456789abcdef0123456789abcdef", post.md5)
        assertEquals("png", post.ext)
        assertEquals(1920, post.width)
        assertEquals(1080, post.height)
        assertEquals(9, post.score)
        assertEquals("e", post.rating)
        assertEquals(321, post.favCount)
        assertTrue(post.isFavorited)
        assertFalse(post.isVideo)
        assertEquals("https://static.e621.net/original/a.png", post.fileUrl)
        assertEquals("https://static.e621.net/sample/a.jpg", post.sampleUrl)
        assertEquals("https://static.e621.net/preview/a.jpg", post.previewUrl)
        assertEquals(listOf("cat", "solo", "someone"), post.tagsFlat)
    }

    @Test
    fun `parses legacy e621 format`() {
        val post = Post.from(JSONObject(oldFormat))
        assertEquals(123L, post.id)
        assertEquals("aaaabbbbccccddddeeeeffff00001111", post.md5)
        assertEquals("webm", post.ext)
        assertEquals(800, post.width)
        assertEquals(1200, post.height)
        assertEquals(7, post.score)
        assertEquals(5, post.favCount)
        assertTrue(post.isVideo)
        assertEquals("https://static.e621.net/data/b.webm", post.fileUrl)
    }

    @Test
    fun `image url falls back through file sample preview`() {
        val post = Post.from(JSONObject(newFormat))
        assertEquals(post.fileUrl, post.imageUrl(Quality.ORIGINAL))
        assertEquals(post.sampleUrl, post.imageUrl(Quality.SAMPLE))
        assertEquals(post.previewUrl, post.imageUrl(Quality.PREVIEW))

        val onlyPreview = post.copy(fileUrl = null, sampleUrl = null)
        assertEquals(post.previewUrl, onlyPreview.imageUrl(Quality.ORIGINAL))
    }

    @Test
    fun `missing urls return null instead of crashing`() {
        val post = Post.from(JSONObject(oldFormat)).copy(fileUrl = null, sampleUrl = null, previewUrl = null)
        assertNull(post.imageUrl(Quality.ORIGINAL))
    }

    @Test
    fun `target filename matches desktop rule`() {
        val post = Post.from(JSONObject(newFormat))
        assertEquals("4005902_01234567.png", post.targetFilename())
    }

    @Test
    fun `withFavorite keeps count consistent`() {
        val post = Post.from(JSONObject(oldFormat)).copy(isFavorited = false, favCount = 5)
        val added = post.withFavorite(true)
        assertTrue(added.isFavorited)
        assertEquals(6, added.favCount)

        val removed = added.withFavorite(false)
        assertFalse(removed.isFavorited)
        assertEquals(5, removed.favCount)

        // 幂等：重复设成同一状态不改变计数
        assertEquals(added.favCount, added.withFavorite(true).favCount)
    }
}
