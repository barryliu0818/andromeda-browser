plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.liquid.browser.baselineprofile"
    compileSdk { version = release(37) }
    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 允許在模擬器上執行（數據僅供參考，正式量測請用真機）
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }
    targetProjectPath = ":app"
}

baselineProfile { useConnectedDevices = true }

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.test.runner)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro)
}
