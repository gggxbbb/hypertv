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
        versionCode = 1
        versionName = "1.0"

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
    implementation(platform(libs.androidx.compose.bom))
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
    // Media3 播放器（ExoPlayer 核心 + PlayerView 控件，ticket 04）
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ============ WebUI 构建集成（ticket 07）============
// 构建链：npm install → npm run build → 复制 webui/dist 到 assets/webui。
// - npmInstallWebui 幂等（npm install 自身很快）；npm 未安装/网络失败会直接报错，不静默
// - buildWebui 以 webui/src 与配置文件为输入、dist 为输出，源码未变时由 Gradle 增量缓存跳过
val webuiDir = rootProject.layout.projectDirectory.dir("webui")
val webuiDistDir = webuiDir.dir("dist")
val webuiAssetsDir = layout.projectDirectory.dir("src/main/assets/webui")

val npmCommand = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val npmInstallWebui by tasks.registering(Exec::class) {
    group = "webui"
    description = "安装 WebUI npm 依赖（幂等）"
    workingDir = webuiDir.asFile
    commandLine(npmCommand, "install", "--no-audit", "--no-fund")
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
    dependsOn(npmInstallWebui, cleanWebuiDist)
    workingDir = webuiDir.asFile
    commandLine(npmCommand, "run", "build")
    inputs.dir(webuiDir.dir("src"))
    inputs.files(
        webuiDir.file("package.json"),
        webuiDir.file("package-lock.json"),
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