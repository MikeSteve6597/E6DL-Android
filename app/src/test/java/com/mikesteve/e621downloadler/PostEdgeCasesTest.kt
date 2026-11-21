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
 * `Post.from` 的边界与容错测试。
 *
 * 断言全部按 `data/Models.kt` **现有实现的实际行为**编写（不是「理想行为」）：
 *  - 缺字段 / null → 空串、0、空表，不抛异常；
 *  - `files.meta` 存在但里面没有 `ext` 时，`optString` 返回空串（不是 null），
 *    因此不会回退到旧结构 `file.ext`，实际 ext 为空串；
 *  - md5 不足 8 位时 `targetFilename` 直接 `take(8)`，不补齐、不前导零。
 */
class PostEdgeCasesTest {

    private fun samplePost(
        id: Long = 1L,
        md5: String = "0123456789abcdef",
        ext: String = "png",
        fileUrl: String? = "F",
        sampleUrl: String? = "S",
        previewUrl: String? = "P",
        width: Int = 1920,
        height: Int = 1080,
        score: Int = 9,
        rating: String = "s",
        tags: Map<String, List<String>> = mapOf("general" to listOf("cat")),
        isFavorited: Boolean = false,
        favCount: Int = 0,
    ): Post = Post(
        id = id,
        md5 = md5,
        ext = ext,
        fileUrl = fileUrl,
        sampleUrl = sampleUrl,
        previewUrl = previewUrl,
        width = width,
        height = height,
        score = score,
        rating = rating,
        tags = tags,
        isFavorited = isFavorited,
        favCount = favCount,
    )

    private fun ratingLabelOf(value: Any?): String =
        Post.from(JSONObject().put("rating", value ?: JSONObject.NULL)).ratingLabel

    @Test
    fun `missing and null fields fall back to defaults`() {
        val bare = Post.from(JSONObject("""{"id":42}"""))
        assertEquals(42L, bare.id)
        assertEquals("", bare.md5)
        assertEquals("jpg", bare.ext)
        assertFalse(bare.isVideo)
        assertNull(bare.fileUrl)
        assertNull(bare.sampleUrl)
        assertNull(bare.previewUrl)
        assertEquals(0, bare.width)
        assertEquals(0, bare.height)
        assertEquals(0, bare.score)
        assertEquals("", bare.rating)
        assertEquals("未知", bare.ratingLabel)
        assertTrue(bare.tags.isEmpty())
        assertTrue(bare.tagsFlat.isEmpty())
        assertFalse(bare.isFavorited)
        assertEquals(0, bare.favCount)
        assertEquals("42_.jpg", bare.targetFilename())

        val nulls = Post.from(
            JSONObject(
                """{"id":7,"md5":null,"rating":null,"score":null,"fav_count":null,"tags":null,"file":null,"files":null,"is_favorited":null}""",
            ),
        )
        assertEquals(7L, nulls.id)
        assertEquals("", nulls.md5)
        assertEquals("jpg", nulls.ext)
        assertEquals("", nulls.rating)
        assertEquals("未知", nulls.ratingLabel)
        assertEquals(0, nulls.score)
        assertEquals(0, nulls.favCount)
        assertFalse(nulls.isFavorited)
        assertTrue(nulls.tags.isEmpty())
        assertNull(nulls.fileUrl)
        assertEquals("7_.jpg", nulls.targetFilename())
    }

    @Test
    fun `tags tolerate empty object empty arrays and non array values`() {
        val none = Post.from(JSONObject("""{"id":1,"tags":{}}"""))
        assertTrue(none.tags.isEmpty())
        assertTrue(none.tagsFlat.isEmpty())

        val mixed = Post.from(
            JSONObject(
                """{"id":1,"tags":{"general":[],"artist":["a1","a2"],"species":[null,"canine"],"lore":"not-an-array"}}""",
            ),
        )
        assertEquals(3, mixed.tags.size)
        assertEquals(emptyList<String>(), mixed.tags["general"])
        assertEquals(listOf("a1", "a2"), mixed.tags["artist"])
        // 数组里的 null 经 optString 变成空串，条目本身不会丢。
        assertEquals(listOf("", "canine"), mixed.tags["species"])
        // 非数组值被当作没有，键不进入 tags。
        assertNull(mixed.tags["lore"])
        assertEquals(4, mixed.tagsFlat.size)
        assertTrue(mixed.tagsFlat.containsAll(listOf("a1", "a2", "", "canine")))
    }

