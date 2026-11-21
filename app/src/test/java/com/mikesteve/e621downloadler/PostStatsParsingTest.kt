package com.mikesteve.e621downloadler

import com.mikesteve.e621downloadler.data.Post
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * e621 新结构（`stats.*` / `uploader_name` / `files.video.*`）解析测试。
 *
 * 背景：站点把评分、收藏数等从顶层搬到了 `stats` 里，视频帖的 `files.original.url`
 * 可能为空、真正的视频地址在 `files.video.*`。这里把「新字段优先、旧字段逐级回退」
 * 的规则全部钉住，站点再改版会先在单元测试里失败，而不是在用户手机上显示 0 分/无作者。
 */
class PostStatsParsingTest {

    private fun parse(json: String): Post = Post.from(JSONObject(json.trimIndent()))

    @Test
    fun `stats score fav comment and uploader_name take priority over legacy fields`() {
        val post = parse(
            """
            {
              "id": 5001,
              "score": { "up": 100, "down": 1, "total": 99 },
              "fav_count": 1,
              "is_favorited": true,
              "comment_count": 2,
              "uploader_name": "uploader_new",
              "stats": {
                "score": { "up": 5, "down": 1, "total": 4 },
                "fav_count": 12,
                "is_favorited": false,
                "comment_count": 3
              },
              "tags": { "artist": ["artist_a", "artist_b"], "general": ["cat"] }
            }
            """,
        )
        assertEquals(4, post.score)
        assertEquals(12, post.favCount)
        assertFalse(post.isFavorited)
        assertEquals(3, post.commentCount)
        assertEquals("uploader_new", post.uploaderName)
        assertEquals("artist_a", post.artist)
    }

    @Test
    fun `legacy numeric score and top level stats still parse`() {
        val post = parse(
            """
            {
              "id": 5002,
              "score": 7,
              "fav_count": 5,
              "is_favorited": true,
              "comment_count": 1,
              "uploader_name": "old_user"
            }
            """,
        )
        assertEquals(7, post.score)
        assertEquals(5, post.favCount)
        assertTrue(post.isFavorited)
        assertEquals(1, post.commentCount)
        assertEquals("old_user", post.uploaderName)
        assertNull(post.videoUrl)
        assertEquals("", post.artist)
    }

    @Test
    fun `legacy score object total is used when stats is absent`() {
        assertEquals(
            7,
            parse("""{"id": 5003, "score": {"up": 10, "down": 3, "total": 7}}""").score,
        )
        // stats 里的 0 也是有效值，不能被旧结构的分值顶掉。
        assertEquals(
            0,
            parse("""{"id": 5008, "score": 99, "stats": {"score": {"up": 0, "down": 0, "total": 0}}}""").score,
        )
    }

    @Test
    fun `score falls back to up_score minus down_score`() {
        assertEquals(6, parse("""{"id": 5004, "up_score": 10, "down_score": 4}""").score)
        // 顶层 score 为 null 时同样回退（旧 JSON 里 null 很常见）。
        assertEquals(3, parse("""{"id": 5005, "score": null, "up_score": 5, "down_score": 2}""").score)
        // 什么分值字段都没有 → 0，不抛异常。
        assertEquals(0, parse("""{"id": 5006}""").score)
    }

    @Test
    fun `stats without fav comment fields falls back to top level`() {
        val post = parse(
            """
            {
              "id": 5007,
              "score": 11,
              "fav_count": 8,
              "is_favorited": true,
              "comment_count": 6,
              "stats": { "score": { "up": 3, "down": 1, "total": 2 } }
            }
            """,
        )
        // 只有 stats.score 存在时它优先，其余字段仍读顶层旧结构。
        assertEquals(2, post.score)
        assertEquals(8, post.favCount)
        assertTrue(post.isFavorited)
        assertEquals(6, post.commentCount)
    }

