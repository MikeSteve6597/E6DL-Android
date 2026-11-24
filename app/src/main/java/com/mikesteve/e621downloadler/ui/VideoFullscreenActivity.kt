package com.mikesteve.e621downloadler.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mikesteve.e621downloadler.R

/**
 * 视频全屏播放页（v1.4.0）。
 *
 * 为什么用独立 Activity 而不是 Compose Dialog：
 *  - `PlayerView` 内部是 SurfaceView，Compose 的 Dialog + AndroidView 叠层在部分设备上
 *    点击/系统栏表现不稳定；独立窗口是最可靠的全屏方式；
 *  - 进入时锁定横屏 + 沉浸式（隐藏状态栏/导航栏）+ 屏幕常亮；
 *  - 关闭按钮在播放器区域之外（顶部栏），不会被 SurfaceView 挡住；
 *  - 从详情页传入 URL 与当前播放位置，全屏继续播放；销毁时释放 ExoPlayer。
 *
 * 注意：本 Activity 只做播放，不做任何网络请求（URL 由调用方提供，网络门禁在详情页已生效）。
 */
class VideoFullscreenActivity : Activity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val startPosition = intent.getLongExtra(EXTRA_POSITION, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        val playerView = PlayerView(this).apply {
            player = exo
            useController = true
        }
        root.addView(
            playerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        if (url.isNotBlank()) {
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.prepare()
            if (startPosition > 0) exo.seekTo(startPosition)
            exo.playWhenReady = true
        }
        // 关闭按钮放在播放器外部区域（顶部），SurfaceView 不会挡住它的点击。
        val close = Button(this).apply {
            text = getString(R.string.e6dl_video_exit_fullscreen)
            setOnClickListener { finish() }
        }
        root.addView(
            close,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = 24
                rightMargin = 24
            },
        )
        if (title.isNotBlank()) {
            root.addView(
                android.widget.TextView(this).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 16f
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START,
                ).apply {
                    topMargin = 32
                    leftMargin = 32
                },
            )
        }
        setContentView(root)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_POSITION = "position"
        const val EXTRA_TITLE = "title"

        fun intent(activity: Activity, url: String, positionMs: Long, title: String = ""): Intent =
            Intent(activity, VideoFullscreenActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_POSITION, positionMs)
                .putExtra(EXTRA_TITLE, title)
    }
}
