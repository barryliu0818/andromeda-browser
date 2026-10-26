plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}

// 專案位於 iCloud 同步的「文件」資料夾：建置輸出改放到不同步的 ~/Desktop/AndroidDev.nosync/build，
// 避免 iCloud 上傳大量中間檔並產生「檔名 2」衝突副本導致建置失敗。
allprojects {
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/Desktop/AndroidDev.nosync/build/${rootProject.name}/${project.name}"))
}
