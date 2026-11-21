package com.mikesteve.e621downloadler.ui

/**
 * 仅 debug 构建可用的调试开关。
 *
 * `demo = true` 时（`adb shell am start ... --ez e6dl_demo true`）发现页/收藏页会注入
 * 本地演示数据，用于在开发期硬禁网的前提下验证三种窗口尺寸的布局。
 * release 构建里 [demo] 永远为 false。
 */
object DebugFlags {
    var demo: Boolean = false
}
