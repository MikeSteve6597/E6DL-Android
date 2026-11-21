package com.mikesteve.e621downloadler.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.Post

/**
 * 应用内视频播放器（Media3 / ExoPlayer）。
 *
 * - 地址来自 `Post.videoUrl`（新接口 `files.video.*` 逐级回退；旧接口回退到 `file.url`）；
 * - 播放器随 Composable 销毁释放；Activity 进入后台（ON_STOP）自动暂停，避免后台继续解码；
 * - 网络锁/单机模式由 `NetworkGate` 在 API 层拦截：开发构建下视频加载失败会显示错误文案，这是预期行为；
 * - SWF（Flash）不被 ExoPlayer 支持，会落到错误提示，可用「在浏览器中打开」。
 */
@Composable
fun PostVideoPlayer(
    post: Post,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val videoUrl = post.videoUrl
    var errorText by remember(post.id) { mutableStateOf<String?>(null) }

    val player = remember(post.id) {
        ExoPlayer.Builder(context).build()
    }

    // 播放器资源释放
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // 监听播放错误（网络锁、编码不支持、404 等）
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorText = error.errorCodeName + (error.message?.let { "：$it" } ?: "")
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 切换作品/地址时重建媒体项
    LaunchedEffect(player, videoUrl) {
        errorText = null
        if (!videoUrl.isNullOrBlank()) {
            player.setMediaItem(MediaItem.fromUri(videoUrl))
            player.prepare()
        }
    }

    // 进入后台时暂停（不做后台播放；与"不使用前台服务"的产品约定一致）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (videoUrl.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.e6dl_video_no_url),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            } else {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        errorText?.let { message ->
            Text(
                text = stringResource(R.string.e6dl_video_error, message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
