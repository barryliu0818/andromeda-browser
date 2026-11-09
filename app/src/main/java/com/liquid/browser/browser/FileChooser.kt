package com.liquid.browser.browser

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.liquid.browser.core.t
import java.io.File

/**
 * 網頁上傳檔案（<input type=file>）：收圖片／影片時先出 Safari 式來源選單（照片圖庫／拍照／錄影／選擇檔案），
 * 有 capture 也一樣讓使用者選（不強制開相機）；其他類型直接開系統檔案選擇器（收音訊時加「錄音」）。
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

    /** 照片圖庫：Android 13 起用系統相片挑選器，舊版用相簿／檔案選擇器 */
    private fun library(ask: FileChooserAsk): Intent {
        val type = when {
            ask.only("image") -> "image/*"
            ask.only("video") -> "video/*"
            else -> null
        }
        if (Build.VERSION.SDK_INT >= 33) return Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            type?.let { setType(it) }
            if (ask.multiple) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
        }
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType(type ?: "image/*")
            if (type == null) putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            if (ask.multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    /** 選擇檔案：系統檔案選擇器（收音訊且有麥克風權限時前面加「錄音」） */
    private fun files(ask: FileChooserAsk): Intent {
        val types = ask.types
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (types.isNotEmpty() && "*/*" !in types) {
                // Chromium：只收圖片／影片時加一個不存在的類型，避免系統直接換成相片挑選器而看不到檔案
                val mimes = if (types.any { it.startsWith("image/") || it.startsWith("video/") }) types + "type/nonexistent" else types
                putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            }
            if (ask.multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, pick)
            putExtra(Intent.EXTRA_TITLE, t("選擇檔案"))
            if (ask.mic && ask.wantsAudio) putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)))
        }
    }

    fun intent(context: Context, ask: FileChooserAsk): Intent = when (ask.source) {
        FileSource.Library -> library(ask)
        FileSource.Photo -> cameraIntent(MediaStore.ACTION_IMAGE_CAPTURE, captureFile(context, "jpg").also { ask.photo = it })
        FileSource.Video -> cameraIntent(MediaStore.ACTION_VIDEO_CAPTURE, captureFile(context, "mp4").also { ask.video = it })
        FileSource.Files, null -> files(ask)
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
