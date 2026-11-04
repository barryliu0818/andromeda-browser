import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
}

// 金鑰只從 local.properties 讀（不進 git）：QWEN_API_KEY=…；簽章另見 keystore.properties
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.liquid.browser"
    compileSdk { version = release(37) }

    defaultConfig {
        // 原本的簽章金鑰遺失，無法覆蓋手機上的舊版（com.liquid.browser）：新版用另一個套件名稱，兩版並存
        applicationId = "com.liquid.browser.next"
        manifestPlaceholders["appLabel"] = "Orea"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "0.4"
        // 帳號服務（Supabase）：網址與公開金鑰（anon key，本來就是給 App 用的）也從 local.properties 讀；兩版都要能登入
        buildConfigField("String", "SUPABASE_URL", "\"${providers.gradleProperty("SUPABASE_URL").orNull ?: localProps.getProperty("SUPABASE_URL", "")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${providers.gradleProperty("SUPABASE_ANON_KEY").orNull ?: localProps.getProperty("SUPABASE_ANON_KEY", "")}\"")
    }

    signingConfigs {
        // 原本的金鑰已遺失：keystore.properties（storeFile、storePassword、keyAlias、keyPassword）存在時用新金鑰，否則用 debug 金鑰
        if (keystoreProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    // 兩版：自用版編入 local.properties 的翻譯金鑰；分享版不含任何金鑰
    flavorDimensions += "key"
    productFlavors {
        create("personal") {
            dimension = "key"
            buildConfigField("String", "QWEN_API_KEY", "\"${localProps.getProperty("QWEN_API_KEY", "")}\"")
        }
        create("share") {
            dimension = "key"
            buildConfigField("String", "QWEN_API_KEY", "\"\"")
        }
    }

    // 內建的 Orea Chromium 核心（約 180MB，不進 git：GitHub 單檔上限 100MB）。local.properties 的 OREA_CORE_DIR 指到
    // 含 orea_core/core.bin（核心 APK）與 orea_core/version.txt 的資料夾就打包進 App；沒設定則照常編譯、用系統 WebView（見 Engine）
    (providers.gradleProperty("OREA_CORE_DIR").orNull ?: localProps.getProperty("OREA_CORE_DIR"))?.let { dir ->
        if (file(dir).resolve("orea_core/core.bin").isFile) sourceSets.getByName("main").assets.srcDir(dir)
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.util)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.savedstate.compose)
    implementation(libs.androidx.webkit)
    testImplementation(kotlin("test-junit"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.glass)

    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.metrics.performance)
    baselineProfile(project(":baselineprofile"))
}

baselineProfile {
    // 產生的 Baseline Profile 放進 main：personal、share 兩版共用（重新產生：./gradlew :app:generatePersonalReleaseBaselineProfile）
    mergeIntoMain = true
}
