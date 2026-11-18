package pl.geoeco.serwis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ───────────────────────────── MODEL ─────────────────────────────

data class MailRow(
    val id: String,
    val who: String,
    val subject: String,
    val snippet: String,
    val time: Long,
    val unread: Boolean,
    val count: Int,
    val hasAtt: Boolean = false
)

data class MailAttachment(val messageId: String, val attachmentId: String, val name: String, val mime: String, val size: Long)

data class MailMessage(
    val id: String,
    val from: String,
    val to: String,
    val time: Long,
    val html: String?,
    val text: String?,
    val attachments: List<MailAttachment>,
    val unread: Boolean,
    val snippet: String,
    val cc: String = "",
    val replyTo: String = "",
    val msgIdHeader: String = "",
    val refs: String = ""
)

data class MailThread(val id: String, val subject: String, val messages: List<MailMessage>)

class MailNew(val row: MailRow, val fromEmail: String)

// ───────────────────────────── POMOCNICZE ─────────────────────────────

/** Odświeża książkę adresową z historii poczty: wysłane (ważniejsze) i odebrane. */
suspend fun refreshMailContacts(ctx: Context) {
    val me = Prefs.mailAccount(ctx).lowercase()
    val names = HashMap<String, String>()
    val counts = HashMap<String, Int>()
    fun take(h: String, w: Int) {
        for (a in splitAddrs(h)) {
            val e = senderEmail(a)
            if (!e.contains("@") || e == me || e.contains("noreply") || e.contains("no-reply") || e.contains("mailer-daemon")) continue
            counts[e] = (counts[e] ?: 0) + w
            val n = senderName(a)
            if (n.isNotBlank() && !n.contains("@") && names[e].isNullOrEmpty()) names[e] = n
        }
    }
    ImapApi.recentHeaders(ctx, "SENT", 80, listOf("To", "Cc")).forEach { (to, cc) -> take(to, 3); take(cc, 2) }
    ImapApi.recentHeaders(ctx, "INBOX", 80, listOf("From")).forEach { (f) -> take(f, 1) }
    Prefs.setMailContacts(ctx, counts.map { (e, c) -> MailContact(e, names[e].orEmpty(), c) })
}

/** Dzieli listę adresów po przecinkach, pomijając przecinki w cudzysłowie. */
internal fun splitAddrs(s: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var q = false
    for (ch in s) {
        if (ch == '"') q = !q
        if (ch == ',' && !q) { out.add(cur.toString().trim()); cur.clear() } else cur.append(ch)
    }
    out.add(cur.toString().trim())
    return out.filter { it.isNotEmpty() }
}

private fun replyInit(t: MailThread, myEmail: String, all: Boolean, sig: String): ComposeInit {
    val own = { m: MailMessage -> myEmail.isNotEmpty() && m.from.contains(myEmail, ignoreCase = true) }
    val last = t.messages.lastOrNull { !own(it) } ?: t.messages.last()
    val ref = t.messages.last()
    val to = if (own(last)) last.to else last.replyTo.ifBlank { last.from }
    val toMail = senderEmail(to)
    val cc = if (all) {
        splitAddrs(last.to + "," + last.cc)
            .filter { !(myEmail.isNotEmpty() && it.contains(myEmail, ignoreCase = true)) && senderEmail(it) != toMail }
            .joinToString(", ")
    } else ""
    val subj = if (t.subject.startsWith("re:", ignoreCase = true)) t.subject else "Re: ${t.subject}"
    val junk = Regex("<(?:mailto:|https?://|gg:)[^>]*>")
    val src = (last.text ?: last.snippet).replace("\r", "").replace(junk, "").lines().take(80).joinToString("\n") { "> $it" }
    val quote = "${mailTimeFull(last.time)}, ${senderName(last.from)} napisał(a):\n$src"
    return ComposeInit(
        to = to, cc = cc, subject = subj, body = "", signature = sig.trim(), quote = quote, threadId = t.id,
        inReplyTo = ref.msgIdHeader,
        references = (ref.refs + " " + ref.msgIdHeader).trim()
    )
}

private fun unescapeHtml(s: String): String =
    s.replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

