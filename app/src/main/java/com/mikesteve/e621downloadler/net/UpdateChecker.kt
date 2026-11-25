package com.mikesteve.e621downloadler.net

import com.mikesteve.e621downloadler.BuildConfig
import com.mikesteve.e621downloadler.data.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 启动自检发现的远端新版本。
 *
 * @property versionName 从 tag 里解析出的纯数字版本号（如 `1.5.0`，不含 `android-v` 前缀），用于展示。
 * @property tagName 远端 release 的原始 tag（如 `android-v1.5.0`），调用方可用它做「跳过此版本」。
 * @property releaseUrl release 的 GitHub 页面（API 的 `html_url`），供「前往下载」使用。
 */
data class AppUpdateInfo(
    val versionName: String,
    val tagName: String,
    val releaseUrl: String,
)

/**
 * 启动自检更新（Android 仓库，不是桌面仓库）。
 *
 * 只查 GitHub 的 `releases/latest`（正式版，`draft`/`prerelease` 会被排除），
 * 把 tag 里的 `android-vX.Y.Z` 与 [BuildConfig.VERSION_NAME] 做语义化数字逐段比较：
 * 只有远端**严格更新**才返回 [AppUpdateInfo]，否则返回 null。
 *
 * 失败策略：网络/HTTP/JSON 解析失败一律返回 null（不崩溃、不弹错），
 * 单机模式与 debug 构建也直接返回 null（debug 构建本身没有 INTERNET 权限）；
 * 更新检查失败绝不能影响应用启动。所有请求都先过 [NetGuard] / [NetworkGate]，不绕过网络锁。
 */
object UpdateChecker {

    /** Android 仓库的 latest release（`tag_name` 形如 `android-v1.5.0`）。 */
    private const val RELEASES_LATEST_API =
        "https://api.github.com/repos/MikeSteve6597/E6DL-Android/releases/latest"

    /** `html_url` 缺失/异常时的兜底落地页。 */
    private const val RELEASES_PAGE = "https://github.com/MikeSteve6597/E6DL-Android/releases"

    /** 只认 Android 包的 tag 前缀，桌面仓库的 `vX.Y.Z` 不会被误判。 */
    private const val TAG_PREFIX = "android-v"

    private const val TIMEOUT_SECONDS = 12L

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 查询远端最新版；有更新返回 [AppUpdateInfo]，无更新/不可查返回 null。
     *
     * 协程可取消：取消异常会向上抛，调用方（`LaunchedEffect`）正常结束即可。
     */
    suspend fun checkLatest(cfg: Config): AppUpdateInfo? {
        // 网络门禁与 ApiClient 完全一致：先开发期网络锁，再运行模式（单机直接拒绝）。
        // NetworkGate.require 内部也会调用 NetGuard.ensureNetworkAllowed，这里显式写出来，
        // 让「两道门禁、先锁后模式」的顺序一眼可见，且与桌面版语义对齐。
        try {
            NetGuard.ensureNetworkAllowed()
            NetworkGate.require(cfg.mode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // debug 网络锁 / 单机模式：启动自检静默跳过，不打扰用户。
            return null
        }

        val request = Request.Builder()
            .url(RELEASES_LATEST_API)
            .header("Accept", "application/vnd.github+json")
            // GitHub API 强制要求 User-Agent；带上版本号便于服务端识别。
            .header("User-Agent", "E6DL/${BuildConfig.VERSION_NAME} (Android)")
            .build()

        return try {
            withContext(Dispatchers.IO) { fetchLatest(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 超时/无网络/DNS 失败/JSON 解析失败：静默视为「没有更新」。
            null
        }
    }

    /**
     * 同步执行请求并解析。HTTP 非 2xx（含 GitHub 未登录限流 403/429）、
     * 响应不是 JSON、缺字段等情况都返回 null。
     */
    private fun fetchLatest(request: Request): AppUpdateInfo? =
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body.string()
            val text = body.trimStart()
            if (text.isEmpty() || !text.startsWith("{")) return null

            val json = JSONObject(body)
            // /releases/latest 理论上不会再返回 draft/prerelease，防御性再判一次。
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null

            val tagName = json.optString("tag_name").trim()
            if (tagName.isEmpty()) return null
            val remoteVersion = parseVersionFromTag(tagName) ?: return null
            if (!isNewerVersion(remoteVersion, BuildConfig.VERSION_NAME)) return null

            val htmlUrl = json.optString("html_url").trim()
            val releaseUrl = if (htmlUrl.startsWith("https://")) htmlUrl else RELEASES_PAGE
            AppUpdateInfo(versionName = remoteVersion, tagName = tagName, releaseUrl = releaseUrl)
        }

    /**
     * 从 `android-v1.5.0` 提取 `1.5.0`；前缀不符或版本段不是纯数字时返回 null。
     * （对外暴露便于 JVM 单测，不参与请求。）
     */
    fun parseVersionFromTag(tagName: String): String? {
        val trimmed = tagName.trim()
        if (!trimmed.startsWith(TAG_PREFIX)) return null
        val version = trimmed.removePrefix(TAG_PREFIX).trim()
        return if (parseNumericVersion(version) != null) version else null
    }

    /**
     * 语义化版本比较：远端比当前**严格更新**返回 true。
     *
     * 逐段比较数字（缺的段按 0 处理），如 `1.5.0` vs `1.4.9` → true、`1.4.0` vs `1.4.0` → false；
     * 任一侧无法解析时返回 false（宁可不提示，也不误报更新）。
     */
    fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = parseNumericVersion(remote) ?: return false
        val currentParts = parseNumericVersion(current) ?: return false
        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val a = remoteParts.getOrElse(i) { 0 }
            val b = currentParts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * 解析 `1.5.0` / `1.5.0-beta.1`（只取 `-` 或 `+` 之前的点分数字段）。
     * 空串、非数字段（如 `abc`）返回 null。
     */
    private fun parseNumericVersion(raw: String): List<Int>? {
        val core = raw.trim().substringBefore('-').substringBefore('+').trim()
        if (core.isEmpty()) return null
        val segments = core.split('.')
        if (segments.isEmpty()) return null
        val out = ArrayList<Int>(segments.size)
        for (seg in segments) {
            if (seg.isEmpty() || !seg.all { it in '0'..'9' }) return null
            out.add(seg.toIntOrNull() ?: return null)
        }
        return out
    }
}
