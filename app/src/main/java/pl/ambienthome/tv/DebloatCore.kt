package pl.ambienthome.tv

data class DebloatChange(val pkg: String, val previous: Int, val user: Int, val phase: String, val time: Long, val note: String = "") {
    val pending: Boolean get() = phase != "restored"
    fun restoreCommand(): String = "pm ${if (previous == 0) "default-state" else "enable"} --user $user $pkg"
}
interface DebloatJournal {
    fun entries(): List<DebloatChange>
    /** Must durably persist or throw before returning. */
    fun put(change: DebloatChange)
}
object DebloatPolicy {
    val profiles = mapOf(
        "com.miui.tv.analytics" to "Analityka Xiaomi. Wyłączenie ogranicza działanie tego pakietu, nie całej telemetrii TV.",
        "com.xiaomi.statistic" to "Statystyki Xiaomi. Funkcje statystyk producenta przestaną działać.",
        "com.xm.webcontent" to "Treści internetowe/promocyjne Xiaomi. Część treści producenta może zniknąć.",
        "com.mitv.tvhome.mitvplus" to "MiTV+. Zniknie dostęp do tej oferty i powiązanych kanałów.",
        "com.mitv.tvhome.michannel" to "Kanał treści Xiaomi. Może wyłączyć rekomendacje producenta.",
        "com.mitv.tvhome.oemtab" to "Zakładka treści OEM. Może wyłączyć ofertę producenta w ekranie głównym.",
        "fusion.android.tv.demo" to "Tryb demonstracyjny. Nie wybieraj, jeśli używasz TV do prezentacji w sklepie.",
        "com.xiaomi.mitv.manualhelp" to "Instrukcja/pomoc Xiaomi. Pomoc producenta przestanie być dostępna.",
        "com.xiaomi.mimusic2" to "Odtwarzacz muzyki Xiaomi. Nie będzie można go uruchomić.",
        "com.mitv.gallery" to "Galeria producenta. Przeglądanie zdjęć tą aplikacją przestanie działać.",
        "com.google.android.apps.youtube.music" to "YouTube Music. Ta aplikacja przestanie działać.",
        "com.google.android.videos" to "Filmy/Google TV jako aplikacja do filmów. Nie będzie można jej uruchomić.",
        "com.amazon.bueller.photos" to "Amazon Photos. Znikną funkcje zdjęć zapewniane przez ten pakiet."
    )
    fun validPackage(pkg: String) = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(pkg)
    fun protection(pkg: String, own: String, dynamic: Set<String>): String? {
        if (!validPackage(pkg)) return "Nieprawidłowa nazwa pakietu"
        if (pkg == own || pkg.startsWith("pl.ambienthome.")) return "Ambient i jego warianty są chronione"
        if (pkg in dynamic) return "Aktywna dostępność, launcher, administrator lub dostawca wejść TV"
        if (pkg == "dev.trooped.tvquickbars" || pkg == "flar2.homebutton") return "Integracja pilota / automatyki"
        val low = pkg.lowercase()
        val important = listOf("launcher", "setup", "wizard", "recovery", "updat", "upgrad", "provider", "input", "bluetooth", "network", "wifi", "systemui", "settings", "permission", "packageinstaller", "keyguard", "controller", "security", "credential", "keystore", "webview", "tv.service", "tvservice", "tv.livetv", "tvinput", "tv.remote", "cast", "mediashell", "dream", "audio", "tuner", "remotecontrol")
        if (important.any { it in low } || low.endsWith(".service")) return "Składnik systemu, sprzętu, HOME lub konfiguracji"
        if (pkg.startsWith("com.android.") || pkg.startsWith("com.mediatek.") || pkg.startsWith("com.google.") && pkg !in profiles) return "Chroniony składnik platformy / usług Google"
        return null
    }
}

object DebloatBatch {
    fun run(packages: List<String>, operation: (String) -> String): List<String> {
        require(packages.size in 1..10 && packages.distinct().size == packages.size && packages.all { DebloatPolicy.validPackage(it) })
        val results = mutableListOf<String>()
        for (pkg in packages) {
            try { results.add(operation(pkg)) }
            catch (e: Exception) { results.add("Przerwano przy $pkg: ${e.message}. Pozostałe pozycje pominięto; wykonane zmiany są w dzienniku."); break }
        }
        return results
    }
}

