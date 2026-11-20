package com.mikesteve.e621downloadler.net

import com.mikesteve.e621downloadler.BuildConfig

/**
 * 开发期网络锁（Android 侧）。
 *
 * 与桌面版的 `netguard.py` 同义，但 Android 多一道系统级防线：
 * debug 构建**没有** INTERNET 权限（见 app/src/main/AndroidManifest.xml 与 src/release 的差异），
 * 所以即使有人绕过这里的判断，系统也会拒绝建连。
 *
 * 规则：
 *  - debug 构建：networkAllowed = false，任何网络调用在发请求前抛 [NetworkLockedException]
 *  - release 构建：networkAllowed = true
 *  - 没有任何运行时开关可以解除；要联网只能打 release 包
 */
class NetworkLockedException(message: String) : RuntimeException(message)

object NetGuard {
    /** 当前是否为开发（debug）构建。 */
    val isDevelopmentBuild: Boolean get() = BuildConfig.DEBUG

    /** 是否允许联网。 */
    val networkAllowed: Boolean get() = !BuildConfig.DEBUG

    /** 所有网络入口在真正发请求之前调用它。 */
    fun ensureNetworkAllowed() {
        if (!networkAllowed) {
            throw NetworkLockedException(
                "开发版本已启用网络锁：禁止任何联网请求。发布构建才会解除该限制。",
            )
        }
    }
}
