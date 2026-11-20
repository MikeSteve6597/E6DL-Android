package com.mikesteve.e621downloadler.ui.pages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.BuildConfig
import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.Quality
import com.mikesteve.e621downloadler.net.NetGuard
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.components.InfoBar
import com.mikesteve.e621downloadler.ui.components.InfoType
import com.mikesteve.e621downloadler.ui.components.SectionCard

/**
 * 设置页（对齐桌面版 `SettingsPage`）。
 *
 * 关键点：**单机 / 在线模式开关可以手动切换**（状态会持久化），
 * 但 debug 构建受开发期网络锁保护——切到"在线"也不会真的联网，
 * 页面会明确提示这是开发限制，避免被误当成 bug。
 */
@Composable
fun SettingsPage(
    vm: AppViewModel,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cfg by vm.config.collectAsState()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var messageType by remember { mutableStateOf(InfoType.Info) }
    var keywordMemory by remember(cfg.keywordMemory) { mutableIntStateOf(cfg.keywordMemory) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri != null) {
            vm.exportZip(uri) { result ->
                message = result
                messageType = if (result.contains("失败")) InfoType.Warning else InfoType.Success
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InfoBar(message = message, type = messageType)

        SectionCard(title = "运行模式") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = cfg.mode == Config.MODE_OFFLINE,
                    onClick = {
                        vm.setMode(Config.MODE_OFFLINE)
                        message = "已切换为单机模式，联网功能已全部禁用。"
                        messageType = InfoType.Info
                    },
                    label = { Text("单机模式") },
                )
                FilterChip(
                    selected = cfg.mode == Config.MODE_ONLINE,
                    onClick = {
                        vm.setMode(Config.MODE_ONLINE)
                        message = if (NetGuard.networkAllowed) {
                            "已切换为在线模式。"
                        } else {
                            "已切换为在线模式。但这是开发构建：网络仍被锁死（没有 INTERNET 权限），请求会直接失败。"
                        }
                        messageType = if (NetGuard.networkAllowed) InfoType.Success else InfoType.Warning
                    },
                    label = { Text("在线模式") },
                )
            }
            Text(
                text = if (NetGuard.networkAllowed) {
                    "当前为发布构建，可以在线模式访问 e621。"
                } else {
                    "开发构建：开关可以手动切换（状态会保存），但网络被双重锁死（无 INTERNET 权限 + NetGuard），实际不会发出任何请求。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "外观") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) ->
                    FilterChip(
                        selected = cfg.theme == key,
                        onClick = { vm.setTheme(key) },
                        label = { Text(label) },
                    )
                }
            }
        }

        SectionCard(title = "下载") {
            Text("默认画质", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Quality.labels.forEach { (key, label) ->
                    FilterChip(
                        selected = cfg.quality == key,
                        onClick = { vm.setQuality(key) },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                text = "下载目录（应用专属，卸载即删）：\n${vm.openDownloadDirHint()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { exportLauncher.launch("E6DL-export.zip") }) {
                Text("导出压缩包到其他文件夹…")
            }
        }

        SectionCard(title = "搜索关键词记忆") {
            Text("保留最近 $keywordMemory 条（0 = 不记忆）", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = keywordMemory.toFloat(),
                onValueChange = { v -> keywordMemory = v.toInt() },
                onValueChangeFinished = { vm.setKeywordMemory(keywordMemory) },
                valueRange = 0f..50f,
                steps = 49,
            )
        }

        SectionCard(title = "账号") {
            if (cfg.loggedIn) {
                Text("已登录：${cfg.username}", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenLogin) { Text("更换账号") }
                    TextButton(onClick = {
                        vm.logout()
                        message = "已退出登录，本机保存的用户名与 API Key 已删除。"
                        messageType = InfoType.Info
                    }) { Text("退出登录") }
                }
            } else {
                Text("未登录：可浏览与游客下载，收藏功能需要登录。", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onOpenLogin) { Text("登录 e621") }
            }
        }

        SectionCard(title = "本地数据") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    vm.clearHistory()
                    message = "搜索历史已清空。"
                    messageType = InfoType.Success
                }) { Text("清空搜索历史") }
                OutlinedButton(onClick = {
                    val ok = clearImageCache(context)
                    message = if (ok) "图片缓存已清空。" else "清空缓存失败。"
                    messageType = if (ok) InfoType.Success else InfoType.Warning
                }) { Text("清空图片缓存") }
            }
            Text(
                text = "去重记录：下载目录下的 downloaded_md5.txt（仅登录且勾选保存历史时写入）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "关于") {
            Text("E6DL ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "minSdk 24 · targetSdk 36 · 仅 arm64-v8a\n开发期网络锁：${if (NetGuard.networkAllowed) "关闭（发布构建）" else "开启"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "本项目为第三方工具，与 e621 官方无关联。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 清空 Coil 磁盘缓存目录。 */
private fun clearImageCache(context: android.content.Context): Boolean = try {
    val dir = java.io.File(context.cacheDir, "image_cache")
    if (dir.exists()) dir.deleteRecursively()
    true
} catch (e: Exception) {
    false
}
