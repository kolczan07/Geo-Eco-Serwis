package pl.geoeco.serwis

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    fun get(url: String, timeoutMs: Int = 10000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "Geo-Eco-Serwis/3.1")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
