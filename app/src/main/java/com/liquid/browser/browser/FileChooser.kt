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
 * 網頁上傳檔案（<input type=file>）：同 Brave／Chrome，選單裡有「相機」「錄影」和檔案／相簿。
 * 有 capture 屬性（例如只收圖片又加了 capture 的 input）直接開相機。
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

    fun intent(context: Context, ask: FileChooserAsk): Intent {
        val cams = buildList {
            if (ask.camera && ask.wantsImage) add(cameraIntent(MediaStore.ACTION_IMAGE_CAPTURE, captureFile(context, "jpg").also { ask.photo = it }))
            if (ask.camera && ask.wantsVideo) add(cameraIntent(MediaStore.ACTION_VIDEO_CAPTURE, captureFile(context, "mp4").also { ask.video = it }))
        }
        // capture：直接開相機（只要照片就拍照、只要影片就錄影）
        if (ask.params.isCaptureEnabled && cams.isNotEmpty()) {
            return if (ask.wantsImage || cams.size == 1) cams.first() else cams.last()
        }
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            val types = ask.params.acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                .map { if (it.startsWith(".")) android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.drop(1)) ?: "*/*" else it }
                .distinct()
            type = types.singleOrNull() ?: "*/*"
            if (types.size > 1 && "*/*" !in types) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            if (ask.params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return Intent.createChooser(pick, t("選擇檔案")).apply {
            if (cams.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, cams.toTypedArray())
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
