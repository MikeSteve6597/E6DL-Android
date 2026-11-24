package com.mikesteve.e621downloadler.net

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.mikesteve.e621downloadler.BuildConfig
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 翻译对象类型：评论是整句，标签是短语。两者的本地/在线策略不同。 */
enum class TranslationKind { COMMENT, TAG }

/**
 * 翻译仓库（v1.4.0）。
 *
 * 两套引擎：
 *  - [Config.TRANSLATION_GOOGLE]：在线翻译。**必须先过 [NetworkGate]**（开发版网络锁 +
 *    单机模式两道门禁），主接口 google translate 公共端点，失败回退 MyMemory；
 *    绝不绕过 NetGuard / NetworkGate。
 *  - [Config.TRANSLATION_LOCAL]：本地机翻。标签直接查 `res/raw/local_tag_translations.tsv`
 *    内置词典；评论走 Google ML Kit 设备端翻译（English → Chinese，首次下载模型），
 *    设备无 GMS / 无网络 / 模型下载失败时**静默回退**逐词词典翻译并保留未命中的英文词。
 *    单机模式或开发版网络锁下不会触发模型下载（模型已就绪时仍可纯离线使用 ML Kit），
 *    因此 debug（无 INTERNET 权限）也能工作，不会崩溃。
 *
 * 结果按 `engine + kind + text` 缓存在内存里（[ConcurrentHashMap]），重复翻译不重复请求；
 * 缓存只在进程内有效，不落盘。
 */
object TranslationRepository {

    /** 内存缓存上限，超过后整体清空（避免长会话无限增长）。 */
    private const val CACHE_LIMIT = 4000

    /** 批量翻译时同时进行的请求数上限。 */
    private const val BATCH_CONCURRENCY = 4

    /** ML Kit 单次等待上限：模型下载/翻译卡住时退回词典，不让 UI 无限转圈。 */
    private const val MLKIT_TIMEOUT_MS = 12_000L

    /** ML Kit 失败后的冷却时间：避免每条评论都重新尝试（debug 禁网时尤其重要）。 */
    private const val MLKIT_RETRY_COOLDOWN_MS = 10 * 60 * 1000L

    /** MyMemory 免费接口单次 query 长度有限，太长的评论直接让上层报错。 */
    private const val MYMEMORY_MAX_CHARS = 900

    private val cache = ConcurrentHashMap<String, String>()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // ------------------------------------------------------------- 本地词典

    @Volatile
    private var dictionaryCache: Map<String, String>? = null

    /** 词典条目数（调试/测试用）。 */
    fun dictionarySize(context: Context): Int = dictionary(context.applicationContext).size

    /**
     * 读取并解析内置词典：两列 TSV（英文 tag <TAB> 中文），忽略空行与 `#` 注释。
     * 解析失败（资源缺失等）返回空表，调用方回退原文，不抛异常。
     */
    private fun dictionary(context: Context): Map<String, String> {
        dictionaryCache?.let { return it }
        return synchronized(this) {
            dictionaryCache?.let { return@synchronized it }
            val loaded: Map<String, String> = try {
                val map = HashMap<String, String>(256)
                context.resources
                    .openRawResource(R.raw.local_tag_translations)
                    .bufferedReader(Charsets.UTF_8)
                    .useLines { lines ->
                        lines.forEach { rawLine ->
                            val line = rawLine.trim()
                            if (line.isEmpty() || line.startsWith("#")) return@forEach
                            val tab = line.indexOf('\t')
                            if (tab <= 0 || tab >= line.length - 1) return@forEach
                            val english = line.substring(0, tab).trim().lowercase()
                            val chinese = line.substring(tab + 1).trim()
                            if (english.isNotEmpty() && chinese.isNotEmpty()) {
                                map[english] = chinese
                            }
                        }
                    }
                map
            } catch (e: Exception) {
                emptyMap()
            }
            dictionaryCache = loaded
            loaded
        }
    }

    /** 单个标签查词典；支持 `blue eyes` ⇄ `blue_eyes` 两种写法。 */
    private fun dictionaryTranslate(context: Context, text: String): String {
        val dict = dictionary(context)
        if (dict.isEmpty()) return text
        val key = text.trim().lowercase()
        if (key.isEmpty()) return text
        dict[key]?.let { return it }
        dict[key.replace(' ', '_')]?.let { return it }
        dict[key.replace('_', ' ')]?.let { return it }
        return text
    }

    /**
     * 逐词词典翻译：把英文按"字母/数字/下划线/撇号"切词，词命中词典就替换，
     * 未命中的词保留英文；标点与空白原样保留。用于评论在 ML Kit 不可用时的兜底。
     */
    private fun dictionaryWordByWord(context: Context, text: String): String {
        val dict = dictionary(context)
        if (dict.isEmpty()) return text
        val out = StringBuilder(text.length + 16)
        val word = StringBuilder()
        fun flushWord() {
            if (word.isEmpty()) return
            val token = word.toString()
            val lower = token.lowercase()
            val hit = dict[lower] ?: dict[lower.replace(' ', '_')]
            out.append(hit ?: token)
            word.setLength(0)
        }
        for (ch in text) {
            if (ch.isLetterOrDigit() || ch == '_' || ch == '\'') {
                word.append(ch)
            } else {
                flushWord()
                out.append(ch)
            }
        }
        flushWord()
        return out.toString()
    }

