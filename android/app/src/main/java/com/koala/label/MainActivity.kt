package com.koala.label

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var printer: Printer
    private var pageReady = false
    private val pending = mutableListOf<JSONObject>()
    private var update: UpdateInfo? = null
    private var lastCheck = 0L
    private var downloading = false

    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCb?.onReceiveValue(uris.toTypedArray()); fileCb = null
    }

    private var afterPerms: ((Boolean) -> Unit)? = null
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        afterPerms?.invoke(res.values.all { it }); afterPerms = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        printer = Printer(applicationContext)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.setBackgroundColor(0xFF00071A.toInt())
        web.setBackgroundColor(0xFF00071A.toInt())
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
        }
        web.addJavascriptInterface(Bridge(), "KoaNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(req.url)
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (req.url.host == "appassets.androidplatform.net") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                deliverPending()
                update?.let { js("window.__koaUpdate&&__koaUpdate(${JSONObject.quote(it.version)})") }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try { pickPdf.launch(arrayOf("application/pdf")); true } catch (e: Exception) { fileCb = null; false }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__koaBack?__koaBack():false") { r -> if (r != "true") finish() }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        checkUpdate(false)
    }

    override fun onStop() {
        super.onStop()
        printer.releaseNow()   // l'imprimante redevient visible pour les autres appareils
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }
    private fun toast(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }

    /* ---------- PDF reçus (Partager / Ouvrir avec) ---------- */
    private fun handleIntent(i: Intent?) {
        i ?: return
        val uris = mutableListOf<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val u = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        else i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                if (u != null) uris += u
            }
            Intent.ACTION_VIEW -> i.data?.let { uris += it }
        }
        if (uris.isEmpty()) return
        i.action = Intent.ACTION_MAIN   // ne pas réimporter au retour dans l'appli
        thread {
            for (u in uris) runCatching {
                val bytes = contentResolver.openInputStream(u)!!.use { it.readBytes() }
                var name = "bordereau.pdf"
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
                }
                synchronized(pending) { pending += JSONObject().put("name", name).put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP)) }
            }.onFailure { toast("Impossible de lire ce fichier") }
            runOnUiThread { deliverPending() }
        }
    }

    private fun deliverPending() {
        if (!pageReady) return
        if (synchronized(pending) { pending.isNotEmpty() }) web.evaluateJavascript("window.__koaPending&&__koaPending()", null)
    }

    /* ---------- Bluetooth ---------- */
    private fun withBluetooth(go: (Boolean) -> Unit) {
        val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) { go(true); return }
        afterPerms = go
        askPerms.launch(perms)
    }

    /* ---------- Mises à jour ---------- */
    private fun checkUpdate(manual: Boolean) {
        val now = System.currentTimeMillis()
        if (!manual && now - lastCheck < 10 * 60 * 1000) return
        lastCheck = now
        thread {
            val r = runCatching { Updater.check() }
            r.onSuccess { info ->
                if (info != null) {
                    update = info
                    if (pageReady) js("window.__koaUpdate&&__koaUpdate(${JSONObject.quote(info.version)})")
                } else if (manual) toast("KoaLabel est à jour (${BuildConfig.VERSION_NAME})")
            }.onFailure { if (manual) toast("Vérification impossible, vérifie ta connexion") }
        }
    }

    private fun installUpdate() {
        val info = update ?: run { checkUpdate(true); return }
        if (downloading) return
        if (!Updater.canInstall(this)) {
            toast("Autorise KoaLabel à installer des applis, puis reviens appuyer sur Installer")
            Updater.askInstallPermission(this)
            return
        }
        downloading = true
        js("window.__koaUpdProgress&&__koaUpdProgress(0)")
        thread {
            val f = runCatching { Updater.download(this, info.url) { p -> js("window.__koaUpdProgress&&__koaUpdProgress($p)") } }.getOrNull()
            downloading = false
            js("window.__koaUpdProgress&&__koaUpdProgress(-1)")
            if (f == null) toast("Téléchargement impossible, vérifie ta connexion")
            else runCatching { Updater.install(this, f) }.onFailure { toast("Installation impossible") }
        }
    }

    /* ---------- Fichiers ---------- */
    private fun shareFile(bytes: ByteArray, name: String) {
        val dir = File(cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        f.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, null))
    }

    private fun saveFile(bytes: ByteArray, name: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                File(dir, name).writeBytes(bytes)
            }
            toast("Enregistré dans Téléchargements")
        } catch (e: Exception) {
            toast("Enregistrement impossible")
        }
    }

    /* ---------- Pont appelé par la page ---------- */
    inner class Bridge {
        @JavascriptInterface
        fun print(id: Int, b64: String) {
            val job = Base64.decode(b64, Base64.DEFAULT)
            runOnUiThread {
                withBluetooth { ok ->
                    if (!ok) { js("__koaPrintDone($id, ${JSONObject.quote("autorisation Bluetooth refusée")})"); return@withBluetooth }
                    printer.print(job) { err ->
                        js("__koaPrintDone($id, ${if (err == null) "null" else JSONObject.quote(err)})")
                    }
                }
            }
        }

        @JavascriptInterface
        fun share(b64: String, name: String) {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            runOnUiThread { runCatching { shareFile(bytes, name) }.onFailure { toast("Partage impossible") } }
        }

        @JavascriptInterface
        fun save(b64: String, name: String) {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            if (Build.VERSION.SDK_INT < 29 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread {
                    afterPerms = { ok -> if (ok) thread { saveFile(bytes, name) } else toast("Autorisation refusée") }
                    askPerms.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                }
                return
            }
            thread { saveFile(bytes, name) }
        }

        @JavascriptInterface
        fun takePending(): String {
            val list = synchronized(pending) { val l = pending.toList(); pending.clear(); l }
            return if (list.isEmpty()) "" else JSONArray(list).toString()
        }

        @JavascriptInterface
        fun checkUpdate() = this@MainActivity.checkUpdate(true)

        @JavascriptInterface
        fun installUpdate() = runOnUiThread { this@MainActivity.installUpdate() }

        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME
    }
}
