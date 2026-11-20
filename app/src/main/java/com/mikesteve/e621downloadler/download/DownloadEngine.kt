package com.mikesteve.e621downloadler.download

import android.content.Context
import android.net.Uri
import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.net.ApiClient
import com.mikesteve.e621downloadler.net.NetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 下载进度快照，供 UI 渲染。 */
data class DownloadProgress(
    val done: Int = 0,
    val total: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val currentName: String = "",
    val message: String = "",
)

/**
 * 下载引擎：与桌面版 `DownloadWorker` 语义一致，但**不做后台服务**（用户明确要求）。
 *
 * - 文件落在应用专属目录：`Android/data/<包名>/files/E6dl/`（卸载即删）
 * - `.part` 临时文件 + 原子重命名，取消/失败不留残片
 * - `downloaded_md5.txt` 去重，仅"已登录且勾选保存历史"时读写（游客不落盘）
 */
class DownloadEngine(
    private val context: Context,
    private val api: ApiClient,
) {

    val downloadDir: File
        get() {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "E6dl").apply { if (!exists()) mkdirs() }
        }

    private val historyFile: File get() = File(downloadDir, "downloaded_md5.txt")

    fun readSeenMd5(): MutableSet<String> {
        val seen = HashSet<String>()
        if (historyFile.exists()) {
            historyFile.forEachLine { line -> line.trim().takeIf { it.isNotEmpty() }?.let { seen.add(it) } }
        }
        return seen
    }

    private fun appendSeenMd5(md5: String) {
        try {
            historyFile.appendText(md5 + "\n")
        } catch (_: IOException) {
            // 去重记录失败不影响下载本身
        }
    }

    /**
     * 批量下载。会按页拉取（每页 `limit` 条，最多 `maxPages` 页），页间隔 1.1 s。
     *
     * @return 实际下载成功 + 跳过 + 失败 的统计
     */
    suspend fun download(
        cfg: Config,
        tags: List<String>,
        blacklist: List<String>,
        quality: String,
        maxPages: Int,
        limit: Int,
        minScore: Int?,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadProgress = withContext(Dispatchers.IO) {
        NetworkGate.require(cfg.mode)

        val remember = cfg.loggedIn && cfg.rememberHistory
        val seen = if (remember) readSeenMd5() else HashSet()

        var done = 0
        var skipped = 0
        var failed = 0
        val collected = ArrayList<Post>()

        for (page in 1..maxPages) {
            onProgress(DownloadProgress(done, collected.size, skipped, failed, "", "正在获取第 $page 页…"))
            val batch = try {
                api.fetchPosts(cfg, tags, blacklist, minScore, limit = limit, page = page)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onProgress(
                    DownloadProgress(done, collected.size, skipped, failed, "", "第 $page 页获取失败：${e.message}"),
                )
                break
            }
            if (batch.isEmpty()) break
            collected.addAll(batch)
            onProgress(
                DownloadProgress(done, collected.size, skipped, failed, "", "已发现 ${collected.size} 项，开始下载…"),
            )
            if (batch.size < limit) break
            if (page < maxPages) delay(1100)
        }

        val total = collected.size
        for ((index, post) in collected.withIndex()) {
            val name = post.targetFilename()
            onProgress(DownloadProgress(done, total, skipped, failed, name, "下载中 ${index + 1}/$total"))

            if (post.isVideo) {
                skipped++
                continue
            }
            if (post.md5 in seen) {
                skipped++
                continue
            }

            val url = post.imageUrl(quality)
            if (url.isNullOrBlank()) {
                failed++
                continue
            }

            val target = File(downloadDir, name)
            if (target.exists() && target.length() > 0) {
                skipped++
                if (remember) {
                    seen.add(post.md5)
                    appendSeenMd5(post.md5)
                }
                continue
            }

            val tmp = File(downloadDir, "$name.part")
            try {
                val ok = downloadOne(cfg, url, tmp, target)
                if (ok) {
                    done++
                    if (remember) {
                        seen.add(post.md5)
                        appendSeenMd5(post.md5)
                    }
                } else {
                    failed++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                tmp.delete()
                throw e
            } catch (e: Exception) {
                tmp.delete()
                failed++
            }
        }

        val summary = buildString {
            append("完成：成功 $done")
            if (skipped > 0) append("，跳过 $skipped")
            if (failed > 0) append("，失败 $failed")
        }
        DownloadProgress(done, total, skipped, failed, "", summary)
    }

    /** 单张下载；返回是否成功。失败时清理 `.part`。 */
    suspend fun downloadOne(cfg: Config, url: String, tmp: File, target: File): Boolean {
        NetworkGate.require(cfg.mode)
        return withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "E6DL/${com.mikesteve.e621downloadler.BuildConfig.VERSION_NAME} (download)")
                    .build()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    resp.body.byteStream().use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                tmp.delete()
                throw e
            } catch (e: Exception) {
                tmp.delete()
                false
            }
        }
    }

    /** 把下载目录打包成 zip 写到用户选定的位置（SAF）。 */
    suspend fun exportZip(uri: Uri): Int = withContext(Dispatchers.IO) {
        val files = downloadDir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) return@withContext 0
        val resolver = context.contentResolver
        val out = resolver.openOutputStream(uri) ?: throw IOException("无法写入目标文件")
        ZipOutputStream(out.buffered()).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        files.size
    }
}
