package com.mikesteve.e621downloadler.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.ConfigStore
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.download.DownloadEngine
import com.mikesteve.e621downloadler.download.DownloadProgress
import com.mikesteve.e621downloadler.net.ApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 全局状态容器（等价于桌面版 `MainWindow` 持有的 config / session / worker）。
 *
 * 刻意不用 Hilt：工程规模还小，一个 AndroidViewModel 就够，少一层依赖少一处坑。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ConfigStore(app)
    val api = ApiClient()
    private val engine = DownloadEngine(app, api)

    val config: StateFlow<Config> =
        store.config.stateIn(viewModelScope, SharingStarted.Eagerly, Config())

    val downloadDir: java.io.File get() = engine.downloadDir

    // ------------------------------------------------------------------ 下载
    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress
    private var downloadJob: Job? = null

    val isDownloading: Boolean get() = downloadJob?.isActive == true

    fun startDownload(
        tags: List<String>,
        blacklist: List<String>,
        quality: String,
        maxPages: Int,
        limit: Int,
        minScore: Int?,
        onFinish: (String) -> Unit,
    ) {
        if (isDownloading) return
        val cfg = config.value
        downloadJob = viewModelScope.launch {
            _progress.value = DownloadProgress(message = "准备中…")
            try {
                val result = engine.download(
                    cfg = cfg,
                    tags = tags,
                    blacklist = blacklist,
                    quality = quality,
                    maxPages = maxPages,
                    limit = limit,
                    minScore = minScore,
                    onProgress = { _progress.value = it },
                )
                onFinish(result.message)
            } catch (e: kotlinx.coroutines.CancellationException) {
                onFinish("已取消")
            } catch (e: Exception) {
                onFinish(com.mikesteve.e621downloadler.net.friendlyMessage(e))
            } finally {
                _progress.value = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun downloadSingle(post: Post, quality: String, onFinish: (String) -> Unit) {
        viewModelScope.launch {
            val url = post.imageUrl(quality)
            if (url.isNullOrBlank()) {
                onFinish("没有可下载的图片地址")
                return@launch
            }
            val target = java.io.File(engine.downloadDir, post.targetFilename())
            val tmp = java.io.File(engine.downloadDir, "${post.targetFilename()}.part")
            val ok = try {
                engine.downloadOne(config.value, url, tmp, target)
            } catch (e: Exception) {
                false
            }
            onFinish(if (ok) "已保存：${target.name}" else "下载失败")
        }
    }

    fun exportZip(uri: android.net.Uri, onFinish: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val count = engine.exportZip(uri)
                onFinish(if (count == 0) "下载目录为空" else "已导出 $count 个文件")
            } catch (e: Exception) {
                onFinish("导出失败：${e.message}")
            }
        }
    }

    fun openDownloadDirHint(): String = engine.downloadDir.absolutePath

    /** 批量下载给定作品（收藏页用），按顺序逐张下载，失败不影响后续。 */
    fun downloadPosts(posts: List<Post>, quality: String, onFinish: (String) -> Unit) {
        if (posts.isEmpty()) {
            onFinish("没有可下载的作品")
            return
        }
        viewModelScope.launch {
            var ok = 0
            var skipped = 0
            var failed = 0
            val cfg = config.value
            for (post in posts) {
                if (post.isVideo) {
                    skipped++
                    continue
                }
                val url = post.imageUrl(quality)
                if (url.isNullOrBlank()) {
                    failed++
                    continue
                }
                val target = java.io.File(engine.downloadDir, post.targetFilename())
                if (target.exists() && target.length() > 0) {
                    skipped++
                    continue
                }
                val tmp = java.io.File(engine.downloadDir, "${post.targetFilename()}.part")
                try {
                    if (engine.downloadOne(cfg, url, tmp, target)) ok++ else failed++
                } catch (e: Exception) {
                    failed++
                }
            }
            onFinish("完成：成功 $ok，跳过 $skipped，失败 $failed")
        }
    }

    // ------------------------------------------------------------------ 配置
    fun setMode(mode: String) = update { it.copy(mode = mode) }
    fun setTheme(theme: String) = update { it.copy(theme = theme) }
    fun setQuality(quality: String) = update { it.copy(quality = quality) }
    fun setRememberHistory(enabled: Boolean) = update { it.copy(rememberHistory = enabled) }
    fun setKeywordMemory(limit: Int) = update { it.copy(keywordMemory = limit.coerceIn(0, Config.MAX_KEYWORD_MEMORY)) }
    fun setSearchDefaults(maxPages: Int, limit: Int, minScore: Int) = update {
        it.copy(
            maxPages = maxPages.coerceIn(1, 50),
            limit = limit.coerceIn(1, 320),
            minScore = minScore,
        )
    }

    fun saveLogin(username: String, apiKey: String) = update {
        it.copy(username = username.trim(), apiKey = apiKey.trim())
    }

    fun logout() = update { it.copy(username = "", apiKey = "") }

    fun addBlacklist(tag: String) = update { cfg ->
        val t = tag.trim()
        if (t.isEmpty() || cfg.blacklist.contains(t)) cfg else cfg.copy(blacklist = cfg.blacklist + t)
    }

    fun removeBlacklist(tag: String) = update { cfg ->
        cfg.copy(blacklist = cfg.blacklist.filterNot { it == tag })
    }

    fun clearHistory() = update { it.copy(history = emptyList()) }

    fun forgetKeyword(keyword: String) = update { cfg ->
        cfg.copy(recentKeywords = cfg.recentKeywords.filterNot { it == keyword })
    }

    fun rememberKeyword(keyword: String) {
        viewModelScope.launch { store.addRecentKeyword(keyword) }
    }

    fun rememberHistory(tags: List<String>, blacklist: List<String>) {
        viewModelScope.launch { store.addHistory(tags, blacklist) }
    }

    fun validateLogin(username: String, apiKey: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val (ok, _, msg) = api.validateLogin(username, apiKey)
            onResult(ok, msg)
        }
    }

    private fun update(transform: (Config) -> Config) {
        viewModelScope.launch { store.update(transform) }
    }
}
