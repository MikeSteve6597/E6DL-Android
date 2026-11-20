package com.mikesteve.e621downloadler

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toPath
import java.io.File

/**
 * 应用入口：配置 Coil（图片加载器）。
 *
 * 与桌面版 `ImageLoader` 对齐：
 *  - 磁盘缓存上限 256 MB（桌面版也是 256 MB）
 * 与桌面版不同：debug 构建没有 INTERNET 权限，图片会直接加载失败——这正是开发期禁网的预期结果。
 */
class E6DLApplication : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val dir = File(this.cacheDir, "image_cache/coil").apply { mkdirs() }
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .diskCache {
                DiskCache.Builder()
                    .directory(dir.absolutePath.toPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
