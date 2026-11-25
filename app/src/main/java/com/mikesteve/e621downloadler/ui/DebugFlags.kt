package com.mikesteve.e621downloadler.ui

/**
 * 仅 debug 构建可用的调试开关。
 *
 * `demo = true` 时（`adb shell am start ... --ez e6dl_demo true`）发现页/收藏页会注入
 * 本地演示数据，用于在开发期硬禁网的前提下验证三种窗口尺寸的布局。
 * `demoQuery`（`--es e6dl_query 狗`）是配套的输入框预填，用于离线验证中文候选/翻译等
 * 需要中文输入的 UI（adb `input text` 只能输入 ASCII）。
 * release 构建里 [demo] 永远为 false、[demoQuery] 永远为空。
 */
object DebugFlags {
    var demo: Boolean = false
    var demoQuery: String = ""
}
