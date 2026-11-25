package com.mikesteve.e621downloadler

import com.mikesteve.e621downloadler.net.UpdateChecker
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.data.Quality
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.5.0：卡片可见性过滤 + 更新检查版本比较。 */
class V150ParsingTest {

    private fun post(json: String): Post = Post.from(JSONObject(json))

    @Test
    fun cardVisibleWhenPreviewExists() {
        val p = post("""{"id":1,"files":{"preview":{"url":"https://x/p.jpg"},"original":{"url":"https://x/o.jpg"},"meta":{"ext":"jpg","md5":"m"}}}""")
        assertTrue(p.hasVisibleCard)
    }

    @Test
    fun cardVisibleWhenOnlySampleExists() {
        val p = post("""{"id":2,"files":{"sample":{"url":"https://x/s.jpg"},"original":{"url":"https://x/o.jpg"},"meta":{"ext":"jpg","md5":"m"}}}""")
        assertTrue(p.hasVisibleCard)
    }

    @Test
    fun cardHiddenWhenNoUrls() {
        val p = post("""{"id":3,"files":{"original":{"url":null},"preview":{"url":null},"sample":{"url":null},"meta":{"ext":"jpg","md5":"m"}}}""")
        assertFalse(p.hasVisibleCard)
    }

    @Test
    fun cardHiddenForDeletedLikeOldShape() {
        val p = post("""{"id":4,"file":{"url":null},"preview":{"url":null},"sample":{"url":null},"md5":"m"}""")
        assertFalse(p.hasVisibleCard)
    }

    @Test
    fun updateVersionParsing() {
        assertEquals("1.5.0", UpdateChecker.parseVersionFromTag("android-v1.5.0"))
        assertEquals(null, UpdateChecker.parseVersionFromTag("v1.5.0"))
        assertEquals(null, UpdateChecker.parseVersionFromTag("android-v"))
    }

    @Test
    fun updateVersionComparison() {
        assertTrue(UpdateChecker.isNewerVersion("1.5.0", "1.4.9"))
        assertTrue(UpdateChecker.isNewerVersion("1.10.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewerVersion("2.0", "1.9.9"))
        assertFalse(UpdateChecker.isNewerVersion("1.4.0", "1.4.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.3.9", "1.4.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.4", "1.4.0"))
    }

    @Test
    fun qualityConstantsUnchanged() {
        assertEquals("1", Quality.ORIGINAL)
        assertEquals("2", Quality.SAMPLE)
        assertEquals("3", Quality.PREVIEW)
    }
}
