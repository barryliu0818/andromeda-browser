package com.liquid.browser.core

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PerformanceHintManager
import android.view.FrameMetrics
import android.view.Window
import java.io.File

/**
 * 智適應刷新率（仿小米「智能刷新率」）：
 *  - 觸控、捲動、動畫時把視窗切到最高刷新率模式（120Hz）；
 *  - 停止操作 [IdleMs] 後交還給系統（preferredDisplayModeId = 0），HyperOS／LTPO 會把靜止畫面降到低刷新率省電；
 *    播放影片時 Media3 依片源幀率設定 Surface 幀率（24／30／60），系統就能用對應的刷新率，不會一直跑 120Hz。
 * 小米 15 Ultra 沒有 Android 原生 ARR（hasArrSupport=false），「高」幀率類別只對應 90Hz，所以用明確的顯示模式切換。
 * 使用者在系統設定把刷新率限制在 60Hz 時，系統的上限優先，這裡不會超過。
 */
class AdaptiveRefresh(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private val idle = Runnable { apply(false) }

    /** 與目前解析度相同、刷新率最高的模式 */
    private val highMode: Int by lazy {
        val d = activity.display ?: return@lazy 0
        val cur = d.mode
        d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }?.modeId ?: 0
    }

    /** 有操作或畫面在動：切到高刷新率，並重新計時 */
    fun poke() {
        if (!active) apply(true)
        handler.removeCallbacks(idle)
        handler.postDelayed(idle, IdleMs)
    }

    /** 離開 App：立即交還給系統 */
    fun release() {
        handler.removeCallbacks(idle)
        if (active) apply(false)
    }

    private fun apply(on: Boolean) {
        active = on
        val lp = activity.window.attributes
        val mode = if (on) highMode else 0
        if (lp.preferredDisplayModeId != mode) activity.window.attributes = lp.apply { preferredDisplayModeId = mode }
        AdpfHint.powerEfficient(!on)
    }

    companion object {
        const val IdleMs = 1500L
    }
}

/**
 * ADPF 效能提示（Android 12+）：把主執行緒與 RenderThread 每一幀實際花的時間回報給系統排程器，
 * 驍龍 8 Elite＋HyperOS 據此即時調整 CPU 頻率與大小核配置：要趕幀時拉高、閒置時降頻省電，
 * 比系統只靠負載猜測更準（少掉幀也少發熱）。
 */
object AdpfHint {
    private var session: PerformanceHintManager.Session? = null
    private var thread: HandlerThread? = null

    fun start(activity: Activity, refreshRate: Float) {
        if (Build.VERSION.SDK_INT < 31 || session != null) return
        val phm = activity.getSystemService(PerformanceHintManager::class.java) ?: return
        val tids = listOfNotNull(android.os.Process.myTid(), renderThreadTid()).toIntArray()
        val target = (1_000_000_000L / refreshRate).toLong()
        session = runCatching { phm.createHintSession(tids, target) }.getOrNull() ?: return
        val t = HandlerThread("adpf").apply { start() }
        thread = t
        activity.window.addOnFrameMetricsAvailableListener(listener, Handler(t.looper))
    }

    private val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
        // 實際工作時間＝整幀時間扣掉等待 vsync 前的延遲
        val work = m.getMetric(FrameMetrics.TOTAL_DURATION) - m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)
        if (work > 0) runCatching { session?.reportActualWorkDuration(work) }
    }

    /** 閒置時告訴系統偏好省電（Android 15+） */
    fun powerEfficient(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 35) runCatching { session?.setPreferPowerEfficiency(on) }
    }

    fun stop(activity: Activity) {
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
        session?.close(); session = null
        thread?.quitSafely(); thread = null
    }

    /** RenderThread 的執行緒 id（/proc/self/task/<tid>/comm） */
    private fun renderThreadTid(): Int? = File("/proc/self/task").listFiles()?.firstOrNull {
        runCatching { File(it, "comm").readText().trim() == "RenderThread" }.getOrDefault(false)
    }?.name?.toIntOrNull()
}
