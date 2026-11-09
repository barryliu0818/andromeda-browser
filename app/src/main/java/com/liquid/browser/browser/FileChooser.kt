package com.liquid.browser.browser

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.WebChromeClient
import androidx.core.content.FileProvider
import com.liquid.browser.core.t
import java.io.File

/**
 * 網頁上傳檔案（<input type=file>），照 Chromium 的 SelectFileDialog：
 *  - 有 capture 且只收圖片＝直接開相機；只收影片＝直接錄影；只收音訊＝直接錄音
 *  - 其他情況開系統檔案選擇器，前面加上「相機」「錄影」「錄音」（依 accept 類型，且有權限才加）
 */
object FileChooser {
    private fun captureFile(context: Context, ext: String): Uri {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        // 舊的拍照暫存清掉（只留最近一小時的，網頁可能還在讀）
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        val f = File(dir, "${System.currentTimeMillis()}.$ext")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    private fun cameraIntent(action: String, out: Uri) = Intent(action).apply {
        putExtra(MediaStore.EXTRA_OUTPUT, out)
        clipData = ClipData.newRawUri("", out)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun photo(context: Context, ask: FileChooserAsk) =
        cameraIntent(MediaStore.ACTION_IMAGE_CAPTURE, captureFile(context, "jpg").also { ask.photo = it })
    private fun video(context: Context, ask: FileChooserAsk) =
        cameraIntent(MediaStore.ACTION_VIDEO_CAPTURE, captureFile(context, "mp4").also { ask.video = it })
    private fun sound() = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)

    fun intent(context: Context, ask: FileChooserAsk): Intent {
        // capture：直接開相機／錄影機／錄音機
        when {
            ask.captureImage && ask.camera -> return photo(context, ask)
            ask.captureVideo && ask.camera -> return video(context, ask)
            ask.captureAudio && ask.mic -> return sound()
        }
        val types = ask.types
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (types.isNotEmpty() && "*/*" !in types) {
                // Chromium：只收圖片／影片時加一個不存在的類型，避免系統直接換成相簿選擇器而沒有「檔案」可選
                val mimes = if (types.any { it.startsWith("image/") || it.startsWith("video/") }) types + "type/nonexistent" else types
                putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            }
            if (ask.params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val extra = buildList {
            if (ask.camera && ask.wantsImage) add(photo(context, ask))
            if (ask.camera && ask.wantsVideo) add(video(context, ask))
            if (ask.mic && ask.wantsAudio) add(sound())
        }
        return Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, pick)
            putExtra(Intent.EXTRA_TITLE, t("選擇檔案"))
            if (extra.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, extra.toTypedArray())
        }
    }

    private fun nonEmpty(context: Context, uri: Uri?): Uri? = uri?.takeIf {
        runCatching { context.contentResolver.openAssetFileDescriptor(it, "r")?.use { fd -> fd.length > 0 } }.getOrNull() == true
    }

    fun result(context: Context, ask: FileChooserAsk, code: Int, data: Intent?): Array<Uri>? {
        if (code != Activity.RESULT_OK) return null
        val clip = data?.clipData
        val picked = buildList {
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { add(it) }
            data?.data?.let { if (it !in this) add(it) }
        }.filter { it != ask.photo && it != ask.video }
        if (picked.isNotEmpty()) return picked.toTypedArray()
        // 相機 App 回傳時 data 通常是空的：看哪個暫存檔有寫入
        return (nonEmpty(context, ask.photo) ?: nonEmpty(context, ask.video))?.let { arrayOf(it) }
    }
}