/** Sam adres e-mail z nagłówka From, małymi literami. */
internal fun senderEmail(h: String): String =
    (if (h.contains("<")) h.substringAfter("<").substringBefore(">") else h).trim().lowercase()

/** „Imię Nazwisko <adres>” → „Imię Nazwisko” (albo sam adres). */
internal fun senderName(h: String): String {
    if (h.isBlank()) return ""
    val name = h.substringBefore("<").trim().trim('"').trim()
    if (name.isNotEmpty() && h.contains("<")) return name
    return h.substringAfter("<", h).substringBefore(">").trim()
}

private fun mailTime(t: Long): String {
    if (t <= 0L) return ""
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
    val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
    val f = when {
        sameDay -> "HH:mm"
        sameYear -> "d MMM"
        else -> "dd.MM.yyyy"
    }
    return SimpleDateFormat(f, Locale("pl", "PL")).format(Date(t))
}

/** Nagłówek grupy na liście: Dzisiaj / Wczoraj / Ostatnie 7 dni / miesiąc (z rokiem, jeśli inny niż bieżący). */
private fun mailGroup(t: Long): String {
    if (t <= 0L) return ""
    fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = Math.round((startOfDay(System.currentTimeMillis()) - startOfDay(t)) / 86_400_000.0).toInt()
    return when {
        days <= 0 -> "Dzisiaj"
        days == 1 -> "Wczoraj"
        days <= 7 -> "Ostatnie 7 dni"
        else -> {
            val sameYear = Calendar.getInstance().get(Calendar.YEAR) == Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.YEAR)
            SimpleDateFormat(if (sameYear) "LLLL" else "LLLL yyyy", Locale("pl", "PL")).format(Date(t))
                .replaceFirstChar { it.uppercase() }
        }
    }
}

private fun mailTimeFull(t: Long): String =
    if (t <= 0L) "" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale("pl", "PL")).format(Date(t))

