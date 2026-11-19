package pl.geoeco.serwis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Konto pocztowe: adres, serwery IMAP (odbiór) i SMTP (wysyłka), login i hasło. Hasło trafia do szyfrowanego magazynu. */
@Composable
fun MailAccountScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf(Prefs.mailAccount(ctx)) }
    var fromName by remember { mutableStateOf(Prefs.mailFromName(ctx)) }
    var login by remember { mutableStateOf(if (Prefs.mailAccount(ctx).isBlank()) "" else Prefs.mailLogin(ctx).takeIf { it != Prefs.mailAccount(ctx) }.orEmpty()) }
    var pass by remember { mutableStateOf("") }
    var imapHost by remember { mutableStateOf(Prefs.mailImapHost(ctx)) }
    var imapPort by remember { mutableStateOf(Prefs.mailImapPort(ctx).toString()) }
    var imapSsl by remember { mutableStateOf(Prefs.mailImapSec(ctx) == "ssl") }
    var smtpHost by remember { mutableStateOf(Prefs.mailSmtpHost(ctx)) }
    var smtpPort by remember { mutableStateOf(Prefs.mailSmtpPort(ctx).toString()) }
    var smtpSsl by remember { mutableStateOf(Prefs.mailSmtpSec(ctx) == "ssl") }
    var saveSent by remember { mutableStateOf(Prefs.mailSaveSent(ctx)) }
    var status by remember { mutableStateOf(if (Prefs.mailConfigured(ctx)) "Konto zapisane. Wpisz hasło ponownie tylko jeśli chcesz je zmienić." else "") }
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    /** Czy użytkownik sam ruszał pola serwerów — wtedy autokonfiguracja ich nie nadpisuje. */
    var hostsEdited by remember { mutableStateOf(Prefs.mailConfigured(ctx)) }
    var providerNote by remember { mutableStateOf("") }

    // Autokonfiguracja: po wpisaniu adresu rozpoznajemy dostawcę (domena, a dla własnych domen rekord MX).
    LaunchedEffect(email) {
        if (hostsEdited || !email.contains("@")) return@LaunchedEffect
        kotlinx.coroutines.delay(700)
        val p = MailAutoConfig.detect(email)
        if (p == null) {
            providerNote = ""
            return@LaunchedEffect
        }
        imapHost = p.imapHost; imapPort = p.imapPort.toString(); imapSsl = p.imapSsl
        smtpHost = p.smtpHost; smtpPort = p.smtpPort.toString(); smtpSsl = p.smtpSsl
        if (p.savesSentItself) saveSent = false
        providerNote = "Wykryto dostawcę: ${p.name}. Serwery uzupełnione automatycznie." + (if (p.note.isNotBlank()) "\n" + p.note else "")
    }

    /** Podpowiada serwery z domeny adresu, jeśli pola są jeszcze puste. */
    fun guessHosts() {
        val d = email.substringAfter('@', "").trim().lowercase()
        if (d.isEmpty()) return
        if (imapHost.isBlank()) imapHost = "imap.$d"
        if (smtpHost.isBlank()) smtpHost = "smtp.$d"
    }

    fun save() {
        val mail = email.trim()
        if (!mail.contains("@")) { status = "Wpisz poprawny adres e-mail."; return }
        if (imapHost.isBlank() || smtpHost.isBlank()) { status = "Podaj serwery IMAP i SMTP."; return }
        val ip = imapPort.trim().toIntOrNull()
        val sp = smtpPort.trim().toIntOrNull()
        if (ip == null || sp == null) { status = "Porty muszą być liczbami (np. 993 i 465)."; return }
        // Puste hasło przy już zapisanym koncie = zostaje dotychczasowe.
        val password = if (pass.isEmpty()) Prefs.mailPassword(ctx) else pass
        if (password.isEmpty()) { status = "Wpisz hasło do skrzynki."; return }
        val ok = Prefs.saveMailAccount(
            ctx, mail, fromName, login.ifBlank { mail }, password,
            imapHost, ip, if (imapSsl) "ssl" else "starttls",
            smtpHost, sp, if (smtpSsl) "ssl" else "starttls", saveSent
        )
        if (!ok) { status = "Nie udało się zapisać hasła (brak dostępu do magazynu kluczy)."; return }
        pass = ""
        scope.launch {
            busy = true
            status = "Sprawdzam połączenie…"
            try {
                withContext(Dispatchers.IO) { ImapApi.verify(ctx) }
                status = "Połączono ✓ Konto zapisane."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Zapisano, ale połączenie się nie udało: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    AppScaffold("Konto pocztowe", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SubText("Wpisz adres e-mail — serwery uzupełnią się same dla Google (także firmowych domen na Workspace) i home.pl; dla innych dostawców wpisz je ręcznie. Hasło jest szyfrowane w pamięci telefonu. Dla Gmaila i niektórych innych serwisów potrzebne jest hasło aplikacji, a nie zwykłe hasło konta.")

            LabeledField("Adres e-mail:", email, { email = it }, keyboard = KeyboardType.Email)
            if (providerNote.isNotEmpty()) SubText(providerNote)
            LabeledField("Nazwa nadawcy (opcjonalnie):", fromName, { fromName = it })
            LabeledField("Login (jeśli inny niż adres e-mail):", login, { login = it })
            LabeledField("Hasło:", pass, { pass = it }, password = true)

            SectionTitle("Odbieranie (IMAP)")
            LabeledField("Serwer IMAP:", imapHost, { imapHost = it; hostsEdited = true }, keyboard = KeyboardType.Uri)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledField("Port:", imapPort, { imapPort = it; hostsEdited = true }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                Column(Modifier.weight(2f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = imapSsl, onCheckedChange = { on ->
                            imapSsl = on
                            if (on && imapPort == "143") imapPort = "993"
                            if (!on && imapPort == "993") imapPort = "143"
                        })
                        Text(if (imapSsl) "  SSL/TLS (993)" else "  STARTTLS (143)", fontSize = 14.sp, color = cs.onSurface)
                    }
                }
            }

            SectionTitle("Wysyłanie (SMTP)")
            LabeledField("Serwer SMTP:", smtpHost, { smtpHost = it; hostsEdited = true }, keyboard = KeyboardType.Uri)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledField("Port:", smtpPort, { smtpPort = it; hostsEdited = true }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                Column(Modifier.weight(2f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = smtpSsl, onCheckedChange = { on ->
                            smtpSsl = on
                            if (on && smtpPort == "587") smtpPort = "465"
                            if (!on && smtpPort == "465") smtpPort = "587"
                        })
                        Text(if (smtpSsl) "  SSL/TLS (465)" else "  STARTTLS (587)", fontSize = 14.sp, color = cs.onSurface)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Zapisuj kopię w Wysłanych", fontWeight = FontWeight.Bold, color = cs.onSurface)
                    SubText("Wyłącz, jeśli serwer sam zapisuje wysłane wiadomości (np. Gmail) — inaczej będą podwójne.")
                }
                Switch(checked = saveSent, onCheckedChange = { saveSent = it })
            }

            Button(
                enabled = !busy,
                onClick = { guessHosts(); save() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Zapisz i sprawdź połączenie") }
            OutlinedButton(
                enabled = !busy && Prefs.mailConfigured(ctx),
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Usuń konto z telefonu") }
            if (status.isNotEmpty()) SubText(status)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Usunąć konto pocztowe z telefonu?") },
            text = { Text("Wiadomości zostają na serwerze. Skasowane zostaną tylko dane logowania zapisane w aplikacji.") },
            confirmButton = {
                TextButton(onClick = {
                    MailNotify.cancel(ctx)
                    Prefs.setMailNotify(ctx, false)
                    Prefs.clearMailAccount(ctx)
                    email = ""; fromName = ""; login = ""; pass = ""
                    imapHost = ""; smtpHost = ""
                    status = "Konto usunięte."
                    confirmClear = false
                }) { Text("Usuń") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Anuluj") } }
        )
    }
}