    @Test
    fun `rating label maps known values and falls back to unknown`() {
        assertEquals("安全", ratingLabelOf("s"))
        assertEquals("待定", ratingLabelOf("q"))
        assertEquals("明确", ratingLabelOf("e"))
        assertEquals("未知", ratingLabelOf("x"))
        // 实际行为：rating 不做大小写归一，"S" 也落到未知。
        assertEquals("未知", ratingLabelOf("S"))
        assertEquals("未知", ratingLabelOf(""))
        assertEquals("未知", ratingLabelOf(null))
        // rating 键完全缺失同样是未知。
        assertEquals("未知", Post.from(JSONObject()).ratingLabel)
    }

    @Test
    fun `extension is lowercased and video extensions are detected case insensitively`() {
        val legacyWebm = Post.from(JSONObject("""{"id":1,"file":{"ext":"WEBM","url":"https://s/v.webm"}}"""))
        assertEquals("webm", legacyWebm.ext)
        assertTrue(legacyWebm.isVideo)

        val newMp4 = Post.from(JSONObject("""{"id":2,"files":{"meta":{"ext":"MP4"}}}"""))
        assertEquals("mp4", newMp4.ext)
        assertTrue(newMp4.isVideo)

        val legacySwf = Post.from(JSONObject("""{"id":3,"file":{"ext":"swf"}}"""))
        assertTrue(legacySwf.isVideo)

        val png = Post.from(JSONObject("""{"id":4,"file":{"ext":"PNG"}}"""))
        assertEquals("png", png.ext)
        assertFalse(png.isVideo)

        val gif = Post.from(JSONObject("""{"id":5,"files":{"meta":{"ext":"gif"}}}"""))
        assertFalse(gif.isVideo)
    }

    @Test
    fun `meta without ext does not fall back to legacy file ext`() {
        val post = Post.from(
            JSONObject(
                """{"id":9,"files":{"meta":{}},"file":{"ext":"png","url":"https://s/legacy.png"}}""",
            ),
        )
        // 实际行为：meta 非 null 时 ext 取 meta.optString("ext")，得到空串，
        // 不会回退到旧结构 file.ext（elvis 只在 null 时才回退）。
        assertEquals("", post.ext)
        assertFalse(post.isVideo)
        assertEquals("9_.", post.targetFilename())
        // 但 URL 是逐字段回退的，旧 file.url 仍然生效。
        assertEquals("https://s/legacy.png", post.fileUrl)
    }

    @Test
    fun `urls fall back from new files section to legacy fields`() {
        val newStyle = Post.from(
            JSONObject(
                """
                {
                  "id": 3,
                  "files": {
                    "meta": {"md5": "0123456789abcdef", "ext": "png"},
                    "original": {"jpg": "https://s/orig.jpg", "webp": "https://s/orig.webp"},
                    "sample": {"url": "", "jpg": "https://s/sample.jpg"},
                    "preview": {"webp": "https://s/preview.webp"}
                  }
                }
                """.trimIndent(),
            ),
        )
        // url 键缺失 → 用 jpg；url 为空串 → 用 jpg；没有 url/jpg → 用 webp。
        assertEquals("https://s/orig.jpg", newStyle.fileUrl)
        assertEquals("https://s/sample.jpg", newStyle.sampleUrl)
        assertEquals("https://s/preview.webp", newStyle.previewUrl)

        val legacyFallback = Post.from(
            JSONObject(
                """{"id":4,"files":{"original":{"width":10,"height":20}},"file":{"ext":"jpg","url":"https://s/old.jpg"},"sample":{"url":"https://s/old-sample.jpg"}}""",
            ),
        )
        assertEquals("https://s/old.jpg", legacyFallback.fileUrl)
        assertEquals("https://s/old-sample.jpg", legacyFallback.sampleUrl)
        assertNull(legacyFallback.previewUrl)
    }

