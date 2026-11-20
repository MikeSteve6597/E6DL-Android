package com.mikesteve.e621downloadler.net

import com.mikesteve.e621downloadler.BuildConfig
import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.Post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** 单机模式拦截（与桌面版 `OfflineModeError` 同义）。 */
class OfflineModeException : RuntimeException("当前为单机模式，已禁止联网请求")

/** 服务端返回非 2xx 时抛出。 */
class ApiException(val code: Int, message: String) : RuntimeException(message)

/**
 * 网络门禁：**先**看开发期网络锁，**再**看用户选择的运行模式。
 *
 * 两道锁的先后顺序与桌面版一致：开发版连"单机/在线"都轮不到，直接按网络锁拒绝。
 */
object NetworkGate {
    fun require(mode: String) {
        NetGuard.ensureNetworkAllowed()
        if (mode != Config.MODE_ONLINE) throw OfflineModeException()
    }
}

/** 把异常翻译成中文提示，规则对齐桌面版 `gui.friendly_error`。 */
fun friendlyMessage(e: Throwable): String = when {
    e is NetworkLockedException -> "开发版本已启用网络锁：禁止任何联网请求（发布版才允许联网）。"
    e is OfflineModeException -> "当前为单机模式，已禁用联网。请在「设置 › 运行模式」切换为在线模式。"
    e is ApiException -> when (e.code) {
        401 -> "登录状态已失效，请在设置页重新登录。"
        403 -> "没有权限执行该操作。"
        404 -> "请求的内容不存在。"
        429 -> "请求过于频繁（HTTP 429），请稍后再试。"
        in 500..599 -> "e621 服务器暂时不可用，请稍后再试。"
        else -> "请求失败（HTTP ${e.code}）。"
    }
    // 解析类异常绝不把原文（可能含整段 JSON）显示到界面上
    e is org.json.JSONException -> "接口返回格式无法解析（e621 可能已改版），请把此情况反馈给开发者。"
    e is kotlinx.coroutines.CancellationException -> "已取消。"
    e is SocketTimeoutException -> "网络请求超时，请检查网络后重试。"
    e is IOException -> "无法连接到 e621，请检查网络或代理设置。"
    else -> (e.message ?: "未知错误").let { if (it.length > 120) it.take(120) + "…" else it }
}

/**
 * e621 接口封装。UA 与鉴权头规则与桌面版一致。
 *
 * 注意：所有方法都会先过 [NetworkGate]；debug 构建里调用会直接抛 [NetworkLockedException]，
 * 不会建立任何连接。
 */
