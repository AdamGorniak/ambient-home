package pl.ambienthome.tv

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class DebloatStore(context: Context) : DebloatJournal {
    private val file = AtomicFile(File(context.filesDir, "debloat-journal.json"))
    private val identity by lazy { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.takeIf { it.isNotBlank() }?.let {
        java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b.toInt() and 255) }
    } ?: error("Brak lokalnej tożsamości urządzenia; dziennik Debloat nie jest dostępny") }
    private fun toJson(c: DebloatChange) = JSONObject().put("package", c.pkg).put("previous", c.previous).put("user", c.user).put("phase", c.phase).put("time", c.time).put("note", c.note).put("restore", "adb shell ${c.restoreCommand()}")
    @Synchronized override fun entries(): List<DebloatChange> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        // Never silently discard a damaged journal: refuse further operations instead.
        val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        check(root.getInt("schema") == 1) { "Nieobsługiwana wersja dziennika" }
        check(root.getString("owner") == identity) { "Dziennik pochodzi z innego urządzenia lub użytkownika" }
        val rows = root.getJSONArray("changes")
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let { DebloatChange(it.getString("package"), it.getInt("previous"), it.getInt("user"), it.getString("phase"), it.getLong("time"), it.optString("note")) }.also {
            check(DebloatPolicy.validPackage(it.pkg) && it.previous in 0..1 && it.user in 0..9999 && it.phase in setOf("planned", "disabled", "uncertain", "restored")) { "Uszkodzony wpis dziennika; zachowaj plik i nie wykonuj dalszych zmian" }
        } }
    }
    @Synchronized override fun put(change: DebloatChange) {
        val values = entries().filterNot { it.pkg == change.pkg && it.user == change.user } + change
        val root = document(values)
        val out = file.startWrite()
        try { out.write(root.toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
    private fun document(values: List<DebloatChange>) = JSONObject().put("schema", 1).put("owner", identity).put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("fingerprint", Build.FINGERPRINT).put("changes", JSONArray().apply { values.forEach { put(toJson(it)) } })
    @Synchronized fun report(proposed: DebloatChange? = null, proposals: List<DebloatChange> = emptyList()): String {
        val doc = document(entries())
        if (proposed != null) doc.put("proposalNotExecuted", toJson(proposed))
        if (proposals.isNotEmpty()) doc.put("proposalsNotExecuted", JSONArray().apply { proposals.forEach { put(toJson(it)) } })
        doc.put("instructions", "Uruchamiaj polecenia restore tylko na tym samym TV i użytkowniku. Przywracanie w menu dotyczy wyłącznie wpisów tego dziennika. Nie usuwaj danych Ambient przed przywróceniem lub wyeksportowaniem raportu.")
        return doc.toString(2)
    }
    companion object {
        fun keys(context: Context): KeyPair {
            val file = AtomicFile(File(context.filesDir, "local-adb-key.json"))
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
                val factory = KeyFactory.getInstance("RSA")
                return KeyPair(factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(json.getString("public")))), factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(json.getString("private")))))
            }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val json = JSONObject().put("private", Base64.getEncoder().encodeToString(pair.private.encoded)).put("public", Base64.getEncoder().encodeToString(pair.public.encoded))
            val out = file.startWrite()
            try { out.write(json.toString().toByteArray()); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
            return pair
        }
    }
}
