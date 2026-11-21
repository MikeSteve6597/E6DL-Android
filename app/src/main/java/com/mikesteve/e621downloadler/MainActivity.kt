package com.mikesteve.e621downloadler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mikesteve.e621downloadler.ui.DebugFlags
import com.mikesteve.e621downloadler.ui.E6DLApp
import com.mikesteve.e621downloadler.ui.theme.E6DLTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 仅 debug 构建：允许通过 intent extra 打开演示数据（离线 UI 验证用）
        DebugFlags.demo = BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_DEMO, false) == true
        setContent {
            E6DLTheme {
                E6DLApp()
            }
        }
    }

    private companion object {
        const val EXTRA_DEMO = "e6dl_demo"
    }
}