class ApiClient(private val apiRoot: String = "https://e621.net") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val authClient = client.newBuilder()
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private fun userAgent(cfg: Config): String =
        if (cfg.loggedIn) {
            "E6DL/${BuildConfig.VERSION_NAME} (by ${cfg.username} on e621)"
        } else {
            "E6DL/${BuildConfig.VERSION_NAME} (anonymous)"
        }

    private fun basicAuth(user: String, key: String): String {
        val raw = "$user:$key".toByteArray(Charsets.UTF_8)
        return "Basic " + android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
    }

    private fun request(path: String, cfg: Config): Request.Builder {
        val builder = Request.Builder()
            .url("$apiRoot$path")
            .header("Accept", "application/json")
            .header("User-Agent", userAgent(cfg))
        if (cfg.loggedIn) {
            builder.header("Authorization", basicAuth(cfg.username, cfg.apiKey))
        }
        return builder
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, "HTTP ${resp.code}")
            }
            body
        }
    }

    /** 与桌面版 `build_search_tags` 一致：黑名单拼 `-tag`，追加 `score:>=N`。 */
    fun buildSearchTags(tags: List<String>, blacklist: List<String>, minScore: Int?): String {
        val parts = ArrayList<String>()
        parts.addAll(tags.map { it.trim() }.filter { it.isNotEmpty() })
        parts.addAll(blacklist.map { it.trim() }.filter { it.isNotEmpty() }.map { "-$it" })
        if (minScore != null) parts.add("score:>=$minScore")
        return parts.joinToString(" ")
    }

    suspend fun fetchPosts(
        cfg: Config,
        tags: List<String>,
        blacklist: List<String> = emptyList(),
        minScore: Int? = null,
        limit: Int = 24,
        page: Int = 1,
    ): List<Post> {
        NetworkGate.require(cfg.mode)
        val tagStr = buildSearchTags(tags, blacklist, minScore)
        val httpUrl = okhttp3.HttpUrl.Builder()
            .scheme("https").host("e621.net").addPathSegment("posts.json")
            .addQueryParameter("tags", tagStr)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("page", page.toString())
            .build()
        val req = request("/posts.json", cfg).url(httpUrl).build()
        val body = execute(req)
        return parsePostList(body)
    }

    suspend fun fetchPost(cfg: Config, id: Long): Post? {
        NetworkGate.require(cfg.mode)
        val body = execute(request("/posts/$id.json", cfg).build())
        return parseSinglePost(body)
    }

    suspend fun fetchFavorites(cfg: Config, limit: Int = 100, page: Int = 1): List<Post> {
        NetworkGate.require(cfg.mode)
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https").host("e621.net").addPathSegment("favorites.json")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("page", page.toString())
            .build()
        val body = execute(request("/favorites.json", cfg).url(url).build())
        return parsePostList(body)
    }

    /** 逐页拉取收藏，按 id 去重；页间隔 1.1 s（与桌面版一致）。 */
    suspend fun fetchAllFavorites(
        cfg: Config,
        limit: Int = 200,
        maxPages: Int = 25,
        onProgress: ((Int) -> Unit)? = null,
    ): List<Post> {
        val posts = ArrayList<Post>()
        val seen = HashSet<Long>()
        for (page in 1..maxPages) {
            val batch = fetchFavorites(cfg, limit = limit, page = page)
            if (batch.isEmpty()) break
            var added = 0
            for (post in batch) {
                if (seen.add(post.id)) {
                    posts.add(post)
                    added++
                }
            }
            onProgress?.invoke(posts.size)
            if (batch.size < limit || added == 0) break
            delay(1100)
        }
        return posts
    }

    suspend fun addFavorite(cfg: Config, postId: Long) {
        NetworkGate.require(cfg.mode)
        val form = FormBody.Builder().add("post_id", postId.toString()).build()
        val req = request("/favorites.json", cfg).post(form).build()
        execute(req)
    }

    suspend fun removeFavorite(cfg: Config, postId: Long) {
        NetworkGate.require(cfg.mode)
        val req = request("/favorites/$postId.json", cfg).delete().build()
        execute(req)
    }

    /** 校验登录：返回 (成功, HTTP 码, 提示)。网络异常时状态码为 0。 */
    suspend fun validateLogin(user: String, key: String): Triple<Boolean, Int, String> {
        NetGuard.ensureNetworkAllowed()
        val req = Request.Builder()
            .url("$apiRoot/users/me.json")
            .header("Accept", "application/json")
            .header("User-Agent", "E6DL/${BuildConfig.VERSION_NAME} (by $user on e621)")
            .header("Authorization", basicAuth(user, key))
            .build()
        return try {
            withContext(Dispatchers.IO) {
                authClient.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        200 -> Triple(true, 200, "登录成功")
                        401 -> Triple(false, 401, "用户名或 API Key 错误")
                        else -> Triple(false, resp.code, "验证失败（HTTP ${resp.code}）")
                    }
                }
            }
        } catch (e: Exception) {
            Triple(false, 0, "网络错误：${e.message}")
        }
    }

    /**
     * 解析作品列表，兼容三种返回形态：
     *  - 顶层数组：`[{...}, {...}]`（2026-11 线上实测的新形态）
     *  - 包一层：`{"posts": [...]}`
     *  - 收藏接口：`{"favorites": [...]}`
     */
    private fun parsePostList(body: String): List<Post> {
        val text = body.trimStart()
        if (text.isEmpty()) return emptyList()
        val array = if (text.startsWith("[")) {
            org.json.JSONArray(body)
        } else {
            val obj = JSONObject(body)
            obj.optJSONArray("posts") ?: obj.optJSONArray("favorites") ?: org.json.JSONArray()
        }
        val out = ArrayList<Post>(array.length())
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(Post.from(it)) }
        }
        return out
    }

    /** 单作品：`{"post": {...}}` 或直接 `{...}` 或 `[{...}]`。 */
    private fun parseSinglePost(body: String): Post? {
        val text = body.trimStart()
        if (text.isEmpty()) return null
        if (text.startsWith("[")) {
            val arr = org.json.JSONArray(body)
            return arr.optJSONObject(0)?.let { Post.from(it) }
        }
        val obj = JSONObject(body)
        val inner = obj.optJSONObject("post") ?: obj
        return if (inner.optLong("id") > 0) Post.from(inner) else null
    }
}