private fun sizeText(b: Long): String = when {
    b >= 1_048_576 -> String.format(Locale("pl", "PL"), "%.1f MB", b / 1_048_576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

private val DARK_MEDIA = Regex("@media[^{]*prefers-color-scheme\\s*:\\s*dark[^{]*\\{", RegexOption.IGNORE_CASE)

/** Usuwa bloki @media (prefers-color-scheme: dark) — mail ma być w swoim jasnym wydaniu, które potem odwracamy sami. */
private fun stripDarkMedia(html: String): String {
    var out = html
    while (true) {
        val m = DARK_MEDIA.find(out) ?: return out
        var depth = 1
        var i = m.range.last + 1
        while (i < out.length && depth > 0) {
            if (out[i] == '{') depth++ else if (out[i] == '}') depth--
            i++
        }
        out = out.removeRange(m.range.first, i)
    }
}

private fun wrapHtml(rawBody: String, dark: Boolean, textColor: Int, isHtml: Boolean): String {
    val body = if (dark && isHtml) stripDarkMedia(rawBody) else rawBody
    // Mail HTML w ciemnym motywie odwracamy filtrem, więc domyślny tekst ma być ciemny (po odwróceniu jasny).
    val col = if (dark && isHtml) "#111111" else String.format("#%06X", textColor and 0xFFFFFF)
    // Ciemny motyw: mail HTML (zwykle czarny tekst na białym) odwracamy filtrem, a obrazy przywracamy.
    val darkFix = if (dark && isHtml)
        "html{filter:invert(1) hue-rotate(180deg)}img,video{filter:invert(1) hue-rotate(180deg)}" else ""
    return "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>a{color:#4a8fe7}summary{list-style:none}summary::-webkit-details-marker{display:none}" +
        "body{font-family:sans-serif;font-size:15px;line-height:1.45;margin:0;padding:0;color:$col;word-wrap:break-word;overflow-wrap:anywhere}" +
        "img{max-width:100%;height:auto}table{max-width:100%}pre{white-space:pre-wrap}$darkFix</style></head><body>$body</body></html>"
}

private fun escapeText(t: String): String = plainToHtml(t)

private fun isPdf(a: MailAttachment): Boolean =
    a.mime.equals("application/pdf", ignoreCase = true) || a.name.endsWith(".pdf", ignoreCase = true)

private fun saveAttachment(ctx: Context, a: MailAttachment, bytes: ByteArray): File {
    val safe = a.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "zalacznik" }
    val dir = File(ctx.cacheDir, "mail/${a.messageId}").apply { mkdirs() }
    return File(dir, safe).also { it.writeBytes(bytes) }
}

private fun openFile(ctx: Context, a: MailAttachment, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    val i = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, a.mime.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(Intent.createChooser(i, a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ───────────────────────────── EKRAN ─────────────────────────────

private val FOLDERS = listOf("INBOX" to "Odebrane", "SENT" to "Wysłane", "DRAFT" to "Robocze", "TRASH" to "Kosz")

@Composable
fun MailScreen(onBack: () -> Unit, onOpenAccount: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf("INBOX") }
    var query by remember { mutableStateOf("") }
    var onlyUnread by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val rows = remember { mutableStateListOf<MailRow>() }
    var nextToken by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var thread by remember { mutableStateOf<MailThread?>(null) }
    var openingId by remember { mutableStateOf<String?>(null) }
    var needsConnect by remember { mutableStateOf(false) }
    val trusted = remember { mutableStateListOf<String>().apply { addAll(Prefs.trustedSenders(ctx)) } }
    var compose by remember { mutableStateOf<ComposeInit?>(null) }
    var contacts by remember { mutableStateOf(Prefs.mailContacts(ctx)) }
    val selected = remember { mutableStateListOf<String>() }
    var pdfView by remember { mutableStateOf<Pair<String, File>?>(null) }

    /** Wywołanie operacji pocztowej poza wątkiem UI. */
    suspend fun <T> api(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Odświeża licznik nieprzeczytanych (kafelek na ekranie głównym). */
    suspend fun updateUnread() {
        try { Prefs.setMailUnread(ctx, api { ImapApi.inboxUnread(ctx) }) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { }
    }

    /** Klucz bieżącego wyszukiwania (filtr nieprzeczytanych tylko w Odebranych plus wpisany tekst). */
    fun searchQuery(): String = (if (onlyUnread && folder == "INBOX") "U:" else "") + query.trim()

    fun load(more: Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            status = ""
            try {
                val label = folder
                val q = searchQuery()
                val unreadOnly = onlyUnread && label == "INBOX"
                val (fetched, next) = api { ImapApi.list(ctx, label, if (more) nextToken else null, query.trim(), unreadOnly) }
                if (label == folder && q == searchQuery()) {
                    if (!more) rows.clear()
                    // Wątek może zahaczać o dwie strony listy — nie dublujemy wierszy.
                    rows.addAll(fetched.filter { n -> rows.none { it.id == n.id } })
                    nextToken = next
                }
                if (label == "INBOX") updateUnread()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Błąd: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    fun onConnected() {
        connected = true
        needsConnect = false
        load(false)
        // Książkę adresową odświeżamy w tle, najwyżej raz na dobę.
        if (Prefs.mailContactsAge(ctx) > 24L * 3600 * 1000) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { refreshMailContacts(ctx) }
                    contacts = Prefs.mailContacts(ctx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) { }
            }
        }
    }

    fun connect() {
        if (!Prefs.mailConfigured(ctx)) {
            needsConnect = true
            return
        }
        onConnected()
    }

    LaunchedEffect(Unit) {
        File(ctx.cacheDir, "mail").deleteRecursively()
        connect()
    }

    fun emptyTrash() {
        scope.launch {
            busy = true
            status = ""
            try {
                api { ImapApi.deleteAllTrash(ctx) }
                rows.clear()
                nextToken = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Nie udało się opróżnić kosza: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    fun openThread(id: String) {
        scope.launch {
            openingId = id
            try {
                val t = api { ImapApi.getThread(ctx, id) }
                thread = t
                val idx = rows.indexOfFirst { it.id == id }
                if (idx >= 0 && rows[idx].unread) {
                    rows[idx] = rows[idx].copy(unread = false)
                    try { api { ImapApi.markRead(ctx, id) } } catch (e: Exception) { }
                    updateUnread()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Nie udało się otworzyć wiadomości: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                openingId = null
            }
        }
    }

    /** Oznacza zaznaczone wątki jako przeczytane (tylko te, które są nieprzeczytane). */
    fun markSelectedRead() {
        val ids = selected.filter { id -> rows.any { it.id == id && it.unread } }
        selected.clear()
        if (ids.isEmpty()) return
        scope.launch {
            busy = true
            var failed = 0
            ids.forEach { id ->
                try {
                    api { ImapApi.markRead(ctx, id) }
                    val idx = rows.indexOfFirst { it.id == id }
                    if (idx >= 0) rows[idx] = rows[idx].copy(unread = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
            }
            if (failed > 0) status = "Nie udało się oznaczyć wiadomości: $failed"
            updateUnread()
            busy = false
        }
    }

    /** Przenosi zaznaczone wątki do kosza. */
    fun trashSelected() {
        val ids = selected.toList()
        selected.clear()
        if (ids.isEmpty()) return
        scope.launch {
            busy = true
            var failed = 0
            ids.forEach { id ->
                try {
                    api { ImapApi.trash(ctx, id) }
                    rows.removeAll { it.id == id }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
            }
            if (failed > 0) status = "Nie udało się usunąć wiadomości: $failed"
            updateUnread()
            busy = false
        }
    }

    val comp = compose
    if (comp != null) {
        MailComposeScreen(
            init = comp,
            contacts = contacts,
            onSend = { d ->
                api { ImapApi.send(ctx, buildRaw(ctx, d)) }
                // Adresaci wysłanej wiadomości trafiają od razu do podpowiedzi.
                val add = (splitAddrs(d.to.replace(";", ",")) + splitAddrs(d.cc.replace(";", ",")))
                    .map { senderEmail(it) }.filter { it.contains("@") }
                if (add.isNotEmpty()) {
                    val m = Prefs.mailContacts(ctx).associateBy { it.email }.toMutableMap()
                    add.forEach { e -> m[e] = MailContact(e, m[e]?.name.orEmpty(), (m[e]?.count ?: 0) + 3) }
                    Prefs.setMailContacts(ctx, m.values.toList())
                    contacts = Prefs.mailContacts(ctx)
                }
            },
            onSaveDraft = { d -> api { ImapApi.saveDraft(ctx, buildRaw(ctx, d)) } },
            onClose = { sent ->
                compose = null
                if (sent) {
                    thread?.let { openThread(it.id) }
                    if (folder == "SENT") load(false)
                } else if (folder == "DRAFT") load(false)
            }
        )
        return
    }

    val pv = pdfView
    if (pv != null) {
        BackHandler { pdfView = null }
        PdfViewerScreen(pv.first, pv.second, onClose = { pdfView = null })
        return
    }

    val cur = thread
    if (cur != null) {
        BackHandler { thread = null }
        MailThreadView(
            thread = cur,
            folder = folder,
            myEmail = Prefs.mailAccount(ctx),
            trusted = trusted,
            onReply = { all -> compose = replyInit(cur, Prefs.mailAccount(ctx), all, Prefs.mailSignature(ctx)) },
            onTrust = { addr ->
                if (addr.isNotEmpty() && addr !in trusted) {
                    trusted.add(addr)
                    Prefs.setTrustedSenders(ctx, trusted.toList())
                }
            },
            onOpenPdf = { name, f -> pdfView = name to f },
            fetchAttachment = { a -> api { ImapApi.attachment(ctx, a.messageId, a.attachmentId) } },
            onTrash = {
                scope.launch {
                    try {
                        api { ImapApi.trash(ctx, cur.id) }
                        rows.removeAll { it.id == cur.id }
                        thread = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        status = "Nie udało się usunąć: " + (e.message ?: e.javaClass.simpleName)
                    }
                }
            },
            onRestore = {
                scope.launch {
                    try {
                        api { ImapApi.untrash(ctx, cur.id) }
                        rows.removeAll { it.id == cur.id }
                        thread = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        status = "Nie udało się przywrócić: " + (e.message ?: e.javaClass.simpleName)
                    }
                }
            },
            onBack = { thread = null }
        )
        return
    }

    val selecting = selected.isNotEmpty()
    if (selecting) BackHandler { selected.clear() }
    AppScaffold(
        if (selecting) "Zaznaczono: ${selected.size}" else "Poczta",
        { if (selecting) selected.clear() else onBack() },
        onRefresh = { if (connected && !selecting) load(false) },
        refreshing = busy,
        actions = {
            if (selecting) {
                TextButton(onClick = {
                    selected.clear()
                    selected.addAll(rows.map { it.id })
                }) { Text("Wszystkie") }
                IconButton(enabled = !busy, onClick = { markSelectedRead() }) {
                    Icon(Icons.Filled.Check, contentDescription = "Oznacz jako przeczytane")
                }
                if (folder != "TRASH") {
                    IconButton(enabled = !busy, onClick = { trashSelected() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Usuń")
                    }
                }
            } else {
                IconButton(enabled = connected && !busy, onClick = { load(false) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Odśwież")
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FOLDERS.forEach { (id, name) ->
                    val unreadN = if (id == "INBOX") Prefs.mailUnread(ctx) else 0
                    FolderTab(if (unreadN > 0) "$name  $unreadN" else name, folder == id) {
                        if (folder != id && connected) {
                            folder = id
                            selected.clear()
                            rows.clear()
                            nextToken = null
                            load(false)
                        }
                    }
                }
            }
            if (connected) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchField(query, { query = it }, "Szukaj", Modifier.weight(1f), onSearch = { load(false) })
                    if (folder == "INBOX") {
                        FilterChipSmall("Nieprzeczytane", onlyUnread) {
                            onlyUnread = !onlyUnread
                            selected.clear()
                            rows.clear()
                            nextToken = null
                            load(false)
                        }
                    }
                }
            }
            if (folder == "TRASH" && rows.isNotEmpty() && query.isBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = { confirmEmpty = true }) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text("Opróżnij kosz", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (needsConnect) {
                SubText(
                    "Poczta nie jest jeszcze skonfigurowana. Podaj adres, serwery IMAP i SMTP oraz hasło do skrzynki.",
                    Modifier.padding(top = 16.dp)
                )
                Button(enabled = !busy, onClick = onOpenAccount, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text("Skonfiguruj konto")
                }
            }
            if (status.isNotEmpty()) {
                Text(status, modifier = Modifier.padding(top = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
            if (busy && rows.isEmpty()) SubText("Wczytywanie…", Modifier.padding(top = 16.dp))
            if (!busy && !needsConnect && status.isEmpty() && rows.isEmpty() && connected) {
                SubText(if (searchQuery().isNotEmpty()) "Brak wyników." else "Brak wiadomości w tym folderze.", Modifier.padding(top = 16.dp))
            }
            val sheetShape = RoundedCornerShape(16.dp)
            LazyColumn(
                Modifier.weight(1f).padding(top = 8.dp).clip(sheetShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, sheetShape)
            ) {
                rows.forEachIndexed { idx, r ->
                    val label = mailGroup(r.time)
                    if (label.isNotEmpty() && label != rows.getOrNull(idx - 1)?.let { mailGroup(it.time) }) {
                        item(key = "h$idx") {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                            )
                        }
                    }
                    item(key = r.id) {
                        MailRowCard(
                            r,
                            loading = openingId == r.id,
                            selected = r.id in selected,
                            onClick = {
                                if (selecting) {
                                    if (r.id in selected) selected.remove(r.id) else selected.add(r.id)
                                } else if (openingId == null) openThread(r.id)
                            },
                            onLongClick = { if (r.id !in selected) selected.add(r.id) }
                        )
                    }
                }
                if (nextToken != null) {
                    item {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { load(true) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) { Text(if (busy) "Wczytywanie…" else "Wczytaj starsze") }
                    }
                }
                // Miejsce pod przyciskiem „Napisz”, żeby nie zasłaniał ostatniej wiadomości.
                item { Spacer(Modifier.size(88.dp)) }
            }
        }
        if (connected) {
            ExtendedFloatingActionButton(
                onClick = { compose = ComposeInit(signature = Prefs.mailSignature(ctx).trim()) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(pad).padding(16.dp)
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Napisz", fontWeight = FontWeight.Bold)
            }
        }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Opróżnić kosz?") },
            text = { Text("Wszystkie wiadomości z kosza zostaną usunięte na stałe. Tego nie da się cofnąć.") },
            confirmButton = { TextButton(onClick = { confirmEmpty = false; emptyTrash() }) { Text("Usuń na stałe") } },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Anuluj") } }
        )
    }
}

/** Zakładka folderu: zwykły tekst, aktywna z delikatnym kolorowym tłem. */
@Composable
private fun FolderTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            fontSize = 14.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) cs.primary else cs.onSurfaceVariant
        )
    }
}

/** Mały filtr przy wyszukiwarce (np. „Nieprzeczytane”). */
@Composable
private fun FilterChipSmall(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (selected) cs.primary else cs.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text,
            fontSize = 12.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) cs.primary else cs.onSurfaceVariant
        )
    }
}

@Composable
private fun PillChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) cs.primary else cs.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            fontSize = 14.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) cs.onPrimary else cs.onSurfaceVariant
        )
    }
}

private val AVATAR_COLORS = listOf(0xFF2563EB, 0xFF0D9488, 0xFFD97706, 0xFFDB2777, 0xFF7C3AED, 0xFF16A34A).map { Color(it) }

private fun avatarColor(s: String): Color = AVATAR_COLORS[(s.lowercase().hashCode() and 0x7fffffff) % AVATAR_COLORS.size]

@Composable
private fun MailRowCard(r: MailRow, loading: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ac = avatarColor(r.who)
    val dark = cs.background.luminance() < 0.5f
    val readWeight = if (dark) FontWeight.SemiBold else FontWeight.Bold
    val unreadWeight = FontWeight.ExtraBold
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, onLongClick = onLongClick, flat = true) {
        Row(
            Modifier.background(if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(Modifier.size(38.dp)) {
                Box(
                    Modifier.fillMaxSize().clip(CircleShape).background(if (selected) cs.primary else ac.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(Icons.Filled.Check, contentDescription = "Zaznaczono", tint = cs.onPrimary, modifier = Modifier.size(22.dp))
                    } else {
                        Text(
                            r.who.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?",
                            fontWeight = FontWeight.Bold,
                            color = ac
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.who.ifBlank { "—" },
                        modifier = Modifier.weight(1f),
                        fontWeight = if (r.unread) unreadWeight else readWeight,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = cs.onSurface
                    )
                    if (r.hasAtt) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Załącznik", tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    if (r.count > 1) SubText("${r.count}  ")
                    Text(
                        if (loading) "…" else mailTime(r.time),
                        fontSize = 12.sp,
                        fontWeight = if (r.unread) FontWeight.Bold else FontWeight.Normal,
                        color = if (r.unread) cs.primary else cs.onSurfaceVariant
                    )
                }
                Text(
                    r.subject,
                    fontWeight = if (r.unread) unreadWeight else readWeight,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = cs.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.snippet,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = cs.onSurfaceVariant
                    )
                    if (r.unread && !selected) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(9.dp).clip(CircleShape).background(cs.primary))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 66.dp).height(1.dp).background(cs.outlineVariant))
    }
}

// ───────────────────────────── WĄTEK (ROZMOWA) ─────────────────────────────

@Composable
private fun MailThreadView(
    thread: MailThread,
    folder: String,
    myEmail: String,
    trusted: List<String>,
    onReply: (Boolean) -> Unit,
    onTrust: (String) -> Unit,
    fetchAttachment: suspend (MailAttachment) -> ByteArray,
    onOpenPdf: (String, File) -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var busyAtt by remember { mutableStateOf<String?>(null) }
    // Rozwinięta jest ostatnia wiadomość i nieprzeczytane; reszta zwinięta do jednej linii.
    val expanded = remember(thread.id) {
        mutableStateMapOf<String, Boolean>().apply {
            thread.messages.forEachIndexed { i, m -> put(m.id, i == thread.messages.lastIndex || m.unread) }
        }
    }
    val images = remember(thread.id) { mutableStateMapOf<String, Boolean>() }

    AppScaffold(
        thread.subject,
        onBack,
        actions = {
            if (folder == "TRASH") {
                TextButton(onClick = onRestore) { Text("Przywróć") }
            } else {
                IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, contentDescription = "Usuń") }
            }
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                thread.subject,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            thread.messages.forEach { m ->
                val own = myEmail.isNotEmpty() && m.from.contains(myEmail, ignoreCase = true)
                val open = expanded[m.id] == true
                val cs = MaterialTheme.colorScheme
                val shape = RoundedCornerShape(16.dp)
                Column(
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(if (own) cs.primary.copy(alpha = 0.08f) else cs.surface)
                        .border(1.dp, cs.outlineVariant, shape)
                        .clickable { expanded[m.id] = !open }
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                senderName(m.from).firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                fontWeight = FontWeight.Bold,
                                color = cs.primary
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (own) "Ty" else senderName(m.from).ifBlank { "—" },
                                fontWeight = FontWeight.Bold,
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (open) {
                                Text(
                                    "Do: " + splitAddrs(m.to).joinToString(", ") { senderName(it) }.ifBlank { "—" },
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = cs.onSurfaceVariant
                                )
                            } else SubText(m.snippet.take(80))
                        }
                        SubText(if (open) mailTimeFull(m.time).replace(" ${Calendar.getInstance().get(Calendar.YEAR)},", ",") else mailTime(m.time))
                    }
                    if (open) {
                        Spacer(Modifier.size(10.dp))
                        val showImages = images[m.id] == true || senderEmail(m.from) in trusted
                        val html = m.html
                        val body = when {
                            html != null -> html
                            m.text != null -> escapeText(m.text)
                            else -> escapeText(m.snippet)
                        }
                        MailBody(
                            wrapHtml(
                                body,
                                dark = cs.background.luminance() < 0.5f,
                                textColor = cs.onSurface.toArgb(),
                                isHtml = html != null
                            ),
                            showImages
                        )
                        if (html != null && !showImages && html.contains("<img", ignoreCase = true)) {
                            Row {
                                TextButton(onClick = { images[m.id] = true }) { Text("Pokaż obrazy") }
                                if (!own) TextButton(onClick = { onTrust(senderEmail(m.from)) }) { Text("Ufaj nadawcy") }
                            }
                        }
                        m.attachments.forEach { a ->
                            val key = a.messageId + a.attachmentId
                            OutlinedButton(
                                enabled = busyAtt == null,
                                onClick = {
                                    scope.launch {
                                        busyAtt = key
                                        info = ""
                                        try {
                                            val bytes = withContext(Dispatchers.IO) { fetchAttachment(a) }
                                            val f = withContext(Dispatchers.IO) { saveAttachment(ctx, a, bytes) }
                                            if (isPdf(a)) onOpenPdf(a.name, f) else openFile(ctx, a, f)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: android.content.ActivityNotFoundException) {
                                            info = "Brak aplikacji do otwarcia tego typu pliku."
                                        } catch (e: Exception) {
                                            info = "Nie udało się pobrać załącznika: " + (e.message ?: e.javaClass.simpleName)
                                        } finally {
                                            busyAtt = null
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            ) {
                                Text(
                                    (if (busyAtt == key) "Pobieranie… " else "📎 ") + a.name + "  (" + sizeText(a.size) + ")",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            if (info.isNotEmpty()) Text(info, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            if (folder != "TRASH" && folder != "DRAFT") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onReply(false) }, modifier = Modifier.weight(1f)) { Text("Odpowiedz") }
                    OutlinedButton(onClick = { onReply(true) }, modifier = Modifier.weight(1f)) { Text("Wszystkim") }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Przenieść rozmowę do kosza?") },
            text = { Text("Wiadomości trafią do kosza. Opróżnienie kosza usuwa je na stałe.") },
            confirmButton = { TextButton(onClick = { confirm = false; onTrash() }) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Anuluj") } }
        )
    }
}

@Composable
private fun MailBody(html: String, showImages: Boolean) {
    val ctx = LocalContext.current
    AndroidView(
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = false
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    @Suppress("DEPRECATION")
                    settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
                }
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(0)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) { }
                        return true
                    }
                }
            }
        },
        update = { w ->
            w.settings.blockNetworkImage = !showImages
            if (w.tag != html) {
                w.tag = html
                w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
