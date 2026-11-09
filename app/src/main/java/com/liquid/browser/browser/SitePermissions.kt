package com.liquid.browser.browser

import android.Manifest
import android.content.Context
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * 網站權限，照 Brave（Chromium）的做法：
 *  - 網頁要權限時先跳對話框問「這個網站可不可以」，同意後才跟 Android 要系統權限
 *  - 「記住我的決定」：直到我關閉這個網站（預設）／24 小時／1 週／永遠（brave-core permission_lifetime）
 *  - 同一網站連續關掉提示 3 次（或忽略 4 次）就自動封鎖 7 天（Chromium PermissionDecisionAutoBlocker）
 *  - 每種權限的預設值：先詢問／封鎖（網站設定），個別網站的例外可在設定裡移除
 */
enum class SiteKind(val key: String, val label: String, val icon: String, val android: List<String>) {
    Location("location", "位置", "location_on", listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    Camera("camera", "相機", "photo_camera", listOf(Manifest.permission.CAMERA)),
    Mic("mic", "麥克風", "mic", listOf(Manifest.permission.RECORD_AUDIO)),
}

/** Brave 的「記住我的決定」選項（順序與預設同 brave-core：第一個「直到我關閉這個網站」） */
enum class PermLifetime(val label: String, val millis: Long?) {
    UntilClose("直到我關閉這個網站", 0),
    Day("24 小時", 24 * 3_600_000L),
    Week("1 週", 7 * 24 * 3_600_000L),
    Forever("永遠", null),
}

/** 一筆網站例外：allow＝允許／封鎖；until＝到期時間（null＝永遠，0＝關閉網站前）；approx＝位置只給大概位置 */
data class SiteException(val origin: String, val kind: SiteKind, val allow: Boolean, val until: Long?, val approx: Boolean = false)

/** 記憶體裡的決定（「直到我關閉這個網站」、無痕分頁） */
private data class Choice(val allow: Boolean, val approx: Boolean)

object SitePermissionStore {
    private const val DISMISSALS_BEFORE_BLOCK = 3
    private const val IGNORES_BEFORE_BLOCK = 4
    private const val EMBARGO_MS = 7 * 24 * 3_600_000L

    private lateinit var prefs: android.content.SharedPreferences
    /** 「直到我關閉這個網站」與無痕分頁的決定只放記憶體 */
    private val session = HashMap<String, Choice>()
    private val incognito = HashMap<String, Choice>()
    /** 網站設定畫面用：資料一變就重組 */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.getSharedPreferences("site_permissions", Context.MODE_PRIVATE)
    }

    private fun k(origin: String, kind: SiteKind) = "$origin|${kind.key}"
    private fun now() = System.currentTimeMillis()

    /** 預設值：true＝先詢問，false＝封鎖 */
    fun askByDefault(kind: SiteKind) = prefs.getBoolean("default|${kind.key}", true)
    fun setAskByDefault(kind: SiteKind, ask: Boolean) { prefs.edit().putBoolean("default|${kind.key}", ask).apply(); version++ }

    /** 這個網站目前的決定：true 允許、false 封鎖、null 要問使用者 */
    fun decision(origin: String, kind: SiteKind, private: Boolean): Boolean? {
        val key = k(origin, kind)
        if (private) incognito[key]?.let { return it.allow }
        session[key]?.let { return it.allow }
        stored(origin, kind)?.let { return it.allow }
        if (embargoed(key)) return false
        return if (askByDefault(kind)) null else false
    }

    private fun stored(origin: String, kind: SiteKind): SiteException? {
        val raw = prefs.getString("site|${k(origin, kind)}", null) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val until = if (o.has("until")) o.getLong("until") else null
        if (until != null && until < now()) { prefs.edit().remove("site|${k(origin, kind)}").apply(); return null }
        return SiteException(origin, kind, o.getBoolean("allow"), until, o.optBoolean("approx"))
    }

    /** 這個網站的位置是否只給大概位置（允許時才有意義） */
    fun approx(origin: String, private: Boolean): Boolean {
        val key = k(origin, SiteKind.Location)
        if (private) incognito[key]?.let { return it.approx }
        session[key]?.let { return it.approx }
        return stored(origin, SiteKind.Location)?.approx == true
    }