    // ------------------------------------------------------------- ML Kit

    @Volatile
    private var mlKitTranslator: Translator? = null

    @Volatile
    private var mlKitModelReady = false

    @Volatile
    private var mlKitFailedAt = 0L

    private fun mlKitOnCooldown(): Boolean {
        val failedAt = mlKitFailedAt
        return failedAt != 0L && System.currentTimeMillis() - failedAt < MLKIT_RETRY_COOLDOWN_MS
    }

    private fun markMlKitFailed() {
        mlKitFailedAt = System.currentTimeMillis()
    }

    private fun obtainTranslator(): Translator {
        mlKitTranslator?.let { return it }
        return synchronized(this) {
            mlKitTranslator ?: run {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.CHINESE)
                    .build()
                Translation.getClient(options).also { mlKitTranslator = it }
            }
        }
    }

    /**
     * 设备端英文→中文翻译。任何失败（无 GMS、模型下载失败、debug 禁网、超时）都返回 null，
     * 由调用方回退词典；绝不让异常冒到 UI。
     */
    private suspend fun mlKitTranslate(text: String): String? {
        if (mlKitOnCooldown()) return null
        val translator = try {
            // 首次创建 Translator 可能加载原生库，放到后台线程，避免主线程卡顿。
            withContext(Dispatchers.Default) { obtainTranslator() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            markMlKitFailed()
            return null
        }
        return try {
            val result = withTimeoutOrNull(MLKIT_TIMEOUT_MS) {
                if (!mlKitModelReady) {
                    // 首次需要下载离线模型；DownloadConditions 不限制网络类型。
                    val downloadTask = translator.downloadModelIfNeeded(
                        DownloadConditions.Builder().build(),
                    )
                    suspendCancellableCoroutine<Unit> { cont ->
                        downloadTask
                            .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                            .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
                    }
                    mlKitModelReady = true
                }
                val translateTask = translator.translate(text)
                suspendCancellableCoroutine<String> { cont ->
                    translateTask
                        .addOnSuccessListener { value -> if (cont.isActive) cont.resume(value ?: "") }
                        .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
                }
            }
            // 超时也进入冷却：宁可用词典，也不让下一条评论再等 12 秒。
            if (result == null) markMlKitFailed()
            result?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 模型不可用：进入冷却，本进程后续直接走词典，避免反复等待
            markMlKitFailed()
            null
        }
    }

    // ------------------------------------------------------------- 对外 API

    /**
     * 翻译单段文本。
     *
     * @throws NetworkLockedException debug 构建下选择在线引擎时（开发期网络锁）。
     * @throws OfflineModeException 单机模式下选择在线引擎时。
     * @throws IOException 在线引擎主备接口都失败时。
     */
    suspend fun translate(
        context: Context,
        cfg: Config,
        text: String,
        kind: TranslationKind = TranslationKind.COMMENT,
    ): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text
        val appContext = context.applicationContext
        val engine = normalizeEngine(cfg.translationEngine)

        // 在线路径先过统一网络门禁：开发版网络锁 / 单机模式都在发请求前抛可读异常。
        if (engine == Config.TRANSLATION_GOOGLE) {
            NetworkGate.require(cfg.mode)
        }

        val key = cacheKey(engine, kind, text)
        cache[key]?.let { return it }

        // 已经是中文的评论不再翻译（在线路径也省一次请求）
        if (kind == TranslationKind.COMMENT && isMostlyChinese(trimmed)) return text

        val result = when (engine) {
            Config.TRANSLATION_LOCAL -> translateLocal(appContext, cfg, trimmed, kind)
            else -> translateOnline(cfg, trimmed)
        }
        if (result.isNotBlank()) {
            if (cache.size >= CACHE_LIMIT) cache.clear()
            cache[key] = result
        }
        return result
    }

    /**
     * 批量翻译（默认按标签处理），并发 ≤ [BATCH_CONCURRENCY]，结果顺序与输入一一对应。
     * 单批失败会向上抛出可读异常（调用方用 [friendlyMessage] 展示）。
     */
    suspend fun translateBatch(
        context: Context,
        cfg: Config,
        texts: List<String>,
        kind: TranslationKind = TranslationKind.TAG,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (texts.size == 1) return listOf(translate(context, cfg, texts[0], kind))
        return coroutineScope {
            texts.chunked(BATCH_CONCURRENCY).flatMap { chunk ->
                chunk.map { text -> async { translate(context, cfg, text, kind) } }.awaitAll()
            }
        }
    }

    /** 清空内存缓存（切换引擎后由 UI 主动调用也可）。 */
    fun clearCache() {
        cache.clear()
    }

    // ------------------------------------------------------------- 本地引擎

    private suspend fun translateLocal(
        context: Context,
        cfg: Config,
        text: String,
        kind: TranslationKind,
    ): String =
        when (kind) {
            TranslationKind.TAG -> dictionaryTranslate(context, text)
            // 评论：ML Kit 设备端翻译优先；无 GMS / 无网络 / 模型下载失败时逐词词典兜底。
            TranslationKind.COMMENT -> {
                // 单机模式视为"禁止联网"：模型还没下载好时不去触发下载，直接查词典；
                // 模型已在本次进程内就绪时纯离线运行，可以继续用 ML Kit。
                // 开发版（网络锁）也不尝试下载，避免无谓等待，直接词典兜底。
                val mlKitAvailable = mlKitModelReady ||
                    (cfg.mode == Config.MODE_ONLINE && NetGuard.networkAllowed)
                val machine = if (mlKitAvailable) mlKitTranslate(text) else null
                machine ?: dictionaryWordByWord(context, text)
            }
        }

    // ------------------------------------------------------------- 在线引擎

    /**
     * 在线翻译：google translate 公共端点 → 失败回退 MyMemory。
     * 这里不再重复调用门禁（[translate] 已调用），只负责 HTTP + 解析。
     */
    private suspend fun translateOnline(cfg: Config, text: String): String {
        var googleError: Throwable? = null
        try {
            return googleTranslate(cfg, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            googleError = e
        }
        try {
            return myMemoryTranslate(cfg, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = e.message ?: googleError?.message ?: "翻译服务不可用"
            throw IOException("在线翻译失败：$message")
        }
    }

    /** 主接口：`translate_a/single`，解析嵌套数组，把各段译文拼起来。 */
    private suspend fun googleTranslate(cfg: Config, text: String): String {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("translate.googleapis.com")
            .addPathSegments("translate_a/single")
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", "zh-CN")
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()
        val body = httpGetText(url, cfg)
        val root = JSONArray(body)
        val segments = root.optJSONArray(0) ?: throw IOException("谷歌翻译返回格式无法解析")
        val out = StringBuilder()
        for (i in 0 until segments.length()) {
            val segment = segments.optJSONArray(i) ?: continue
            val piece = segment.optString(0)
            if (piece.isNotBlank()) out.append(piece)
        }
        val translated = out.toString().trim()
        if (translated.isEmpty()) throw IOException("谷歌翻译返回空结果")
        return translated
    }

    /** 备接口：MyMemory，解析 `responseData.translatedText`。 */
    private suspend fun myMemoryTranslate(cfg: Config, text: String): String {
        if (text.length > MYMEMORY_MAX_CHARS) {
            throw IOException("评论过长，备用翻译接口不支持")
        }
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.mymemory.translated.net")
            .addPathSegment("get")
            .addQueryParameter("q", text)
            .addQueryParameter("langpair", "en|zh-CN")
            .build()
        val body = httpGetText(url, cfg)
        val root = JSONObject(body)
        val status = root.optInt("responseStatus", 200)
        val data = root.optJSONObject("responseData")
            ?: throw IOException("MyMemory 返回格式无法解析")
        val raw = data.optString("translatedText")
        if (raw.isBlank()) throw IOException("MyMemory 返回空译文")
        if (status != 200) throw IOException("MyMemory 状态异常（$status）")
        if (raw.uppercase().startsWith("MYMEMORY WARNING")) {
            throw IOException("MyMemory 免费额度已用完")
        }
        return decodeHtmlEntities(raw).trim()
    }

    private suspend fun httpGetText(url: HttpUrl, cfg: Config): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", userAgent(cfg))
            .build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, "翻译服务返回 HTTP ${resp.code}")
            }
            if (body.isBlank()) throw IOException("翻译服务返回空响应")
            body
        }
    }

    private fun userAgent(cfg: Config): String =
        if (cfg.loggedIn) {
            "E6DL/${BuildConfig.VERSION_NAME} (by ${cfg.username} on e621)"
        } else {
            "E6DL/${BuildConfig.VERSION_NAME} (anonymous)"
        }

    // ------------------------------------------------------------- 工具

    private fun normalizeEngine(engine: String): String =
        if (engine == Config.TRANSLATION_LOCAL) Config.TRANSLATION_LOCAL else Config.TRANSLATION_GOOGLE

    private fun cacheKey(engine: String, kind: TranslationKind, text: String): String =
        "$engine|${kind.name}|$text"

    /** 已经以中文为主时不再翻译（评论翻译 / 在线路径都省一次请求）。 */
    private fun isMostlyChinese(text: String): Boolean {
        var cjk = 0
        var letters = 0
        for (ch in text) {
            if (ch.isLetter()) {
                letters++
                if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF) cjk++
            }
        }
        return letters > 0 && cjk.toFloat() / letters >= 0.35f
    }

    /** MyMemory 可能返回 HTML 实体，做最小集合的解码。 */
    private fun decodeHtmlEntities(text: String): String {
        if (!text.contains('&')) return text
        return text
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
    }
}
