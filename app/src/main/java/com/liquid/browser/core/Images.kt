package com.liquid.browser.core

import android.content.Context
import android.webkit.WebSettings
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient

/**
 * Coil 全域設定：用 WebView 的 User-Agent 抓圖（部分網站如 Wikimedia 會拒絕 OkHttp 預設 UA）。
 * 解碼在 Coil 的背景執行緒進行，不佔用動畫幀。
 */
fun installImageLoader(context: Context) {
    val ua = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("Mozilla/5.0 (Linux; Android) Mobile")
    SingletonImageLoader.setSafe { ctx ->
        ImageLoader.Builder(ctx)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", ua).build()) }
                                .build()
                        },
                    ),
                )
            }
            .build()
    }
}
