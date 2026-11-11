package win.wintertools.videocatcher.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONTokener
import win.wintertools.videocatcher.data.Downloads
import win.wintertools.videocatcher.data.Kind
import java.net.URLEncoder

/**
 * Haelt die WebViews und ihren Zustand. Lebt so lange wie die Activity, nicht
 * wie ein einzelner Bildschirm - deshalb bleibt die Seite beim Wechsel zu den
 * Downloads und zurueck unveraendert stehen.
 *
 * Pop-ups (window.open / neue Tabs) bekommen eine eigene WebView, die ueber
 * die Hauptseite gelegt wird. Schliesst man sie, ist die Hauptseite genau so
 * da wie vorher: nichts wird neu geladen.
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserController(private val activity: Activity) {

    private val prefs = activity.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    var url by mutableStateOf("")
        private set
    var address by mutableStateOf("")
    var title by mutableStateOf("")
        private set
    var progress by mutableIntStateOf(100)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    var onStartPage by mutableStateOf(true)
        private set

    /** Auf der aktuellen Seite gefundene Videos */
    val hits = mutableStateListOf<MediaHit>()

    /** Offene Pop-ups, das oberste ist sichtbar */
    val popups = mutableStateListOf<WebView>()
    var popupHost by mutableStateOf("")
        private set

    var blockPopups by mutableStateOf(prefs.getBoolean("blockPopups", false))
        private set
    var blockedPopups by mutableIntStateOf(0)
        private set

    /** Vollbild-Video */
    var fullscreenView by mutableStateOf<View?>(null)
        private set
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    val main: WebView = createWebView(isMain = true)
    val userAgent: String get() = main.settings.userAgentString

    // ------------------------------------------------------- Schnellzugriff

    class Bookmark(val title: String, val url: String)

    /** Gemerkte Seiten fuer die Startseite */
    val bookmarks = mutableStateListOf<Bookmark>()

    /** Zuletzt gewaehlte Aufloesung - wird beim naechsten Stream vorgewaehlt (0 = beste) */
    var preferredHeight by mutableIntStateOf(prefs.getInt("quality", 0))
        private set

    val isBookmarked: Boolean get() = url.isNotEmpty() && bookmarks.any { it.url == url }

    fun toggleBookmark() {
        if (url.isEmpty()) return
        val existing = bookmarks.firstOrNull { it.url == url }
        if (existing != null) bookmarks.remove(existing)
        else bookmarks.add(0, Bookmark(title.ifBlank { hostOf(url) }.take(60), url))
        saveBookmarks()
    }

    fun removeBookmark(b: Bookmark) {
        bookmarks.remove(b)
        saveBookmarks()
    }

    private fun saveBookmarks() {
        val arr = JSONArray()
        bookmarks.forEach { arr.put(org.json.JSONObject().put("title", it.title).put("url", it.url)) }
        prefs.edit().putString("bookmarks", arr.toString()).apply()
    }

    private fun loadBookmarks() {
        runCatching {
            val arr = JSONArray(prefs.getString("bookmarks", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                bookmarks.add(Bookmark(o.optString("title"), o.getString("url")))
            }
        }
    }

    fun hostOf(u: String): String = Uri.parse(u).host.orEmpty().removePrefix("www.")

    init {
        loadBookmarks()
        goHome()
    }

    // ------------------------------------------------------------ Navigation

    fun load(input: String) {
        val target = normalize(input)
        if (target.isEmpty()) return
        closeAllPopups()
        error = null
        onStartPage = false
        main.loadUrl(target)
    }

    fun goHome() {
        closeAllPopups()
        onStartPage = true
        error = null
        url = ""
        address = ""
        title = ""
        hits.clear()
        main.loadDataWithBaseURL("https://start.videocatcher/", START_HTML, "text/html", "utf-8", null)
    }

    fun back() {
        // Eintrag 0 im Verlauf ist die Startseite der App
        if (main.copyBackForwardList().currentIndex <= 1) goHome()
        else main.goBack()
    }

    fun forward() {
        if (main.canGoForward()) main.goForward()
    }

    fun reload() {
        if (onStartPage) return
        error = null
        main.reload()
    }

    private fun normalize(input: String): String {
        val t = input.trim()
        return when {
            t.isEmpty() -> ""
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            t.contains(' ') || !t.contains('.') -> "https://duckduckgo.com/?q=" + URLEncoder.encode(t, "UTF-8")
            else -> "https://$t"
        }
    }

    // ---------------------------------------------------------------- Pop-ups

    fun updateBlockPopups(block: Boolean) {
        blockPopups = block
        prefs.edit().putBoolean("blockPopups", block).apply()
        if (block) closeAllPopups()
    }

    fun closeTopPopup() {
        val top = popups.lastOrNull() ?: return
        popups.remove(top)
        popupHost = popups.lastOrNull()?.url?.let { hostOf(it) }.orEmpty()
        destroyLater(top)
    }

    fun closeAllPopups() {
        val all = popups.toList()
        popups.clear()
        popupHost = ""
        all.forEach { destroyLater(it) }
    }

    private fun destroyLater(w: WebView) {
        w.stopLoading()
        // Erst zerstoeren, wenn die Ansicht aus der Oberflaeche entfernt ist
        handler.postDelayed({
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }, 400)
    }

    // --------------------------------------------------------------- Vollbild

    fun exitFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        clearFullscreen()
    }

    private fun clearFullscreen() {
        fullscreenView = null
        fullscreenCallback = null
        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
    }

    // ----------------------------------------------------------------- Videos

    fun addHit(hit: MediaHit) {
        val i = hits.indexOfFirst { it.key == hit.key }
        if (i >= 0) {
            // Gleiche Datei, evtl. mit frischem Zugriffs-Parameter: neueste Adresse behalten
            if (hits[i].url != hit.url) hits[i] = hit
        } else if (hits.size < 40) {
            hits.add(hit)
        }
    }

    /** Sucht im Seiteninhalt nach Video-Elementen (ergaenzt das Mitlesen der Anfragen). */
    fun scanPage() {
        val views = listOf(main) + popups
        for (w in views) {
            val page = w.url
            runCatching {
                w.evaluateJavascript(MediaSniffer.DOM_SCAN_JS) { result ->
                    runCatching {
                        val json = JSONTokener(result).nextValue() as? String ?: return@runCatching
                        val arr = JSONArray(json)
                        for (i in 0 until arr.length()) {
                            val headers = if (page != null) mapOf("Referer" to page) else null
                            MediaSniffer.classify(arr.getString(i), headers, knownMedia = true)?.let { addHit(it) }
                        }
                    }
                }
            }
        }
    }

    /** @param quality gewaehlte Stufe eines Streams (null = beste bzw. einzige) */
    fun download(hit: MediaHit, quality: Quality? = null) {
        val target = quality?.url ?: hit.url
        val cookie = CookieManager.getInstance().getCookie(target) ?: CookieManager.getInstance().getCookie(hit.url)
        if (quality != null && quality.height > 0) {
            preferredHeight = quality.height
            prefs.edit().putInt("quality", quality.height).apply()
        }
        Downloads.start(
            activity,
            url = target,
            kind = hit.kind,
            ext = hit.ext,
            title = title.ifBlank { null },
            referer = hit.referer ?: url.ifBlank { null },
            userAgent = userAgent,
            cookie = cookie
        )
    }

    fun cookieFor(u: String): String? = CookieManager.getInstance().getCookie(u)

    // ---------------------------------------------------------------- WebView

    private fun createWebView(isMain: Boolean): WebView = WebView(activity).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.WHITE)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Neue Fenster landen in onCreateWindow statt die Seite zu ersetzen
            setSupportMultipleWindows(true)
            // Ohne Antippen darf eine Seite kein Fenster oeffnen
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = Client(isMain)
        webChromeClient = Chrome(isMain)
        setDownloadListener { downloadUrl, ua, disposition, mime, _ ->
            // Direkter Datei-Link: gleich in die Downloadliste
            if (!downloadUrl.startsWith("http")) return@setDownloadListener
            val name = URLUtil.guessFileName(downloadUrl, disposition, mime)
            val ext = name.substringAfterLast('.', "mp4").ifBlank { "mp4" }.lowercase()
            val isHls = ext == "m3u8"
            Downloads.start(
                activity, downloadUrl,
                kind = if (isHls) Kind.HLS else Kind.FILE,
                ext = if (isHls) "ts" else ext,
                title = name.substringBeforeLast('.'),
                referer = this@BrowserController.url.ifBlank { null },
                userAgent = ua,
                cookie = CookieManager.getInstance().getCookie(downloadUrl)
            )
            Toast.makeText(activity, "Download gestartet: $name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncNav() {
        canGoBack = !onStartPage
        canGoForward = main.canGoForward()
    }

    private inner class Client(private val isMain: Boolean) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Nur Webseiten: Sprunglinks in andere Apps (intent://, market:// ...) bleiben wirkungslos
            val scheme = request.url.scheme?.lowercase()
            return scheme !in setOf("http", "https", "about", "data", "blob", "javascript")
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // Laeuft in einem Hintergrund-Thread: nur einordnen, Zustand im Haupt-Thread aendern
            val hit = MediaSniffer.classify(request.url.toString(), request.requestHeaders)
            if (hit != null) handler.post { addHit(hit) }
            return null
        }

        override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
            if (isMain) {
                val real = pageUrl != null && !pageUrl.startsWith("https://start.videocatcher")
                onStartPage = !real
                url = if (real) pageUrl!! else ""
                address = url
                error = null
                hits.clear()
                syncNav()
            } else if (view == popups.lastOrNull()) {
                popupHost = hostOf(pageUrl.orEmpty())
            }
        }

        override fun onPageFinished(view: WebView, pageUrl: String?) {
            if (isMain) {
                if (onStartPage) view.clearHistory()
                title = if (onStartPage) "" else view.title.orEmpty()
                syncNav()
            } else if (view == popups.lastOrNull()) {
                popupHost = hostOf(pageUrl.orEmpty())
            }
            scanPage()
            handler.postDelayed({ scanPage() }, 3000)
        }

        override fun doUpdateVisitedHistory(view: WebView, pageUrl: String?, isReload: Boolean) {
            if (isMain) {
                syncNav()
                if (pageUrl != null && !pageUrl.startsWith("https://start.videocatcher")) {
                    url = pageUrl
                    address = pageUrl
                }
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
            if (isMain && request.isForMainFrame) {
                error = err.description?.toString() ?: "Unbekannter Fehler"
            }
        }
    }

    private inner class Chrome(private val isMain: Boolean) : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (isMain) progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, t: String?) {
            if (isMain && !onStartPage) title = t.orEmpty()
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (blockPopups || popups.size >= 3) {
                blockedPopups++
                return false
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popup = createWebView(isMain = false)
            popups.add(popup)
            popupHost = ""
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (popups.remove(window)) {
                popupHost = popups.lastOrNull()?.url?.let { hostOf(it) }.orEmpty()
                destroyLater(window)
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreenView != null) {
                callback.onCustomViewHidden()
                return
            }
            fullscreenView = view
            fullscreenCallback = callback
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        override fun onHideCustomView() {
            clearFullscreen()
        }

        // Kein graues Platzhalterbild vor dem Start eines Videos
        override fun getDefaultVideoPoster(): Bitmap =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    fun destroy() {
        closeAllPopups()
        handler.removeCallbacksAndMessages(null)
        (main.parent as? ViewGroup)?.removeView(main)
        main.destroy()
    }

    companion object {
        private const val START_HTML =
            "<!doctype html><html><head><meta charset=\"utf-8\"><style>html,body{background:#0A0B0E;margin:0}</style></head><body></body></html>"
    }
}