/** Transactions preserve exact original DEFAULT/ENABLED state. No uninstall, clear or forced reset. */
class DebloatEngine(private val shell: Shell, private val journal: DebloatJournal, private val user: Int, private val own: String, private val protectedPackages: Set<String>, private val manual: Boolean = false) {
    init { require(user in 0..9999) }
    fun state(pkg: String): Int {
        return parseState(dump(pkg), user)
    }
    private fun dump(pkg: String): String {
        require(DebloatPolicy.validPackage(pkg))
        val result = shell.run("dumpsys package $pkg")
        check(result.code == 0) { "Nie udało się odczytać stanu $pkg" }
        return result.output
    }
    fun automaticEligible(pkg: String, device: DebloatDevice): Boolean {
        val target = AutomaticDebloat.target(device, pkg) ?: return false
        if (DebloatPolicy.protection(pkg, own, protectedPackages) != null) return false
        if (journal.entries().any { it.pkg == pkg && it.user == user && it.pending }) return false
        val snapshot = dump(pkg)
        return AutomaticDebloat.matches(target, snapshot) && parseState(snapshot, user) in 0..1 && systemApplication(snapshot)
    }
    fun disableAutomatic(pkg: String, device: DebloatDevice): String {
        val target = AutomaticDebloat.target(device, pkg) ?: error("Brak zgodnego profilu automatycznego")
        return disableChecked(pkg, target)
    }
    fun disable(pkg: String): String = disableChecked(pkg, null)
    private fun systemApplication(snapshot: String): Boolean {
        val uid = Regex("\\b(?:userId|appId)=(\\d+)\\b").find(snapshot)?.groupValues?.get(1)?.toIntOrNull()
        return uid != null && uid >= 10000 && Regex("(?:pkgFlags|flags)=\\[[^]\\r\\n]*\\bSYSTEM\\b").containsMatchIn(snapshot)
    }
    private fun disableChecked(pkg: String, automatic: AutomaticTarget?): String {
        check(DebloatPolicy.protection(pkg, own, protectedPackages) == null) { DebloatPolicy.protection(pkg, own, protectedPackages).orEmpty() }
        check(manual || pkg in DebloatPolicy.profiles) { "Nieznany pakiet wymaga trybu ręcznego" }
        check(journal.entries().none { it.pkg == pkg && it.user == user && it.pending }) { "Ten pakiet ma już zmianę w dzienniku; najpierw ją przywróć" }
        val snapshot = dump(pkg)
        check(automatic == null || AutomaticDebloat.matches(automatic, snapshot)) { "Wersja pakietu nie pasuje do profilu; pominięto automatyczną zmianę" }
        val before = parseState(snapshot, user)
        if (before !in 0..1) return "Pakiet był już wyłączony; nie zmieniono go i nie dodano do przywracania"
        check(systemApplication(snapshot)) { "Chroniony UID lub niepotwierdzona aplikacja systemowa" }
        val change = DebloatChange(pkg, before, user, "planned", System.currentTimeMillis())
        journal.put(change) // A failed durable write MUST prevent the system command.
        try {
            val result = shell.run("pm disable-user --user $user $pkg")
            val after = state(pkg)
            check(result.code == 0 && after == 3) { "Niepotwierdzony wynik wyłączenia: ${result.output.take(250)}" }
            journal.put(change.copy(phase = "disabled"))
            return "Wyłączono $pkg. Stan początkowy i polecenie powrotu zapisano w dzienniku."
        } catch (e: Exception) {
            journal.put(change.copy(phase = "uncertain", note = e.message.orEmpty().take(500)))
            throw e
        }
    }
    fun restore(change: DebloatChange): String {
        require(change.user == user && change.previous in 0..1 && DebloatPolicy.validPackage(change.pkg)) { "Niezgodny wpis dziennika" }
        check(journal.entries().any { it == change && it.pending }) { "Brak aktywnego wpisu dziennika" }
        val before = state(change.pkg)
        if (before == 0 || before == 1) { journal.put(change.copy(phase = "restored", note = "Pakiet był już włączony; zachowano aktualny stan")); return "Już włączony: ${change.pkg}" }
        check(before == 3) { "Stan pakietu zmieniono poza narzędziem; pominięto automatyczny powrót" }
        val result = shell.run(change.restoreCommand())
        check(result.code == 0 && state(change.pkg) == change.previous) { "Nie udało się potwierdzić przywrócenia ${change.pkg}" }
        journal.put(change.copy(phase = "restored", note = "Przywrócono początkowy stan"))
        return "Przywrócono ${change.pkg}"
    }
    companion object {
        fun parseState(dump: String, user: Int): Int {
            val row = Regex("(?m)^\\s*User $user:([^\\r\\n]*)").find(dump) ?: error("Brak stanu tego użytkownika w dumpsys")
            return Regex("\\benabled=([0-4])\\b").find(row.groupValues[1])?.groupValues?.get(1)?.toInt() ?: error("Nieznany format stanu pakietu")
        }
    }
}
