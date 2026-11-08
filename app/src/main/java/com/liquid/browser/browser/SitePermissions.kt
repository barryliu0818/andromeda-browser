package com.liquid.browser.browser

import android.Manifest
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/** 網頁可以要求的裝置功能（Brave 式：先問「這個網站可不可以」，同意後再要 Android 的系統權限） */
enum class SiteKind(val label: String, val icon: String, val android: List<String>) {
    Location("位置", "location_on", listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    Camera("相機", "photo_camera", listOf(Manifest.permission.CAMERA)),
    Mic("麥克風", "mic", listOf(Manifest.permission.RECORD_AUDIO)),
}

/** 網站要求權限：result(true)＝允許（呼叫端負責回覆網頁） */
class SitePermissionAsk(val tab: Tab, val origin: String, val kinds: Set<SiteKind>, val result: (Boolean) -> Unit) {
    val host: String get() = Uri.parse(origin).host?.removePrefix("www.") ?: origin
}

/** 要向 Android 要的系統權限；onResult 收到每個權限是否同意 */
class RuntimeAsk(val permissions: Array<String>, val onResult: (Map<String, Boolean>) -> Unit)

/** 網頁的 <input type=file>：camera＝選單裡要不要放拍照／錄影（有相機權限才放） */
class FileChooserAsk(
    val callback: ValueCallback<Array<Uri>>,
    val params: WebChromeClient.FileChooserParams,
    val camera: Boolean,
) {
    /** 拍照／錄影的輸出檔（相機 App 寫入，回傳時 data 是空的，要從這裡拿） */
    var photo: Uri? = null
    var video: Uri? = null

    private val accept get() = params.acceptTypes.filter { it.isNotBlank() }.joinToString(",").lowercase()
    val wantsImage get() = accept.isEmpty() || "image" in accept || Regex("""\.(jpe?g|png|heic|webp|gif)""").containsMatchIn(accept)
    val wantsVideo get() = accept.isEmpty() || "video" in accept || Regex("""\.(mp4|mov|webm|3gp)""").containsMatchIn(accept)
}