    @Test
    fun `image url falls back per quality for every partial url combination`() {
        val full = samplePost(fileUrl = "F", sampleUrl = "S", previewUrl = "P")
        assertEquals("F", full.imageUrl(Quality.ORIGINAL))
        assertEquals("S", full.imageUrl(Quality.SAMPLE))
        assertEquals("P", full.imageUrl(Quality.PREVIEW))
        // 未知 quality 走 else 分支：预览 → 采样 → 原图。
        assertEquals("P", full.imageUrl("unknown"))
        assertEquals("P", full.imageUrl(""))

        val onlyFile = samplePost(fileUrl = "F", sampleUrl = null, previewUrl = null)
        assertEquals("F", onlyFile.imageUrl(Quality.ORIGINAL))
        assertEquals("F", onlyFile.imageUrl(Quality.SAMPLE))
        assertEquals("F", onlyFile.imageUrl(Quality.PREVIEW))

        val onlySample = samplePost(fileUrl = null, sampleUrl = "S", previewUrl = null)
        assertEquals("S", onlySample.imageUrl(Quality.ORIGINAL))
        assertEquals("S", onlySample.imageUrl(Quality.SAMPLE))
        assertEquals("S", onlySample.imageUrl(Quality.PREVIEW))

        val onlyPreview = samplePost(fileUrl = null, sampleUrl = null, previewUrl = "P")
        assertEquals("P", onlyPreview.imageUrl(Quality.ORIGINAL))
        assertEquals("P", onlyPreview.imageUrl(Quality.SAMPLE))
        assertEquals("P", onlyPreview.imageUrl(Quality.PREVIEW))

        val none = samplePost(fileUrl = null, sampleUrl = null, previewUrl = null)
        for (quality in listOf(Quality.ORIGINAL, Quality.SAMPLE, Quality.PREVIEW, "unknown", "")) {
            assertNull(none.imageUrl(quality))
        }
    }

    @Test
    fun `target filename truncates md5 to eight chars without padding`() {
        val base = samplePost(id = 9L, md5 = "", ext = "jpg")
        // 实际行为：空 md5 → 文件名里就是两个连续下划线/点，不补齐。
        assertEquals("9_.jpg", base.targetFilename())
        assertEquals("9_abc.jpg", base.copy(md5 = "abc").targetFilename())
        assertEquals("9_1234567.jpg", base.copy(md5 = "1234567").targetFilename())
        assertEquals("9_12345678.jpg", base.copy(md5 = "12345678").targetFilename())
        assertEquals("9_12345678.jpg", base.copy(md5 = "123456789abc").targetFilename())
        // md5 大小写不转换，只做截断。
        assertEquals("9_ABCDEF12.png", base.copy(md5 = "ABCDEF12", ext = "png").targetFilename())

        // 从真实 JSON 走一遍，确认短 md5 不会被补齐成 8 位。
        val parsed = Post.from(JSONObject("""{"id":9,"files":{"meta":{"md5":"abc","ext":"png"}}}"""))
        assertEquals("9_abc.png", parsed.targetFilename())
    }

    @Test
    fun `dimensions fall back to legacy file when new values are not positive`() {
        val post = Post.from(
            JSONObject(
                """{"id":1,"files":{"original":{"width":0,"height":0,"url":"https://s/new.png"}},"file":{"width":800,"height":600,"ext":"jpg","url":"https://s/old.jpg"}}""",
            ),
        )
        assertEquals(800, post.width)
        assertEquals(600, post.height)
        assertEquals("https://s/new.png", post.fileUrl)

        val zero = Post.from(JSONObject("""{"id":2,"files":{"original":{"width":-5,"height":0}}}"""))
        assertEquals(0, zero.width)
        assertEquals(0, zero.height)
    }

    @Test
    fun `withFavorite never drops below zero favorites`() {
        val notFavorited = samplePost(isFavorited = false, favCount = 0)
        val added = notFavorited.withFavorite(true)
        assertTrue(added.isFavorited)
        assertEquals(1, added.favCount)
        // 幂等：重复设为已收藏不累加。
        assertEquals(1, added.withFavorite(true).favCount)

        val removed = added.withFavorite(false)
        assertFalse(removed.isFavorited)
        assertEquals(0, removed.favCount)

        // 实际行为：状态是已收藏但收藏数为 0 时取消收藏，coerceAtLeast(0) 兜底为 0。
        val orphan = samplePost(isFavorited = true, favCount = 0)
        val abandoned = orphan.withFavorite(false)
        assertFalse(abandoned.isFavorited)
        assertEquals(0, abandoned.favCount)
    }
}
