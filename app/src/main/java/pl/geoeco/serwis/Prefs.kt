package pl.geoeco.serwis

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("geoeco_prefs", Context.MODE_PRIVATE)

    fun theme(c: Context): String = sp(c).getString("theme", "system") ?: "system"
    fun setTheme(c: Context, v: String) { sp(c).edit().putString("theme", v).apply() }

    fun lastUpdateCheck(c: Context): Long = sp(c).getLong("upd_check", 0L)
    fun setLastUpdateCheck(c: Context, t: Long) { sp(c).edit().putLong("upd_check", t).apply() }

    fun favorites(c: Context): List<Station> =
        readList(sp(c).getString("fav", "[]")) { Station(it.optString("name"), it.optString("url"), it.optString("logo")) }
            .filter { it.url.isNotEmpty() }

    fun setFavorites(c: Context, l: List<Station>) {
        sp(c).edit().putString("fav", toJson(l) { JSONObject().put("name", it.name).put("url", it.url).put("logo", it.logo) }).apply()
    }

    fun shortcuts(c: Context): List<Shortcut> =
        readList(sp(c).getString("shortcuts", "[]")) { Shortcut(it.optString("pkg"), it.optString("label")) }
            .filter { it.pkg.isNotEmpty() }

    fun setShortcuts(c: Context, l: List<Shortcut>) {
        sp(c).edit().putString("shortcuts", toJson(l) { JSONObject().put("pkg", it.pkg).put("label", it.label) }).apply()
    }

    private fun <T> readList(json: String?, f: (JSONObject) -> T): List<T> = try {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { f(a.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun <T> toJson(l: List<T>, f: (T) -> JSONObject): String {
        val a = JSONArray()
        l.forEach { a.put(f(it)) }
        return a.toString()
    }

    // ---- Dane logowania Iglutech: szyfrowane kluczem z Android Keystore ----
    private fun secure(c: Context): SharedPreferences? = try {
        val key = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            c, "geoeco_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }

    fun hasCreds(c: Context): Boolean =
        secure(c)?.let { !it.getString("user", null).isNullOrEmpty() && !it.getString("pass", null).isNullOrEmpty() } ?: false

    fun credsUser(c: Context): String = secure(c)?.getString("user", "") ?: ""

    fun credsRemember(c: Context): Boolean = secure(c)?.getBoolean("remember", false) ?: false

    /** user, pass, remember — albo null, gdy nic nie zapisano. */
    fun creds(c: Context): Triple<String, String, Boolean>? {
        val s = secure(c) ?: return null
        val u = s.getString("user", null) ?: return null
        val p = s.getString("pass", null) ?: return null
        if (u.isEmpty() || p.isEmpty()) return null
        return Triple(u, p, s.getBoolean("remember", false))
    }

    fun saveCreds(c: Context, user: String, pass: String, remember: Boolean): Boolean {
        val s = secure(c) ?: return false
        s.edit().putString("user", user).putString("pass", pass).putBoolean("remember", remember).apply()
        return true
    }

    fun clearCreds(c: Context) {
        secure(c)?.edit()?.remove("user")?.remove("pass")?.remove("remember")?.apply()
    }

    // ---- Poczta (IMAP/SMTP): ustawienia konta; hasło w szyfrowanym magazynie ----
    /** Adres e-mail konta (nadawca wiadomości). */
    fun mailAccount(c: Context): String = sp(c).getString("mail_account", "") ?: ""
    fun setMailAccount(c: Context, email: String) { sp(c).edit().putString("mail_account", email).apply() }

    /** Nazwa wyświetlana nadawcy (opcjonalna). */
    fun mailFromName(c: Context): String = sp(c).getString("mail_from_name", "") ?: ""

    fun mailImapHost(c: Context): String = sp(c).getString("mail_imap_host", "") ?: ""
    fun mailImapPort(c: Context): Int = sp(c).getInt("mail_imap_port", 993)
    /** "ssl" (IMAPS, zwykle port 993) albo "starttls" (zwykle port 143). */
    fun mailImapSec(c: Context): String = sp(c).getString("mail_imap_sec", "ssl") ?: "ssl"

    fun mailSmtpHost(c: Context): String = sp(c).getString("mail_smtp_host", "") ?: ""
    fun mailSmtpPort(c: Context): Int = sp(c).getInt("mail_smtp_port", 465)
    /** "ssl" (SMTPS, zwykle port 465) albo "starttls" (zwykle port 587). */
    fun mailSmtpSec(c: Context): String = sp(c).getString("mail_smtp_sec", "ssl") ?: "ssl"

    /** Login do serwera; gdy pusty, używany jest adres e-mail. */
    fun mailLogin(c: Context): String = (sp(c).getString("mail_login", "") ?: "").ifBlank { mailAccount(c) }
    fun mailPassword(c: Context): String = secure(c)?.getString("mail_pass", "") ?: ""

    /** Czy po wysłaniu zapisywać kopię w folderze Wysłane (wyłącz, jeśli serwer robi to sam, np. Gmail). */
    fun mailSaveSent(c: Context): Boolean = sp(c).getBoolean("mail_save_sent", true)

    fun mailConfigured(c: Context): Boolean =
        mailAccount(c).isNotBlank() && mailImapHost(c).isNotBlank() && mailSmtpHost(c).isNotBlank() && mailPassword(c).isNotEmpty()

    /** Zapisuje konto; hasło trafia do szyfrowanego magazynu. Zwraca false, gdy magazyn kluczy jest niedostępny. */
    fun saveMailAccount(
        c: Context, email: String, fromName: String, login: String, pass: String,
        imapHost: String, imapPort: Int, imapSec: String,
        smtpHost: String, smtpPort: Int, smtpSec: String, saveSent: Boolean
    ): Boolean {
        val s = secure(c) ?: return false
        s.edit().putString("mail_pass", pass).apply()
        sp(c).edit()
            .putString("mail_account", email.trim())
            .putString("mail_from_name", fromName.trim())
            .putString("mail_login", login.trim())
            .putString("mail_imap_host", imapHost.trim())
            .putInt("mail_imap_port", imapPort)
            .putString("mail_imap_sec", imapSec)
            .putString("mail_smtp_host", smtpHost.trim())
            .putInt("mail_smtp_port", smtpPort)
            .putString("mail_smtp_sec", smtpSec)
            .putBoolean("mail_save_sent", saveSent)
            // Nowe konto = nowy punkt odniesienia dla powiadomień i świeża książka adresowa.
            .remove("mail_history_id")
            .remove("mail_contacts_ts")
            .apply()
        return true
    }

    fun clearMailAccount(c: Context) {
        secure(c)?.edit()?.remove("mail_pass")?.apply()
        sp(c).edit()
            .remove("mail_account").remove("mail_from_name").remove("mail_login")
            .remove("mail_imap_host").remove("mail_imap_port").remove("mail_imap_sec")
            .remove("mail_smtp_host").remove("mail_smtp_port").remove("mail_smtp_sec")
            .remove("mail_history_id").remove("mail_unread")
            .apply()
    }

    /** Nadawcy (adresy e-mail, małe litery), od których poczta od razu ładuje obrazy. */
    fun trustedSenders(c: Context): List<String> =
        (sp(c).getString("mail_trusted", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun setTrustedSenders(c: Context, list: List<String>) {
        sp(c).edit().putString("mail_trusted", list.distinct().joinToString("\n")).apply()
    }

    // ── Poczta: podpis, powiadomienia, książka adresowa ──
    /** Podpis zapisany w ustawieniach poczty (domyślnie pusty). */
    fun mailSignature(c: Context): String = sp(c).getString("mail_signature", "") ?: ""
    fun setMailSignature(c: Context, v: String) { sp(c).edit().putString("mail_signature", v).apply() }

    fun mailNotify(c: Context): Boolean = sp(c).getBoolean("mail_notify", false)
    fun setMailNotify(c: Context, v: Boolean) { sp(c).edit().putBoolean("mail_notify", v).apply() }

    /** Sprawdzanie poczty przy włączeniu ekranu (usługa pierwszoplanowa). Domyślnie włączone. */
    fun mailFast(c: Context): Boolean = sp(c).getBoolean("mail_fast", true)
    fun setMailFast(c: Context, v: Boolean) { sp(c).edit().putBoolean("mail_fast", v).apply() }

    /** Ostatnio znana liczba nieprzeczytanych wiadomości w Odebranych (plakietka na kafelku Poczta). */
    fun mailUnread(c: Context): Int = sp(c).getInt("mail_unread", 0)
    fun setMailUnread(c: Context, v: Int) { sp(c).edit().putInt("mail_unread", v).apply(); Widgets.updateMail(c) }

    /** Punkt odniesienia dla powiadomień: "UIDVALIDITY:ostatni_UID". */
    fun mailHistoryId(c: Context): String = sp(c).getString("mail_history_id", "") ?: ""
    fun setMailHistoryId(c: Context, v: String) { sp(c).edit().putString("mail_history_id", v).apply() }

    fun mailContacts(c: Context): List<MailContact> =
        (sp(c).getString("mail_contacts", "") ?: "").lines().mapNotNull { l ->
            val p = l.split("\t")
            if (p.size >= 3 && p[0].isNotBlank()) MailContact(p[0], p[1], p[2].toIntOrNull() ?: 1) else null
        }

    fun setMailContacts(c: Context, list: List<MailContact>) {
        val top = list.sortedByDescending { it.count }.take(600)
        sp(c).edit()
            .putString("mail_contacts", top.joinToString("\n") { it.email + "\t" + it.name.replace("\t", " ").replace("\n", " ") + "\t" + it.count })
            .putLong("mail_contacts_ts", System.currentTimeMillis())
            .apply()
    }

    fun mailContactsAge(c: Context): Long = System.currentTimeMillis() - sp(c).getLong("mail_contacts_ts", 0L)
}
