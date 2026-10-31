package pl.ambienthome.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings
import android.text.InputFilter
import android.widget.EditText
import android.widget.Toast
import org.json.JSONArray

/** Vendor-independent input identities, with local aliases and ordering. */
class InputMenu(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("ambient-inputs", Activity.MODE_PRIVATE)
    private val manager get() = activity.getSystemService(Activity.TV_INPUT_SERVICE) as? TvInputManager
    private fun label(input: TvInputInfo): String = prefs.getString("name:${input.id}", null)?.takeIf { it.isNotBlank() }
        ?: input.loadCustomLabel(activity)?.toString()?.takeIf { it.isNotBlank() } ?: input.loadLabel(activity).toString()
    private fun list(): List<TvInputInfo> {
        val found = try { manager?.tvInputList?.filter { it.isPassthroughInput && it.parentId == null }.orEmpty() } catch (_: Exception) { emptyList() }
        val saved = try { JSONArray(prefs.getString("order", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (_: Exception) { emptyList() }
        return found.sortedWith(compareBy<TvInputInfo> { saved.indexOf(it.id).let { n -> if (n < 0) Int.MAX_VALUE else n } }
            .thenBy { Regex("\\d+").find(label(it))?.value?.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { label(it).lowercase() })
    }
    fun show() {
        val inputs = list()
        if (inputs.isEmpty()) {
            AlertDialog.Builder(activity).setTitle("Źródła TV").setMessage("Ten sprzęt nie udostępnia wejść przez Android TV Input Framework. Użyj przycisku Źródła na pilocie lub ustawień producenta.")
                .setPositiveButton("Ustawienia źródeł") { _, _ -> try { activity.startActivity(Intent("android.settings.TV_INPUT_SETTINGS")) } catch (_: Exception) { try { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { toast("Użyj przycisku Źródła na pilocie") } } }.setNegativeButton("Zamknij", null).show()
            return
        }
        AlertDialog.Builder(activity).setTitle("Źródła TV").setItems(inputs.map { input ->
            label(input) + if (try { manager?.getInputState(input.id) == TvInputManager.INPUT_STATE_DISCONNECTED } catch (_: Exception) { false }) " · brak sygnału" else ""
        }.toTypedArray()) { _, n ->
            try { activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.buildChannelUriForPassthroughInput(inputs[n].id), TvContract.Channels.CONTENT_ITEM_TYPE)) }
            catch (_: Exception) { toast("Producent nie udostępnia przełączania tego wejścia aplikacjom") }
        }.setNeutralButton("Nazwy i kolejność") { _, _ -> edit() }.setNegativeButton("Zamknij", null).show()
    }
    private fun edit() {
        val inputs = list()
        AlertDialog.Builder(activity).setTitle("Nazwy i kolejność wejść").setItems(inputs.map { label(it) }.toTypedArray()) { _, n ->
            val input = inputs[n]
            AlertDialog.Builder(activity).setTitle(label(input)).setItems(arrayOf("Zmień nazwę w Ambient", "Przenieś wyżej", "Przenieś niżej", "Przywróć nazwę producenta")) { _, action ->
                when (action) {
                    0 -> { val text = EditText(activity).apply { isSingleLine = true; filters = arrayOf(InputFilter.LengthFilter(40)); setText(label(input)); setSelectAllOnFocus(true) }
                        AlertDialog.Builder(activity).setTitle("Nazwa wejścia").setView(text).setPositiveButton("Zapisz") { _, _ -> prefs.edit().putString("name:${input.id}", text.text.toString().trim()).apply(); edit() }.setNegativeButton("Anuluj", null).show() }
                    1, 2 -> { val ids = inputs.map { it.id }.toMutableList(); val target = (n + if (action == 1) -1 else 1).coerceIn(0, ids.lastIndex); ids.removeAt(n); ids.add(target, input.id); prefs.edit().putString("order", JSONArray(ids).toString()).apply(); edit() }
                    3 -> { prefs.edit().remove("name:${input.id}").apply(); edit() }
                }
            }.setNegativeButton("Zamknij", null).show()
        }.setNeutralButton("Domyślna kolejność") { _, _ -> prefs.edit().remove("order").apply(); edit() }.setNegativeButton("Zamknij", null).show()
    }
    private fun toast(text: String) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
}