    /** 允許了但只給大概位置的網站（開頁腳本用來模糊座標） */
    fun approxOrigins(private: Boolean): Set<String> {
        if (!::prefs.isInitialized) return emptySet()
        val mem = (if (private) incognito else session)
            .filter { it.key.endsWith("|${SiteKind.Location.key}") && it.value.allow && it.value.approx }
            .keys.map { it.substringBeforeLast('|') }.toSet()
        if (private) return mem
        return mem + exceptions().filter { it.kind == SiteKind.Location && it.allow && it.approx }.map { it.origin }
    }

    fun set(origin: String, kind: SiteKind, allow: Boolean, lifetime: PermLifetime, private: Boolean, approx: Boolean = false) {
        val key = k(origin, kind)
        val ap = allow && approx && kind == SiteKind.Location
        prefs.edit().remove("embargo|$key").apply()
        when {
            private -> incognito[key] = Choice(allow, ap)
            lifetime == PermLifetime.UntilClose -> { session[key] = Choice(allow, ap); prefs.edit().remove("site|$key").apply() }
            else -> {
                session.remove(key)
                val o = JSONObject().put("allow", allow)
                if (ap) o.put("approx", true)
                lifetime.millis?.let { o.put("until", now() + it) }
                prefs.edit().putString("site|$key", o.toString()).apply()
            }
        }
        version++
    }

    /** 移除這個網站的決定（回到預設：先詢問） */
    fun reset(origin: String, kind: SiteKind) {
        val key = k(origin, kind)
        session.remove(key); incognito.remove(key)
        prefs.edit().remove("site|$key").remove("embargo|$key").apply()
        version++
    }

    /** 使用者沒選就關掉對話框（dismiss）或網頁自己取消（ignore）：累計到門檻就封鎖 7 天 */
    fun noAnswer(origin: String, kind: SiteKind, dismissed: Boolean) {
        val key = "embargo|${k(origin, kind)}"
        val o = runCatching { JSONObject(prefs.getString(key, "{}")!!) }.getOrDefault(JSONObject())
        val field = if (dismissed) "dismiss" else "ignore"
        val n = o.optInt(field) + 1
        o.put(field, n)
        if (n >= if (dismissed) DISMISSALS_BEFORE_BLOCK else IGNORES_BEFORE_BLOCK) o.put("until", now() + EMBARGO_MS).put(field, 0)
        prefs.edit().putString(key, o.toString()).apply()
        version++
    }

    private fun embargoed(key: String): Boolean {
        val o = runCatching { JSONObject(prefs.getString("embargo|$key", null) ?: return false) }.getOrNull() ?: return false
        return o.optLong("until") > now()
    }

    /** 「直到我關閉這個網站」：沒有分頁開著這個網站時就忘掉 */
    fun pruneSession(openOrigins: Set<String>, openIncognito: Set<String>) {
        val a = session.keys.removeAll { it.substringBefore('|') !in openOrigins }
        val b = incognito.keys.removeAll { it.substringBefore('|') !in openIncognito }
        if (a || b) version++
    }

    /** 網站設定清單：已存的例外（含「關閉網站前」與自動封鎖中的） */
    fun exceptions(): List<SiteException> {
        val list = ArrayList<SiteException>()
        session.forEach { (key, c) -> parse(key)?.let { (o, kind) -> list += SiteException(o, kind, c.allow, 0, c.approx) } }
        prefs.all.keys.filter { it.startsWith("site|") }.forEach { key ->
            parse(key.removePrefix("site|"))?.let { (o, kind) -> stored(o, kind)?.let(list::add) }
        }
        prefs.all.keys.filter { it.startsWith("embargo|") }.forEach { key ->
            val bare = key.removePrefix("embargo|")
            val until = runCatching { JSONObject(prefs.getString(key, "{}")!!).optLong("until") }.getOrDefault(0)
            if (until > now()) parse(bare)?.let { (o, kind) -> if (list.none { it.origin == o && it.kind == kind }) list += SiteException(o, kind, false, until) }
        }
        return list.sortedWith(compareBy({ it.kind.ordinal }, { it.origin }))
    }

    private fun parse(key: String): Pair<String, SiteKind>? {
        val kind = SiteKind.entries.firstOrNull { it.key == key.substringAfterLast('|') } ?: return null
        return key.substringBeforeLast('|') to kind
    }

    fun originOf(url: String): String? = Uri.parse(url).let { u ->
        if (u.scheme != "http" && u.scheme != "https") null else "${u.scheme}://${u.authority}"
    }
}

