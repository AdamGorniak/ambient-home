package pl.ambienthome.tv

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.util.concurrent.Executors

/** All shell mutations pass DebloatEngine; opening the menu performs no ADB action. */
class DebloatMenu(private val activity: Activity) : AutoCloseable {
    private val prefs = activity.getSharedPreferences("debloat", Activity.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val store = DebloatStore(activity.applicationContext)
    private val user = Process.myUid() / 100000
    private var shell: Shell? = null
    private var protectedPackages = emptySet<String>()
    private var rows = listOf<Row>()
    private val device get() = DebloatDevice(Build.MANUFACTURER, Build.MODEL, Build.FINGERPRINT)
    private var reportServer: PhotoTransferServer? = null
    private var reportDialog: AlertDialog? = null
    @Volatile private var closed = false
    @Volatile private var visible = true
    private data class Row(val pkg: String, val label: String, val disabled: Boolean)
    private fun message(title: String, body: String) { if (!closed && visible && !activity.isDestroyed) AlertDialog.Builder(activity).setTitle(title).setMessage(body).setPositiveButton("Zamknij", null).show() }
    private fun <T> job(label: String, work: () -> T, done: (T) -> Unit) {
        if (closed) return
        val progress = AlertDialog.Builder(activity).setTitle(label).setMessage("Trwa operacja…").setCancelable(false).show()
        worker.execute {
            try { val value = work(); main.post { runCatching { progress.dismiss() }; if (!closed && visible && !activity.isDestroyed) done(value) else if (value is AutoCloseable) value.close() } }
            catch (e: Exception) { main.post { runCatching { progress.dismiss() }; message("Nie wykonano operacji", e.message ?: "Błąd połączenia lub zapisu") } }
        }
    }
    fun show() {
        if (closed || activity.isDestroyed) return
        AlertDialog.Builder(activity).setTitle("Debloat · narzędzia ADB")
            .setItems(arrayOf("Połącz / sprawdź ADB na tym TV", "Szybkie czyszczenie · dobór automatyczny", "Przywróć zmiany Ambient", "Dziennik i kopia raportu", "Zaawansowane · pojedyncze pakiety", "Jak to działa")) { _, n ->
                when (n) {
                    0 -> connection()
                    1 -> scan(true, true)
                    2 -> restoreMenu()
                    3 -> journalMenu()
                    4 -> advanced()
                    5 -> message("Debloat — zasady", "Szybkie czyszczenie samo dobiera pakiety według dokładnego modelu, firmware i wersji pakietu. Wymaga jednego potwierdzenia, bez zaznaczania pakietów. Niepewne pozycje pomija. Brak profilu oznacza brak automatycznych zmian. Pierwszy wąski profil oparto na obserwacji Xiaomi MiTV-MOOR2; nie jest gwarancją bezpieczeństwa.\n\nWyłączanie dla bieżącego użytkownika, bez usuwania APK i danych. Stan początkowy jest zapisywany przed każdym poleceniem. Przywracanie dotyczy tylko zmian Ambient. Launchery, konfiguracja/recovery, podstawowe usługi Google, aktywna dostępność, administratorzy i wejścia TV są chronione.\n\nWymagane autoryzowane klasyczne ADB TCP na tym TV. Nie działa w tle. Skopiuj raport przed zmianami; usunięcie danych aplikacji usuwa lokalny dziennik.")
                }
            }.setNegativeButton("Zamknij", null).show()
    }
    private fun advanced() {
        AlertDialog.Builder(activity).setTitle("Zaawansowane · własna ocena pakietów")
            .setItems(arrayOf("Pakiety z opisem", "Wszystkie pakiety systemowe", "Tryb ręczny: " + if (prefs.getBoolean("manual", false)) "włączony" else "wyłączony")) { _, n ->
                when (n) {
                    0 -> scan(true)
                    1 -> scan(false)
                    2 -> if (prefs.getBoolean("manual", false)) { prefs.edit().putBoolean("manual", false).apply(); advanced() }
                    else AlertDialog.Builder(activity).setTitle("Nieznane pakiety producenta")
                        .setMessage("Własny wybór nieopisanych pakietów może pozbawić TV ważnych funkcji. Ochrona podstawowych składników pozostaje aktywna. Tryb ręczny nigdy nie rozszerza automatycznego profilu.")
                        .setPositiveButton("Włącz tryb ręczny") { _, _ -> prefs.edit().putBoolean("manual", true).apply(); advanced() }.setNegativeButton("Anuluj", null).show()
                }
            }.setNegativeButton("Zamknij", null).show()
    }
    private fun connection() {
        val input = EditText(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(prefs.getInt("port", 5555).toString()); selectAll() }
        AlertDialog.Builder(activity).setTitle("Klasyczne ADB TCP · port na tym TV")
            .setMessage("Włącz debugowanie ADB/sieciowe w opcjach programisty TV. Zaakceptuj na ekranie klucz RSA Ambient. Połączenie tylko do tego urządzenia; bez kodu parowania TLS. Port zwykle 5555. Jeśli pojawi się pytanie RSA, zaakceptuj je i ponów sprawdzenie.")
            .setView(input).setPositiveButton("Sprawdź ADB") { _, _ ->
                val port = input.text.toString().toIntOrNull()
                if (port == null || port !in 1..65535) { message("Port", "Podaj liczbę od 1 do 65535"); return@setPositiveButton }
                prefs.edit().putInt("port", port).apply()
                job("Łączenie z ADB", { establish(port) }) { message("ADB gotowe", "Połączono z tym TV jako shell, użytkownik $user.\n\nOtwarcie menu i skanowanie nie wyłączają pakietów.") }
            }.setNegativeButton("Anuluj", null).show()
    }
    private fun establish(port: Int) {
        shell = null
        val network = activity.getSystemService(Activity.CONNECTIVITY_SERVICE) as ConnectivityManager
        val ownIp = network.getLinkProperties(network.activeNetwork)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.mapNotNull { it.hostAddress }.orEmpty()
        val wire = AdbWire(DebloatStore.keys(activity.applicationContext))
        var failure: Exception? = null
        for (host in (listOf("127.0.0.1") + ownIp).distinct()) {
            try {
                val connected = Shell { command -> wire.execute(host, port, command) }
                check(connected.run("id -u").let { it.code == 0 && it.output.trim() == "2000" }) { "Połączenie nie ma uprawnień ADB shell" }
                check(connected.run("getprop ro.build.fingerprint").output.trim() == Build.FINGERPRINT) { "Tożsamość urządzenia nie pasuje do tego TV" }
                check(connected.run("am get-current-user").output.trim().toIntOrNull() == user) { "Bieżący użytkownik TV różni się od użytkownika aplikacji" }
                shell = connected; refreshProtection(); return
            } catch (e: Exception) { failure = e }
        }
        throw IllegalStateException("Brak połączenia z lokalnym ADB. Włącz klasyczne ADB TCP i zaakceptuj RSA; potem ponów.\n${failure?.message.orEmpty()}")
    }
    private fun requireShell(): Shell = shell ?: error("Najpierw wybierz Połącz / sprawdź ADB na tym TV")
    private fun checked(command: String): String = requireShell().run(command).let { check(it.code == 0) { "ADB odmówiło odczytu: ${it.output.take(300)}" }; it.output }
    private fun refreshProtection() {
        val found = mutableSetOf(activity.packageName)
        val services = checked("settings get secure enabled_accessibility_services")
        services.split(':').forEach { val p = it.trim().substringBefore('/'); if (DebloatPolicy.validPackage(p)) found.add(p) }
        val homes = checked("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        Regex("(?m)^\\s*([A-Za-z][A-Za-z0-9_.]*)/[A-Za-z0-9_.$]+").findAll(homes).forEach { found.add(it.groupValues[1]) }
        (activity.getSystemService(Activity.TV_INPUT_SERVICE) as? TvInputManager)?.tvInputList?.forEach { found.add(it.serviceInfo.packageName) }
        (activity.getSystemService(Activity.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)?.activeAdmins?.forEach { found.add(it.packageName) }
        protectedPackages = found
    }
    private fun engine(): DebloatEngine = DebloatEngine(requireShell(), store, user, activity.packageName, protectedPackages, prefs.getBoolean("manual", false))
    private fun scan(profileOnly: Boolean, quick: Boolean = false) {
        if (quick && AutomaticDebloat.targets(device).isEmpty()) {
            message("Brak profilu automatycznego", "Dla tego modelu i firmware nie ma jeszcze profilu automatycznego czyszczenia. Nic nie zostanie wyłączone. Nie musisz samodzielnie wybierać pakietów.\n\n${Build.MANUFACTURER} ${Build.MODEL}\n\nLauncher działa niezależnie od Debloatu.")
            return
        }
        job("Odczyt pakietów", {
            if (shell == null) establish(prefs.getInt("port", 5555))
            refreshProtection()
            fun packages(text: String) = text.lineSequence().filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.filter { DebloatPolicy.validPackage(it) }.toSet()
            val all = packages(checked("pm list packages -s --user $user"))
            val disabled = packages(checked("pm list packages -d --user $user"))
            rows = all.map { pkg ->
                val label = try { activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                Row(pkg, label, pkg in disabled)
            }.sortedWith(compareBy<Row> { DebloatPolicy.protection(it.pkg, activity.packageName, protectedPackages) != null }.thenBy { it.label.lowercase() })
            if (quick) rows = rows.filter { !it.disabled && engine().automaticEligible(it.pkg, device) }
        }) {
            val values = rows.filter { !profileOnly || it.pkg in DebloatPolicy.profiles }
            if (quick) quickPlan(values)
            else if (values.isEmpty()) message("Pakiety", "Nie znaleziono pasujących pakietów systemowych") else packageList(values)
        }
    }
    private fun quickPlan(values: List<Row>) {
        if (values.isEmpty()) {
            message("Szybkie czyszczenie", "Brak dodatkowych zmian pasujących do profilu. Pakiety mogą być już wyłączone, chronione lub mieć inną wersję. Niepewne pozycje pominięto. Nie oznacza to braku całego bloatware na TV.")
            return
        }
        job("Przygotowanie czyszczenia", {
            refreshProtection()
            values.filter { engine().automaticEligible(it.pkg, device) }
                .map { row -> DebloatChange(row.pkg, engine().state(row.pkg), user, "proposal", System.currentTimeMillis()) }
                .filter { it.previous in 0..1 }
        }) { proposals ->
            if (proposals.isEmpty()) { message("Szybkie czyszczenie", "Brak dodatkowych zmian pasujących do profilu. Nic nie wyłączono."); return@job }
            AlertDialog.Builder(activity).setTitle("Uruchomić szybkie czyszczenie?")
                .setMessage("Ambient dobrał ${proposals.size} składników analityki do tego modelu i firmware. Nie musisz wybierać pakietów. Pozostałe, niepewne pozycje zostaną pominięte.\n\nAPK i dane pozostaną na TV. Stan zostanie zapisany przed każdą zmianą. Powrót: Przywróć zmiany Ambient.\n\nProfil oparto na obserwacji pakietu już wyłączonego na testowym TV, bez nowego testu wyłączenia/przywrócenia. Wersja beta — używasz na własną odpowiedzialność.")
                .setPositiveButton("Wyczyść") { _, _ -> job("Szybkie czyszczenie", {
                    DebloatBatch.run(proposals.map { it.pkg }) { pkg ->
                        check(!closed) { "Operacja przerwana" }
                        refreshProtection()
                        engine().disableAutomatic(pkg, device)
                    }.joinToString("\n\n")
                }) { message("Wynik czyszczenia", it) } }
                .setNeutralButton("Kopia planu i raportu") { _, _ -> shareReport(proposals = proposals) }
                .setNegativeButton("Anuluj", null).show()
        }
    }
    private fun packageList(values: List<Row>) {
        AlertDialog.Builder(activity).setTitle("Pakiety · nic nie jest zaznaczone")
            .setItems(values.map { (if (it.disabled) "[wyłączony] " else "") + (if (DebloatPolicy.protection(it.pkg, activity.packageName, protectedPackages) != null) "[chroniony] " else "") + it.label + "\n" + it.pkg }.toTypedArray()) { _, n -> details(values[n]) }
            .setNeutralButton("Szukaj") { _, _ -> val input = EditText(activity).apply { isSingleLine = true }
                AlertDialog.Builder(activity).setTitle("Fragment nazwy lub pakietu").setView(input).setPositiveButton("Szukaj") { _, _ -> packageList(values.filter { it.pkg.contains(input.text.toString(), true) || it.label.contains(input.text.toString(), true) }) }.setNegativeButton("Anuluj", null).show()
            }.setNegativeButton("Zamknij", null).show()
    }
    private fun details(row: Row) {
        job("Odczyt stanu pakietu", { refreshProtection(); engine().state(row.pkg) }) { state ->
            val reason = DebloatPolicy.protection(row.pkg, activity.packageName, protectedPackages)
            val known = DebloatPolicy.profiles[row.pkg]
            val permitted = reason == null && state in 0..1 && (known != null || prefs.getBoolean("manual", false))
            val detail = "${row.pkg}\n\nStan użytkownika $user: $state (0 domyślny, 1 włączony, 2–4 wyłączony).\n\n${reason ?: known ?: "Nieopisany pakiet producenta. Zależności i skutki wyłączenia nie są znane. Wymaga trybu ręcznego."}\n\nWyłączenie dotyczy tylko tego użytkownika. Kopia APK i dane pozostają na TV."
            val builder = AlertDialog.Builder(activity).setTitle(row.label).setMessage(detail).setNegativeButton("Zamknij", null)
            if (permitted) builder.setPositiveButton("Przejrzyj zmianę") { _, _ -> review(row, state) }
            builder.show()
        }
    }
    private fun review(row: Row, state: Int) {
        val proposal = DebloatChange(row.pkg, state, user, "proposal", System.currentTimeMillis())
        AlertDialog.Builder(activity).setTitle("Wyłączyć ten pakiet?")
            .setMessage("${row.label}\n${row.pkg}\n\n${DebloatPolicy.profiles[row.pkg] ?: "Nieznane zależności producenta — możliwa utrata funkcji TV."}\n\nZapisz zewnętrzną kopię raportu. Przed poleceniem Ambient zapisze stan lokalnie; po usunięciu danych aplikacji ten dziennik zniknie.\n\nPowrót: adb shell ${proposal.restoreCommand()}")
            .setPositiveButton("Wyłącz pakiet") { _, _ -> job("Wyłączanie pakietu", { refreshProtection(); engine().disable(row.pkg) }) { message("Wynik", it) } }
            .setNeutralButton("Kopia raportu") { _, _ -> shareReport(proposal) }
            .setNegativeButton("Anuluj", null).show()
    }
    private fun restoreMenu() {
        try {
            val changes = store.entries().filter { it.pending && it.user == user }
            if (changes.isEmpty()) { message("Przywracanie", "Ambient nie ma własnych zmian do przywrócenia. Pakiety wyłączone wcześniej innymi narzędziami pozostają bez zmian."); return }
            AlertDialog.Builder(activity).setTitle("Przywróć zmiany Ambient")
                .setItems(changes.map { "${it.pkg} · ${it.phase}" }.toTypedArray()) { _, n ->
                    AlertDialog.Builder(activity).setTitle("Przywrócić ${changes[n].pkg}?").setMessage("Przywróci zapisany stan sprzed zmiany Ambient. Dane aplikacji nie będą czyszczone.")
                        .setPositiveButton("Przywróć") { _, _ -> job("Przywracanie", { engine().restore(changes[n]) }) { message("Wynik", it) } }.setNegativeButton("Anuluj", null).show()
                }.setNeutralButton("Przywróć wszystkie") { _, _ ->
                    AlertDialog.Builder(activity).setTitle("Przywrócić ${changes.size} zmian Ambient?").setMessage("Dotyczy wyłącznie pozycji z tego dziennika, nie wcześniejszego debloatu.")
                        .setPositiveButton("Przywróć wszystkie") { _, _ -> job("Przywracanie zmian", {
                            val results = changes.map { c -> try { engine().restore(c) } catch (e: Exception) { "${c.pkg}: ${e.message}" } }; results.joinToString("\n")
                        }) { message("Wyniki", it) } }.setNegativeButton("Anuluj", null).show()
                }.setNegativeButton("Zamknij", null).show()
        } catch (e: Exception) { message("Dziennik", e.message.orEmpty()) }
    }
    private fun journalMenu() {
        try {
            val report = store.report()
            val text = TextView(activity).apply { this.text = report; textSize = 12f; setTextIsSelectable(true); setPadding(24,16,24,16) }
            AlertDialog.Builder(activity).setTitle("Dziennik i polecenia powrotu").setView(ScrollView(activity).apply { addView(text) })
                .setPositiveButton("Pobierz kopię przez LAN") { _, _ -> shareReport() }.setNegativeButton("Zamknij", null).show()
        } catch (e: Exception) { message("Dziennik", e.message.orEmpty()) }
    }
    private fun shareReport(proposal: DebloatChange? = null, proposals: List<DebloatChange> = emptyList()) {
        val net = activity.getSystemService(Activity.CONNECTIVITY_SERVICE) as ConnectivityManager
        val ip = net.getLinkProperties(net.activeNetwork)?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        if (ip == null) { message("Kopia raportu", "Połącz TV z lokalną siecią"); return }
        job("Przygotowanie kopii", { PhotoTransferServer(activity.applicationContext, WallAlbum(activity.applicationContext), report = store.report(proposal, proposals)) {} }) { server ->
            reportServer?.close(); reportServer = server
            reportDialog = AlertDialog.Builder(activity).setTitle("Pobierz raport odzyskiwania")
                .setMessage("Otwórz w przeglądarce w swojej sieci:\n\nhttp://$ip:${server.port}/?token=${server.token}\n\nPobierz JSON z poleceniami powrotu. Serwer udostępnia tylko ten raport, bez klucza ADB. Zamknij okno po pobraniu. HTTP w lokalnej sieci nie jest szyfrowany.")
                .setNegativeButton("Zakończ udostępnianie", null).create().apply { setOnDismissListener { server.close(); if (reportServer === server) reportServer = null }; show() }
        }
    }
    fun stopSharing() { reportDialog?.dismiss(); reportDialog = null; reportServer?.close(); reportServer = null }
    fun pause() { visible = false; stopSharing() }
    fun resume() { visible = true }
    override fun close() { closed = true; stopSharing(); worker.shutdownNow(); main.removeCallbacksAndMessages(null) }
}
