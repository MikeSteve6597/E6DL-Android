import java.util.Properties

plugins {
    // AGP 9.0 起 Kotlin 支持已内置，不再需要 org.jetbrains.kotlin.android；
    // 但 Compose 编译器插件（Kotlin 2.0+）仍然必须显式应用。
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// 发布签名
// 密码放在 <工程根>/keystore.properties（已被 .gitignore 排除）；
// 密钥库放在 <项目根>/tools/android-signing/e6dl-release.jks（不随 GitHub 树走）。
// 这里用目录推算而不是写死盘符，换机器/换盘符不会失效。
// ---------------------------------------------------------------------------
val signingDir = rootProject.projectDir.parentFile.parentFile.parentFile
    .resolve("tools/android-signing")
val keystoreProps = Properties()
run {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { keystoreProps.load(it) }
    }
}

// 仅用于本机模拟器验证：debug 构建默认只出 arm64-v8a（与发布物一致）。
// 需要在 x86_64 模拟器上装 debug 包时，用：
//   gradle :app:assembleDebug -Pe6dl.extraAbi=x86_64
// release 构建脚本不传这个属性，因此发布物始终只含 arm64-v8a。
val extraAbi: String = (project.findProperty("e6dl.extraAbi") as String?)?.trim().orEmpty()

android {
    namespace = "com.mikesteve.e621downloadler"
    // compileSdk 37：2026.11 的 androidx 依赖强制要求（见 libs.versions.toml 注释）
    // 小版本必须 >= 37.1（Compose 1.13 的 AAR 元数据要求），这里跟到 37.2。
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.mikesteve.e621downloadler"
        minSdk = 24
        // 不上架商店，targetSdk 用 36；compileSdk 只影响可调用的 API。
        targetSdk = 36
        versionCode = 7
        versionName = "1.3.0"

        // 只发 ARMv8（用户要求：只开发 arm64-v8a）
        ndk {
            abiFilters += listOf("arm64-v8a")
            if (extraAbi.isNotEmpty()) abiFilters += extraAbi
        }
    }

    signingConfigs {
        if (keystoreProps.size > 0) {
            create("release") {
                storeFile = signingDir.resolve(keystoreProps.getProperty("storeFile")).takeIf { it.exists() }
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 开发期绝对不联网：debug 变体没有 INTERNET 权限（见 src/debug 的 tools:node="remove"）
            isMinifyEnabled = false
        }
        release {
            // 正式版：保留可调试性关闭、开启签名、先不做混淆（体积/稳定优先，P7 再评估）
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)
    implementation(libs.androidx.material3.adaptive.navigation)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // 应用内视频播放（ExoPlayer + 控制条 UI）
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    debugImplementation(libs.androidx.ui.tooling)

    // JVM 单元测试（解析兼容性 / 配置编解码 / 搜索标签构建）
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
