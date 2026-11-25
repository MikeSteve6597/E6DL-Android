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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 翻译对象类型：评论是整句，标签是短语。两者的本地/在线策略不同。 */
enum class TranslationKind { COMMENT, TAG }

/**
 * 在线翻译服务不可用（主服务与备用服务都失败时抛出）。
 *
 * 消息是可直接展示给用户的中文短句（不含响应正文、密钥等敏感信息）。
 * 特意继承 [RuntimeException] 而不是 [IOException]：这样 `friendlyMessage` 会原样展示
 * 翻译失败原因，而不会被统一映射成「无法连接到 e621」。
 */
class TranslationUnavailableException(message: String) : RuntimeException(message)

/**
 * 翻译仓库（v1.5.0）。
 *
 * 两套引擎：
 *  - [Config.TRANSLATION_GOOGLE]：**在线翻译**。常量名只为兼容历史保存的配置而保留，
 *    实际**不再请求 Google 端点**，改为第三方公开翻译服务：
 *    * 主服务 MyMemory：`https://api.mymemory.translated.net/get`，按方向用
 *      `en|zh-CN` / `zh-CN|en`（免费、无需密钥）；
 *    * 备用服务 LibreTranslate 公共实例：POST JSON，`source` / `target` 用 `en` / `zh`，
 *      实例不可用/限流时静默换下一个，全部失败再抛可读异常。
 *    仍**必须先过 [NetworkGate]**（开发版网络锁 + 单机模式两道门禁），绝不绕过 NetGuard。
 *  - [Config.TRANSLATION_LOCAL]：本地机翻。标签直接查 `res/raw/local_tag_translations.tsv`
 *    内置词典；评论走 ML Kit 设备端翻译（English → Chinese，首次下载模型），
 *    设备无 GMS / 无网络 / 模型下载失败时**静默回退**逐词词典翻译并保留未命中的英文词。
 *    单机模式或开发版网络锁下不会触发模型下载（模型已就绪时仍可纯离线使用 ML Kit），
 *    因此 debug（无 INTERNET 权限）也能工作，不会崩溃。
 *
 * 另外提供 [translateToEnglish] 给「中文搜索 / 黑名单添加」复用：不含 CJK 直接返回原文（不联网），
 * 含中文才走上面的第三方在线服务做 zh→en。
 *
 * 结果按 `引擎/方向 + kind + text` 缓存在内存里（[ConcurrentHashMap]），重复翻译不重复请求；
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

    /** MyMemory 免费接口单次查询约 500 字节（UTF-8）上限：按字节切块并留安全余量。 */
    private const val MYMEMORY_CHUNK_BYTES = 450

    /** LibreTranslate 公共实例单次请求的字符上限：超长文本按块拆分。 */
    private const val LIBRE_CHUNK_CHARS = 4000

    /** 在线翻译最多处理这么长的文本；再长直接提示改用本地机翻，避免拆成大量请求。 */
    private const val ONLINE_MAX_CHARS = 12_000

    /** 中文搜索/黑名单方向（zh→en）的缓存键标记，与评论/标签的 en→zh 缓存区分开。 */
    private const val DIRECTION_TO_EN = "online-en"

    /**
     * 备用服务 LibreTranslate 公共实例列表。
     *
     * 公共实例经常限流/停用/需要 API Key；任何一个失败都静默尝试下一个，
     * 全部失败才把错误拼进 [TranslationUnavailableException]。
     * 顺序按“实测可用性”排列：`translate.hostux.net` 是目前可匿名 POST 的公开镜像，
     * 官方实例（libretranslate.com / de.*）现在要求 API Key，只作为兜底尝试。
     */
    private val LIBRE_ENDPOINTS = listOf(
        "https://translate.hostux.net/translate",
        "https://libretranslate.com/translate",
        "https://de.libretranslate.com/translate",
        "https://translate.terraprint.co/translate",
        "https://translate.fedilab.app/translate",
    )

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val cache = ConcurrentHashMap<String, String>()

    /** 主服务（MyMemory）用常规超时。 */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 备用服务用短超时：公共实例经常不可用，不能让备用链路把 UI 拖太久。 */
    private val libreHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    // ------------------------------------------------------------- 本地词典

    @Volatile
    private var dictionaryCache: Map<String, String>? = null

    /** 词典条目数（调试/测试用）。 */
    fun dictionarySize(context: Context): Int = dictionary(context.applicationContext).size

    /**
     * 内置词典全量条目（英文标签 → 中文释义），供「实时中英对照推荐」等只读场景使用（v1.5.1）。
     *
     * 首次调用读取 `res/raw/local_tag_translations.tsv` 并进程内缓存，之后不再读盘；
     * 返回副本，调用方不能改到缓存里的 Map。
     */
    fun dictionaryEntries(context: Context): List<Pair<String, String>> =
        dictionary(context.applicationContext).map { (english, chinese) -> english to chinese }

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
     * 在线引擎 = 第三方公开翻译服务（MyMemory 主、LibreTranslate 备），不再使用 Google；
     * 本地引擎 = 内置词典 / ML Kit 设备端翻译。
     *
     * @throws NetworkLockedException debug 构建下选择在线引擎时（开发期网络锁）。
     * @throws OfflineModeException 单机模式下选择在线引擎时。
     * @throws TranslationUnavailableException 在线引擎主备服务都失败时（消息可直接展示）。
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
            else -> translateOnline(cfg, trimmed, source = "en", target = "zh")
        }
        if (result.isNotBlank()) putCache(key, result)
        return result
    }

    /**
     * 中文 → 英文（供「中文搜索 / 黑名单添加」等场景复用）。
     *
     * 规则：
     *  - 输入 trim 后为空 → 返回空串；
     *  - **不含 CJK 统一表意文字** → 直接返回 trim 后的原文，不联网；
     *  - 含中文 → 走第三方在线翻译 zh→en（MyMemory 主、LibreTranslate 备），结果 trim；
     *  - 在线翻译需要联网：单机模式抛 [OfflineModeException]，debug 构建抛 [NetworkLockedException]，
     *    主备服务都失败抛 [TranslationUnavailableException]（消息可直接展示给用户）；
     *  - 结果按输入文本缓存在内存，重复调用不重复请求。
     *
     * @param context 与 [translate] 保持同一套签名，方便调用方统一传 Context（当前实现不需要资源）。
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun translateToEnglish(context: Context, cfg: Config, text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        // 纯英文/数字直接返回，绝不为了"判断语言"联网。
        if (!containsCjk(trimmed)) return trimmed
        NetworkGate.require(cfg.mode)
        val key = cacheKey(DIRECTION_TO_EN, TranslationKind.TAG, trimmed)
        cache[key]?.let { return it }
        val result = translateOnline(cfg, trimmed, source = "zh", target = "en").trim()
        // 部分公共实例对中文支持很差（可能返回 "?"、空串或原文）：这种结果不能拿去当
        // 英文关键词搜索/加黑名单，按失败处理并给可读提示，让调用方回退到原文。
        if (result.none { it in 'A'..'Z' || it in 'a'..'z' }) {
            throw TranslationUnavailableException(
                "在线翻译没有返回可用的英文结果，请稍后重试或换一个关键词。",
            )
        }
        if (result.isNotBlank()) putCache(key, result)
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

    // ------------------------------------------------------------- 在线引擎（第三方公开服务）

    /**
     * 在线翻译：MyMemory 主服务 → 失败时**静默回退** LibreTranslate 公共实例。
     *
     * 网络门禁已由 [translate] / [translateToEnglish] 在发请求前统一通过，
     * 这里只负责 HTTP + 解析 + 主备切换，绝不绕过 NetGuard / NetworkGate。
     *
     * @param source 源语言代码（`en` 或 `zh`）
     * @param target 目标语言代码（`zh` 或 `en`）
     */
    private suspend fun translateOnline(
        cfg: Config,
        text: String,
        source: String,
        target: String,
    ): String {
        if (text.length > ONLINE_MAX_CHARS) {
            throw TranslationUnavailableException(
                "文本过长（${text.length} 字符），在线翻译暂不支持，请改用本地机翻。",
            )
        }
        val failures = ArrayList<String>(2)
        try {
            return myMemoryTranslate(cfg, text, source, target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failures += "MyMemory ${describeFailure(e)}"
        }
        try {
            return libreTranslate(cfg, text, source, target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failures += "LibreTranslate ${describeFailure(e)}"
        }
        throw TranslationUnavailableException(
            "在线翻译服务暂时不可用（${failures.joinToString("；")}），请稍后重试或改用本地机翻。",
        )
    }

    /** 主服务：MyMemory 免费接口，解析 `responseData.translatedText`。 */
    private suspend fun myMemoryTranslate(
        cfg: Config,
        text: String,
        source: String,
        target: String,
    ): String {
        val langpair = myMemoryLangPair(source, target)
        val chunks = chunkByWords(text, MYMEMORY_CHUNK_BYTES, ::utf8ByteLength)
        val pieces = ArrayList<String>(chunks.size)
        for (chunk in chunks) {
            pieces += myMemoryTranslateChunk(cfg, chunk, langpair)
        }
        return pieces.joinToString(" ").trim()
    }

    /** 语言对：MyMemory 的中文代码是 `zh-CN`。 */
    private fun myMemoryLangPair(source: String, target: String): String = when {
        source == "zh" -> "zh-CN|$target"
        target == "zh" -> "$source|zh-CN"
        else -> "$source|$target"
    }

    private suspend fun myMemoryTranslateChunk(cfg: Config, text: String, langpair: String): String {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.mymemory.translated.net")
            .addPathSegment("get")
            .addQueryParameter("q", text)
            .addQueryParameter("langpair", langpair)
            .build()
        val body = httpGetText(url, cfg)
        val root = JSONObject(body)
        val status = root.optInt("responseStatus", 200)
        if (status != 200) {
            throw IOException("状态异常（$status）")
        }
        val data = root.optJSONObject("responseData")
            ?: throw IOException("返回格式无法解析")
        val raw = data.optString("translatedText").trim()
        if (raw.isEmpty() || raw == "null") throw IOException("返回空译文")
        if (raw.uppercase().startsWith("MYMEMORY WARNING")) {
            throw IOException("免费额度已用完")
        }
        return decodeHtmlEntities(raw).trim()
    }

    /** 备用服务：LibreTranslate 公共实例（POST JSON），逐个实例尝试到成功为止。 */
    private suspend fun libreTranslate(
        cfg: Config,
        text: String,
        source: String,
        target: String,
    ): String {
        val chunks = chunkByWords(text, LIBRE_CHUNK_CHARS) { it.length }
        val pieces = ArrayList<String>(chunks.size)
        for (chunk in chunks) {
            pieces += libreTranslateChunk(cfg, chunk, source, target)
        }
        return pieces.joinToString(" ").trim()
    }

    private suspend fun libreTranslateChunk(
        cfg: Config,
        text: String,
        source: String,
        target: String,
    ): String {
        val payload = JSONObject().apply {
            put("q", text)
            put("source", source)
            put("target", target)
            put("format", "text")
        }.toString()
        var lastFailure: Throwable? = null
        for (endpoint in LIBRE_ENDPOINTS) {
            try {
                val body = httpPostJson(endpoint, payload, cfg)
                val root = JSONObject(body)
                val error = root.optString("error")
                if (error.isNotBlank() && error != "null") {
                    throw IOException("服务返回错误")
                }
                val raw = root.optString("translatedText").trim()
                if (raw.isEmpty() || raw == "null") throw IOException("返回空译文")
                return decodeHtmlEntities(raw).trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastFailure = e
            }
        }
        throw lastFailure ?: IOException("公共实例不可用")
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

    private suspend fun httpPostJson(endpoint: String, payload: String, cfg: Config): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent(cfg))
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            libreHttp.newCall(request).execute().use { resp ->
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

    private fun putCache(key: String, value: String) {
        if (cache.size >= CACHE_LIMIT) cache.clear()
        cache[key] = value
    }

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

    /** 把异常转成短描述拼进可读错误里（不向用户泄露原始响应体）。 */
    private fun describeFailure(e: Throwable): String = when (e) {
        is ApiException -> if (e.code == 429) "免费额度已用完" else "HTTP ${e.code}"
        is SocketTimeoutException -> "请求超时"
        else -> e.message?.trim()?.take(40)?.takeIf { it.isNotEmpty() } ?: "请求失败"
    }
}

// ---------------------------------------------------------------- 文件级纯函数工具

/** 文本里是否含中日韩统一表意文字（判断"已经是中文 / 需要中→英"用）。 */
private fun containsCjk(text: String): Boolean = text.any { ch ->
    val code = ch.code
    code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
}

/** 文本的 UTF-8 字节数（MyMemory 的 `q` 长度上限按字节算）。 */
private fun utf8ByteLength(text: String): Int = text.toByteArray(Charsets.UTF_8).size

private val TRANSLATION_WHITESPACE = Regex("\\s+")

/**
 * 按空白把长文本切成若干块（尽量保持词/句子完整），让在线接口的逐块请求都不超长度上限。
 * [measure] 返回一段文本的长度（MyMemory 用 UTF-8 字节数，LibreTranslate 用字符数）。
 */
private fun chunkByWords(text: String, limit: Int, measure: (String) -> Int): List<String> {
    if (limit <= 0 || measure(text) <= limit) return listOf(text)
    val chunks = ArrayList<String>(2)
    val current = StringBuilder()
    var currentSize = 0

    fun flush() {
        if (current.isEmpty()) return
        chunks.add(current.toString())
        current.setLength(0)
        currentSize = 0
    }

    for (token in text.split(TRANSLATION_WHITESPACE)) {
        if (token.isEmpty()) continue
        val tokenSize = measure(token)
        if (tokenSize > limit) {
            // 单个超长词：按字符硬切，保证一定能装进上限。
            flush()
            var start = 0
            while (start < token.length) {
                var end = start
                var budget = 0
                while (end < token.length) {
                    val cp = token.codePointAt(end)
                    val step = Character.charCount(cp)
                    val pieceSize = measure(token.substring(end, end + step))
                    if (budget + pieceSize > limit) break
                    budget += pieceSize
                    end += step
                }
                if (end == start) end = start + 1
                chunks.add(token.substring(start, end))
                start = end
            }
            continue
        }
        val separator = if (current.isEmpty()) 0 else 1
        if (currentSize + separator + tokenSize > limit) {
            flush()
            current.append(token)
            currentSize = tokenSize
        } else {
            if (current.isNotEmpty()) current.append(' ')
            current.append(token)
            currentSize += separator + tokenSize
        }
    }
    flush()
    return chunks.ifEmpty { listOf(text) }
}