/**
 * 「大概位置」：Android WebView 沒辦法只給某個網站大概位置，所以在開頁時包住 navigator.geolocation，
 * 把座標對齊到約 2 公里的格子、精確度至少 2000 公尺（跟 Android 大概位置的做法相同），不給高度、方向、速度。
 * origins＝已設定大概位置的網站；剛在對話框選大概位置的頁面用 window.__lqGeoApprox=true 打開（只能打開不能關掉）。
 */
fun geoApproxScript(origins: Set<String>): String {
    val list = origins.joinToString(",", "[", "]") { JSONObject.quote(it) }
    return """(function(){var G=window.Geolocation,g=navigator.geolocation;if(!G||!g||G.prototype.__lq)return;
var on=$list.indexOf(location.origin)>=0;
try{Object.defineProperty(window,'__lqGeoApprox',{set:function(v){if(v)on=true},get:function(){return on},configurable:false});}catch(e){}
function fuzz(p){if(!on||!p||!p.coords)return p;var c=p.coords,S=0.02,la=Math.round(c.latitude/S)*S,
k=S/Math.max(Math.cos(la*Math.PI/180),0.01),lo=Math.round(c.longitude/k)*k;if(lo>180)lo-=360;if(lo<-180)lo+=360;
var o={latitude:la,longitude:lo,accuracy:Math.max(c.accuracy||0,2000),altitude:null,altitudeAccuracy:null,heading:null,speed:null};
o.toJSON=function(){return{latitude:o.latitude,longitude:o.longitude,accuracy:o.accuracy,altitude:null,altitudeAccuracy:null,heading:null,speed:null}};
if(window.GeolocationCoordinates)Object.setPrototypeOf(o,GeolocationCoordinates.prototype);
var r={coords:o,timestamp:p.timestamp};r.toJSON=function(){return{coords:o.toJSON(),timestamp:r.timestamp}};
if(window.GeolocationPosition)Object.setPrototypeOf(r,GeolocationPosition.prototype);return r}
function w(f){return typeof f=='function'?function(p){return f(fuzz(p))}:f}
var P=G.prototype,gc=P.getCurrentPosition,wp=P.watchPosition;
function def(n,f){Object.defineProperty(P,n,{value:f,writable:false,configurable:false})}
def('getCurrentPosition',function(s,e,o){return gc.call(this,w(s),e,o)});
def('watchPosition',function(s,e,o){return wp.call(this,w(s),e,o)});
def('__lq',true)})();"""
}

/** 網站要求權限（一次一個，其他排隊；同 Chromium PermissionDialogController） */
class SitePermissionAsk(val tab: Tab, val origin: String, val kinds: Set<SiteKind>, val result: (Boolean) -> Unit) {
    val host: String get() = Uri.parse(origin).host?.removePrefix("www.") ?: origin
}

/** 要向 Android 要的系統權限；onResult 收到每個權限是否同意 */
class RuntimeAsk(val permissions: Array<String>, val onResult: (Map<String, Boolean>) -> Unit)

/** 上傳檔案的來源（Safari 的選單：照片圖庫／拍照／錄影／選擇檔案） */
enum class FileSource { Library, Photo, Video, Files }

/** 網頁的 <input type=file>：source 還沒選時顯示來源選單；mic＝檔案選擇器裡要不要放錄音（有麥克風權限才放） */
class FileChooserAsk(
    val callback: ValueCallback<Array<Uri>>,
    val params: WebChromeClient.FileChooserParams,
) {
    var source by mutableStateOf<FileSource?>(null)
    var mic = false
    /** 拍照／錄影的輸出檔（相機 App 寫入，回傳時 data 是空的，要從這裡拿） */
    var photo: Uri? = null
    var video: Uri? = null

    /** accept 屬性拆成 MIME（.jpg 這類副檔名換成 MIME）；空的＝任何檔案 */
    val types: List<String> get() = params.acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        .map { if (it.startsWith(".")) android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.drop(1)) ?: "*/*" else it }
        .distinct()

    private fun accepts(prefix: String) = types.isEmpty() || types.any { it == "*/*" || it.startsWith("$prefix/") }
    fun only(prefix: String) = types.isNotEmpty() && types.all { it.startsWith("$prefix/") }
    val wantsImage get() = accepts("image")
    val wantsVideo get() = accepts("video")
    val wantsAudio get() = accepts("audio")
    val multiple get() = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
}
