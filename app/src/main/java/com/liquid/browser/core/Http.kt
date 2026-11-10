package com.liquid.browser.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * App 自己發的網路請求共用的 OkHttp（翻譯、圖片）：連線池共用、HTTP/2 多工
 * （同時 8 個 AI 翻譯請求走同一條連線，不必各握手一次），而且請求可以真的取消。
 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 在 IO 執行緒送出請求並交給 block 讀回應；呼叫的協程被取消（離開網頁、關掉分頁）時，
     * 連線馬上中斷——連串流讀到一半也會停（HttpURLConnection 的阻塞讀取不理會協程取消，請求會跑完、照算 token）
     */
    suspend fun <T> exec(request: Request, client: OkHttpClient = this.client, block: (Response) -> T): T = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        val cancel = coroutineContext.job.invokeOnCompletion { call.cancel() }
        try { call.execute().use(block) } finally { cancel.dispose() }
    }
}
