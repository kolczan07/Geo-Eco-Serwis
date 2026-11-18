package pl.geoeco.serwis

enum class Screen {
    Home, Radio, Weather, Iglutech,
    Shortcuts, Settings, IgluSettings, About,
    Mail, MailSettings, MailAccount
}

data class Station(val name: String, val url: String, val logo: String = "")
data class Shortcut(val pkg: String, val label: String)
