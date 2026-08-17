import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "icu.gxb.hypertv"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "icu.gxb.hypertv"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"

    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric 需要访问 Android resources（Room in-memory 单测）
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Compose BOM：Gradle 9 起 platform 约束不再自动传播到 implementation 以外的配置，
    // 因此凡引用无版本号 Compose 库的配置都必须显式声明 BOM（官方推荐写法）。
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // Ktor client（URL 拉取 M3U 源）
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Coil（台标异步加载 + 网络 fetcher + 磁盘缓存）
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // ZXing
    implementation(libs.zxing.core)
    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // XmlPullParser（XMLTV 流式解析）：Android 框架自带 org.xmlpull.v1 实现，release 无需打包 kxml2
    // （避免 R8: Library class XmlResourceParser implements program class XmlPullParser 冲突）；
    // JVM 单测运行时无框架实现，故仅 testImplementation。
    // 注意：kxml2 的 META-INF/services 若被打进 APK 会引 KXmlParser，降级后不再打包。

    // Media3 播放器（ExoPlayer 核心 + HLS 支持 + PlayerView 控件 + MediaSession）
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    // kxml2：仅 JVM 单测需要（XmlPullParser 实现）
    testImplementation(libs.kxml2)

    // 注：项目暂无 androidTest 源码（instrumentation 测试），全部测试为 test/ 下 JVM 单测；
    // 若将来引入 Compose UI 测试，需恢复 ui-test-junit4 / ui-test-manifest，且由于 BOM 约束
    // 不传播到 androidTestImplementation，还需显式声明 androidTestImplementation(composeBom)。
    // ui-tooling 提供 @Preview 渲染支持，同样需要显式 BOM。
    debugImplementation(composeBom)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ============ WebUI 构建集成（ticket 07）============
// 构建链：pnpm install → pnpm run build → 复制 webui/dist 到 assets/webui。
// - pnpmInstallWebui 幂等（pnpm install 自身很快）；pnpm 未安装/网络失败会直接报错，不静默
// - buildWebui 以 webui/src 与配置文件为输入、dist 为输出，源码未变时由 Gradle 增量缓存跳过
val webuiDir = rootProject.layout.projectDirectory.dir("webui")
val webuiDistDir = webuiDir.dir("dist")
val webuiAssetsDir = layout.projectDirectory.dir("src/main/assets/webui")

// 用 pnpm 而非 npm：npm 的 trash 清理在部分沙箱环境会被安全删除拦截导致 install 失败；
// pnpm 依赖符号链接布局，安装更稳且默认不跑 audit

// pnpm 可执行文件：优先读 local.properties 的 pnpm=（本机配置，不入库），否则走系统 PATH
val pnpmExe: String = run {
    val localPropsFile = rootProject.file("local.properties")
    val fromLocal = if (localPropsFile.exists()) {
        val props = Properties()
        localPropsFile.inputStream().use { props.load(it) }
        props.getProperty("pnpm")?.trim()
    } else null
    fromLocal ?: if (System.getProperty("os.name").lowercase().contains("windows")) "pnpm.exe" else "pnpm"
}

val pnpmInstallWebui by tasks.registering(Exec::class) {
    group = "webui"
    description = "安装 WebUI 依赖（pnpm，幂等）"
    workingDir = webuiDir.asFile
    commandLine(pnpmExe, "install")
}

// 显式清理 dist（vite 的 emptyOutDir 在部分 Windows 环境会被文件系统安全删除拦截），
// 因此 buildWebui 前先由 Gradle 删除，vite 侧 emptyOutDir=false
val cleanWebuiDist by tasks.registering(Delete::class) {
    group = "webui"
    description = "清理 WebUI 构建产物目录 webui/dist"
    delete(webuiDistDir)
}

val buildWebui by tasks.registering(Exec::class) {
    group = "webui"
    description = "构建 Vue WebUI 到 webui/dist"
    dependsOn(pnpmInstallWebui, cleanWebuiDist)
    workingDir = webuiDir.asFile
    commandLine(pnpmExe, "run", "build")
    inputs.dir(webuiDir.dir("src"))
    inputs.files(
        webuiDir.file("package.json"),
        webuiDir.file("pnpm-lock.yaml"),
        webuiDir.file("pnpm-workspace.yaml"),
        webuiDir.file("vite.config.ts"),
        webuiDir.file("tsconfig.json"),
        webuiDir.file("index.html"),
    )
    outputs.dir(webuiDistDir)
}

val copyWebuiToAssets by tasks.registering(Copy::class) {
    group = "webui"
    description = "复制 WebUI 构建产物到 app 的 assets/webui"
    dependsOn(buildWebui)
    // 复制前清空目标，避免旧 hash 文件名残留
    delete(webuiAssetsDir)
    from(webuiDistDir)
    into(webuiAssetsDir)
}

tasks.named("preBuild") {
    dependsOn(copyWebuiToAssets)
}