package pl.geoeco.serwis

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import java.io.File

/** Mostek JS ↔ aplikacja: formularz (HTML) oddaje gotowy PDF do zapisu albo wysyłki. */
private class ZlecenieBridge(private val ctx: Context) {
    private val ui = Handler(Looper.getMainLooper())

    private fun toast(t: String) = ui.post { Toast.makeText(ctx, t, Toast.LENGTH_LONG).show() }

    @JavascriptInterface
    fun deliver(b64: String, name: String, mode: String, subject: String, body: String, to: String) {
        try {
            val safe = name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").ifBlank { "zlecenie.pdf" }
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            if (mode == "share") share(safe, bytes, subject, body, to) else toast("Zapisano: " + save(safe, bytes))
        } catch (e: Exception) {
            toast("Nie udało się: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun share(name: String, bytes: ByteArray, subject: String, body: String, to: String) {
        val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
        val f = File(dir, name)
        f.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Wyślij zlecenie").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** PDF do „Pobrane/Geo-Eco-Serwis” (Android 10+) albo do katalogu aplikacji na starszych. */
    private fun save(name: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Geo-Eco-Serwis")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw java.io.IOException("Brak dostępu do pamięci.")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            return "Pobrane/Geo-Eco-Serwis/$name"
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Geo-Eco-Serwis").apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
        return "${dir.path}/$name"
    }
}

/** Zlecenie montażowe dla pompy ciepła: formularz HTML z eksportem do PDF (generowanie PDF wymaga internetu). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ZlecenieScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val chooser = remember { arrayOfNulls<ValueCallback<Array<Uri>>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        chooser[0]?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        chooser[0] = null
    }
    val loader = remember {
        WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx)).build()
    }
    AppScaffold("Zlecenie montażowe PC", onBack) { pad ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    addJavascriptInterface(ZlecenieBridge(ctx), "Android")
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            loader.shouldInterceptRequest(request.url)
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
                        ): Boolean {
                            chooser[0]?.onReceiveValue(null)
                            chooser[0] = cb
                            return try {
                                launcher.launch(params.createIntent())
                                true
                            } catch (e: Exception) {
                                chooser[0] = null
                                false
                            }
                        }
                    }
                    loadUrl("https://appassets.androidplatform.net/assets/zlecenie_pc.html")
                }
            },
            onRelease = { it.destroy() }
        )
    }
}