    @Test
    fun `video url prefers original then variants then samples`() {
        val all = parse(
            """
            {
              "id": 6001,
              "files": {
                "meta": { "ext": "webm" },
                "video": {
                  "original": { "url": "https://static.e621.net/video/original.webm" },
                  "variants": {
                    "webm": { "url": "https://static.e621.net/video/1080.webm" },
                    "mp4": { "url": "https://static.e621.net/video/720.mp4" }
                  },
                  "samples": {
                    "720p": { "url": "https://static.e621.net/video/sample720.webm" },
                    "480p": { "url": "https://static.e621.net/video/sample480.webm" }
                  }
                }
              }
            }
            """,
        )
        assertEquals("https://static.e621.net/video/original.webm", all.videoUrl)

        val variantWebm = parse(
            """
            {
              "id": 6002,
              "files": { "video": { "variants": {
                "webm": { "url": "w" }, "mp4": { "url": "m" }
              } } }
            }
            """,
        )
        assertEquals("w", variantWebm.videoUrl)

        // webm 地址为空串时继续退到 mp4。
        val variantMp4 = parse(
            """
            {
              "id": 6003,
              "files": { "video": { "variants": {
                "webm": { "url": "" }, "mp4": { "url": "m" }
              } } }
            }
            """,
        )
        assertEquals("m", variantMp4.videoUrl)

        val sample720 = parse(
            """
            {
              "id": 6004,
              "files": { "video": { "samples": {
                "720p": { "url": "s720" }, "480p": { "url": "s480" }
              } } }
            }
            """,
        )
        assertEquals("s720", sample720.videoUrl)

        val sample480 = parse(
            """
            {
              "id": 6005,
              "files": { "video": { "samples": {
                "480p": { "url": "s480" }
              } } }
            }
            """,
        )
        assertEquals("s480", sample480.videoUrl)

        // 显式 null 的地址按缺失处理（Android 的 optString 会把 JSON null 变成 "null"，
        // 不能把它当成合法 URL），继续往下一级回退。
        val nullUrl = parse(
            """
            {
              "id": 6008,
              "files": { "video": {
                "original": { "url": null },
                "variants": { "mp4": { "url": "m" } }
              } }
            }
            """,
        )
        assertEquals("m", nullUrl.videoUrl)

        // 没有 video 段 → null；不能拿图片原图地址冒充视频地址。
        val imageOnly = parse(
            """{"id": 6006, "files": {"original": {"url": "https://static.e621.net/original/a.png"}}}""",
        )
        assertNull(imageOnly.videoUrl)
    }

    @Test
    fun `video post with empty files original url still resolves video url`() {
        val post = parse(
            """
            {
              "id": 6007,
              "files": {
                "meta": { "ext": "webm" },
                "original": { "url": "" },
                "video": { "original": { "url": "https://static.e621.net/data/v.webm" } }
              }
            }
            """,
        )
        assertTrue(post.isVideo)
        assertNull(post.fileUrl)
        assertEquals("https://static.e621.net/data/v.webm", post.videoUrl)
    }

    @Test
    fun `legacy video post falls back to file url for video extensions only`() {
        val video = parse(
            """{"id": 6009, "file": {"ext": "webm", "url": "https://static.e621.net/data/legacy.webm"}}""",
        )
        assertTrue(video.isVideo)
        assertEquals("https://static.e621.net/data/legacy.webm", video.videoUrl)

        // 图片帖不能把图片地址当成视频地址。
        val image = parse(
            """{"id": 6010, "file": {"ext": "png", "url": "https://static.e621.net/data/a.png"}}""",
        )
        assertFalse(image.isVideo)
        assertNull(image.videoUrl)
    }

    @Test
    fun `artist uses first artist tag and is empty when absent`() {
        assertEquals(
            "first_artist",
            parse("""{"id": 7001, "tags": {"artist": ["first_artist", "second_artist"]}}""").artist,
        )
        assertEquals("", parse("""{"id": 7002, "tags": {"artist": []}}""").artist)
        assertEquals("", parse("""{"id": 7003, "tags": {"general": ["cat"]}}""").artist)
    }

    @Test
    fun `missing stats defaults to zero and empty uploader`() {
        val post = parse("""{"id": 7004}""")
        assertEquals(0, post.score)
        assertEquals(0, post.favCount)
        assertEquals(0, post.commentCount)
        assertFalse(post.isFavorited)
        assertEquals("", post.uploaderName)
        assertEquals("", post.artist)
        assertNull(post.videoUrl)

        // 上传者字段显式为 null → 空串，而不是字符串 "null"。
        assertEquals("", parse("""{"id": 7005, "uploader_name": null}""").uploaderName)
    }
}
