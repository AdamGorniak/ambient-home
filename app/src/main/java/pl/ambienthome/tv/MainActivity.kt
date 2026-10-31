package pl.ambienthome.tv

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.util.LruCache
import android.widget.*
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Native home screen. No runtime photo downloads, continuous animation or system writes. */
class MainActivity : Activity() {
    private var debloatMenu: DebloatMenu? = null
    private val prefs by lazy { getSharedPreferences("ambient", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var root: FrameLayout
    private lateinit var photo: ImageView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var weather: TextView
    private lateinit var stats: TextView
    private lateinit var credit: TextView
    private lateinit var dockPosition: TextView
    private lateinit var hdmi: TextView
    private lateinit var dock: LinearLayout
    private lateinit var appScroll: HorizontalScrollView
    private lateinit var shortcuts: LinearLayout
    private lateinit var row: LinearLayout
    private var visible = false
    private var dialogDepth = 0
    private var weatherBusy = false
    private var nextWeatherTry = 0L
    private var lastNet = LongArray(3)
    private var lastCpu: LongArray? = null
    private var procReadable = true
    private var lastAppCpu = 0L
    private var lastAppTime = 0L
    private var wakeKey = -1
    private var movingKey: String? = null
    private var suppressMoveRelease = false
    private val iconCache = object : LruCache<String, Bitmap>(1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private data class App(val component: ComponentName, val label: String)
    private var apps = listOf<App>()
    private val defaults = listOf("org.smarttube.stable", "com.netflix.ninja", "com.spotify.tv.android", "com.wbd.stream", "tv.emby.embyatv", "dev.trooped.tvquickbars")
    private val album by lazy { WallAlbum(this) }
    private var photoToken = ""
    private var photoGeneration = 0
    private var photoBitmap: Bitmap? = null
    private var selectedPhoto: WallAlbum.Photo? = null
    private var transferServer: PhotoTransferServer? = null
    private var transferDialog: AlertDialog? = null
    private val thumbs = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val photoTick = Runnable { loadDailyPhoto() }
    private fun appLabel(app: App) = prefs.getString("name:${app.component.flattenToString()}", null)?.takeIf { it.isNotBlank() } ?: app.label
    private val minuteTick = object : Runnable {
        override fun run() {
            if (!visible) return
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE, d MMMM", Locale("pl", "PL")).format(now)
            loadDailyPhoto()
            refreshWeather()
            main.postDelayed(this, 60000 - System.currentTimeMillis() % 60000)
        }
    }
    private val statTick = object : Runnable {
        override fun run() {
            if (!visible) return
            updateStats()
            main.postDelayed(this, 5000)
        }
    }
    private val hideDock = Runnable {
        if (visible && dialogDepth == 0 && movingKey == null && prefs.getInt("idle", 15) > 0) {
            dock.visibility = View.INVISIBLE
            dockPosition.visibility = View.INVISIBLE
            root.requestFocus()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        retireDuplicateTasks()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        root = FrameLayout(this).apply { isFocusableInTouchMode = true; setBackgroundColor(Color.rgb(16, 32, 37)) }
        setContentView(root)
        photo = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        root.addView(photo, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99071017.toInt(), 0x05071017, 0xB3071017.toInt())) }, FrameLayout.LayoutParams(-1, -1))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = text("", 44f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); letterSpacing = -0.035f }
        date = text("", 14f).apply { setTextColor(0xE0FFFFFF.toInt()) }
        weather = text("Pogoda — wczytywanie", 13f).apply { setPadding(0, dp(10), 0, 0) }
        info.addView(clock); info.addView(date); info.addView(weather)
        root.addView(info, FrameLayout.LayoutParams(dp(350), -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(27); marginStart = dp(36) })
        hdmi = action("Źródła TV  ↓") { showInputs() }
        root.addView(hdmi, FrameLayout.LayoutParams(dp(142), dp(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(36) })
        stats = text("", 11f).apply { gravity = Gravity.END; setTextColor(0xBDFFFFFF.toInt()); setLineSpacing(dp(3).toFloat(), 1f) }
        root.addView(stats, FrameLayout.LayoutParams(dp(225), -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(39); marginEnd = dp(36) })
        credit = text("AMBIENT HOME  /  FOTOGRAFIA · UNSPLASH", 8f).apply { letterSpacing = 0.16f; setTextColor(0x8CFFFFFF.toInt()) }
        root.addView(credit, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { bottomMargin = dp(15); marginStart = dp(36) })
        row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(5), dp(6), dp(5)) }
        appScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isSmoothScrollingEnabled = false; clipToPadding = false; addView(row); isFillViewport = false }
        shortcuts = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        dock = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(appScroll, LinearLayout.LayoutParams(0, -1, 1f))
            addView(shortcuts, LinearLayout.LayoutParams(dp(182), -1))
        }
        root.addView(dock, FrameLayout.LayoutParams(-1, dp(100), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(32); marginStart = dp(28); marginEnd = dp(28) })
        dockPosition = text("", 9f).apply { setTextColor(0xA0FFFFFF.toInt()); gravity = Gravity.END }
        root.addView(dockPosition, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(15); marginEnd = dp(36) })
        rebuildDock()
        (row.getChildAt(0) ?: shortcuts.getChildAt(0))?.requestFocus()
    }

    override fun onResume() {
        super.onResume(); visible = true
        debloatMenu?.resume()
        showDock(false)
        io.execute {
            val discovered = queryApps()
            main.post {
                if (!isDestroyed && apps != discovered) {
                    val initial = apps.isEmpty()
                    apps = discovered; rebuildDock()
                    if (initial && dock.visibility == View.VISIBLE) row.getChildAt(0)?.requestFocus()
                }
            }
        }
        lastNet = longArrayOf(0, 0, 0); lastCpu = null; lastAppTime = 0
        main.post(minuteTick); main.post(statTick)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); retireDuplicateTasks(); showDock(true) }
    private fun retireDuplicateTasks() {
        // This OEM can separate HOME and ordinary app launches even with singleInstance.
        // appTasks contains only our tasks; never finish another app or a system task.
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        try { manager.appTasks.forEach { task ->
            val info = task.taskInfo
            if (info.id != taskId && info.baseIntent.component?.className == MainActivity::class.java.name) task.finishAndRemoveTask()
        } } catch (_: Exception) { /* Normal home operation remains available. */ }
    }
    override fun onPause() {
        debloatMenu?.pause()
        transferDialog?.dismiss(); transferDialog = null; transferServer?.close(); transferServer = null
        if (movingKey != null) finishMove(false)
        photoGeneration++; photoToken = selectedPhoto?.id.orEmpty()
        visible = false; main.removeCallbacksAndMessages(null); dock.animate().cancel()
        super.onPause()
    }
    override fun onDestroy() { debloatMenu?.close(); photoGeneration++; io.shutdownNow(); main.removeCallbacksAndMessages(null); photo.setImageDrawable(null); photoBitmap = null; iconCache.evictAll(); thumbs.evictAll(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); setShadowLayer(5f, 0f, 1f, 0x66000000)
    }
    private fun surface(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(if (focused) 0x4DFFFFFF else 0x40101B29)
        setStroke(dp(1), if (focused) 0xE0FFFFFF.toInt() else Color.TRANSPARENT)
    }
    private fun focusable(view: View) {
        view.id = View.generateViewId(); view.isFocusable = true; view.isClickable = true
        view.background = surface(false)
        view.setOnFocusChangeListener { v, focus -> v.background = surface(focus) }
    }
    private fun action(label: String, clicked: () -> Unit) = text(label, 13f).apply {
        gravity = Gravity.CENTER; focusable(this); setOnClickListener { clicked() }
    }
    private fun queryApps(): List<App> {
        val candidates = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { category ->
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
        }
        return candidates.map { App(ComponentName(it.activityInfo.packageName, it.activityInfo.name), it.loadLabel(packageManager).toString()) }
            .filter { it.component.packageName != packageName }.distinctBy { it.component.packageName }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }
    private fun pins(): MutableList<String> {
        if (!prefs.contains("pins")) return defaults.mapNotNull { p -> apps.find { it.component.packageName == p }?.component?.flattenToString() }.toMutableList()
        return prefs.getString("pins", "")!!.split('|').filter { it.isNotEmpty() }.toMutableList()
    }
    private fun dockOrder(): List<String> = prefs.getString("appOrder", null)?.split('|')?.filter { it.isNotBlank() } ?: pins()
    private fun currentOrder(): List<String> = (0 until row.childCount).map { row.getChildAt(it).tag as String }
    private fun rebuildDock() {
        val old = currentFocus?.tag as? String
        row.removeAllViews()
        shortcuts.removeAllViews()
        val savedKeys = dockOrder()
        val savedApps = savedKeys.mapNotNull { key -> apps.find { it.component.flattenToString() == key } }.distinctBy { it.component }
        val hidden = prefs.getStringSet("hiddenApps", emptySet()).orEmpty()
        val ordered = (savedApps + apps.filter { it.component.flattenToString() !in savedKeys }).filter { it.component.flattenToString() !in hidden }
        ordered.forEach { app ->
            val key = app.component.flattenToString()
            dockItem(appLabel(app), app, key, { launch(app) }, { beginMove(key) })
        }
        dockItem("Aplikacje", null, "all", { allApps() }, null, shortcuts)
        dockItem("Ustawienia", null, "settings", { settings() }, null, shortcuts)
        connectDockNavigation(old)
    }
    private fun connectDockNavigation(restoreKey: String?) {
        val all = shortcuts.getChildAt(0)
        val settings = shortcuts.getChildAt(1)
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i); v.nextFocusUpId = hdmi.id
            v.nextFocusDownId = all.id
            v.nextFocusLeftId = row.getChildAt((i - 1).coerceAtLeast(0)).id
            v.nextFocusRightId = if (i < row.childCount - 1) row.getChildAt(i + 1).id else all.id
        }
        all.nextFocusLeftId = row.getChildAt(0)?.id ?: all.id
        all.nextFocusRightId = settings.id
        settings.nextFocusLeftId = all.id
        settings.nextFocusRightId = settings.id
        for (i in 0 until shortcuts.childCount) {
            val v = shortcuts.getChildAt(i)
            v.nextFocusUpId = row.getChildAt(0)?.id ?: hdmi.id
            v.nextFocusDownId = v.id
        }
        hdmi.nextFocusDownId = row.getChildAt(0)?.id ?: all.id
        val children = (0 until row.childCount).map { row.getChildAt(it) } + (0 until shortcuts.childCount).map { shortcuts.getChildAt(it) }
        children.find { it.tag == restoreKey }?.requestFocus()
        if (row.indexOfChild(currentFocus) >= 0) {
            val target = currentFocus!!.id
            all.nextFocusLeftId = target
            for (i in 0 until shortcuts.childCount) shortcuts.getChildAt(i).nextFocusUpId = target
            hdmi.nextFocusDownId = target
        }
        updateDockPosition()
    }
    private fun updateDockPosition() {
        val focused = currentFocus
        val index = (0 until row.childCount).firstOrNull { row.getChildAt(it) == focused }
        dockPosition.text = if (movingKey != null) "PRZESUŃ  ← →  ·  OK zapisz  ·  Wstecz anuluj" else if (index != null) "←  ${index + 1} / ${row.childCount}  →  ·  ↓ opcje" else when (focused?.tag) { "all" -> "Aplikacje"; "settings" -> "Ustawienia"; else -> "← → aplikacje  ·  ↓ opcje" }
    }
    private fun dockItem(label: String, app: App?, key: String, click: () -> Unit, hold: (() -> Unit)?, parent: LinearLayout = row) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; tag = key; focusable(this) }
        column.contentDescription = label
        column.setOnFocusChangeListener { v, focus ->
            v.background = surface(focus)
            if (focus) {
                if (app != null && shortcuts.childCount > 0) {
                    shortcuts.getChildAt(0).nextFocusLeftId = v.id
                    for (i in 0 until shortcuts.childCount) shortcuts.getChildAt(i).nextFocusUpId = v.id
                    hdmi.nextFocusDownId = v.id
                }
                updateDockPosition()
            }
        }
        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xD91B2838.toInt()) }
            setPadding(dp(5), dp(5), dp(5), dp(5))
            if (app != null) setImageBitmap(appIcon(app)) else setImageDrawable(getDrawable(if (key == "all") android.R.drawable.ic_menu_sort_by_size else android.R.drawable.ic_menu_preferences))
        }
        column.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
        column.addView(text(label, 10f).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(3), dp(7), dp(3), 0) }, LinearLayout.LayoutParams(-1, -2))
        column.setOnClickListener { click() }
        if (hold != null) column.setOnLongClickListener { hold(); true }
        parent.addView(column, LinearLayout.LayoutParams(dp(83), dp(78)).apply { setMargins(dp(4), 0, dp(4), 0) })
    }
    private fun appIcon(app: App): Bitmap {
        val key = app.component.flattenToString()
        iconCache.get(key)?.let { return it }
        val drawable = try { packageManager.getActivityIcon(app.component) } catch (_: Exception) { getDrawable(android.R.drawable.sym_def_app_icon)!! }
        val size = dp(38)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val ratio = minOf(size.toFloat() / width, size.toFloat() / height)
        val w = (width * ratio).roundToInt(); val h = (height * ratio).roundToInt()
        drawable.setBounds((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2)
        drawable.draw(Canvas(bitmap))
        iconCache.put(key, bitmap)
        return bitmap
    }
    private fun launch(app: App) { safeStart(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    private fun safeStart(intent: Intent): Boolean = try { startActivity(intent); true } catch (_: Exception) { toast("Ta funkcja nie jest dostępna na tym telewizorze"); false }
    private fun showDialog(builder: AlertDialog.Builder, wide: Boolean = false) {
        dialogDepth++; main.removeCallbacks(hideDock)
        builder.create().apply {
            setOnDismissListener { dialogDepth = (dialogDepth - 1).coerceAtLeast(0); resetIdle() }
            show()
            if (wide) window?.setLayout(minOf(dp(730), resources.displayMetrics.widthPixels - dp(48)), (resources.displayMetrics.heightPixels * 0.82f).roundToInt())
        }
    }
    private fun allApps() {
        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val columns = if (widthDp < 800) 4 else 5
        val cellWidth = (minOf(730, widthDp - 48) - 64) / columns - 6
        val grid = GridLayout(this).apply { columnCount = columns; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val hidden = prefs.getStringSet("hiddenApps", emptySet()).orEmpty()
        for (app in apps) {
            val key = app.component.flattenToString()
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; focusable(this); setPadding(dp(6), dp(8), dp(6), dp(8)) }
            cell.contentDescription = appLabel(app)
            cell.addView(ImageView(this).apply { setImageBitmap(appIcon(app)); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(42), dp(42)))
            cell.addView(text(appLabel(app) + if (key in hidden) " · ukryta" else "", 11f).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, dp(36)))
            cell.setOnClickListener { launch(app) }
            cell.setOnLongClickListener { appOptions(app); true }
            grid.addView(cell, GridLayout.LayoutParams().apply { width = dp(cellWidth); height = dp(100); setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        showDialog(AlertDialog.Builder(this).setTitle("Wszystkie aplikacje · przytrzymaj OK: opcje").setView(ScrollView(this).apply { addView(grid) }).setNegativeButton("Zamknij", null), true)
        grid.getChildAt(0)?.requestFocus()
    }
    private fun appOptions(app: App) {
        val key = app.component.flattenToString()
        val hidden = prefs.getStringSet("hiddenApps", emptySet()).orEmpty().toMutableSet()
        showDialog(AlertDialog.Builder(this).setTitle(appLabel(app)).setItems(arrayOf("Otwórz", "Przenieś na początek docka", "Zmień nazwę", if (key in hidden) "Pokaż w docku" else "Ukryj w docku", "Informacje o aplikacji")) { _, n ->
            when (n) {
                0 -> launch(app)
                1 -> { hidden.remove(key); prefs.edit().putStringSet("hiddenApps", hidden).apply(); moveToFront(key) }
                2 -> { val input = EditText(this).apply { setText(appLabel(app)); isSingleLine = true; filters = arrayOf(android.text.InputFilter.LengthFilter(32)); setSelectAllOnFocus(true); selectAll() }
                    showDialog(AlertDialog.Builder(this).setTitle("Nazwa w Ambient").setView(input).setPositiveButton("Zapisz") { _, _ -> prefs.edit().putString("name:$key", input.text.toString().trim()).apply(); rebuildDock() }.setNeutralButton("Oryginalna") { _, _ -> prefs.edit().remove("name:$key").apply(); rebuildDock() }.setNegativeButton("Anuluj", null)) }
                3 -> { if (!hidden.add(key)) hidden.remove(key); prefs.edit().putStringSet("hiddenApps", hidden).apply(); rebuildDock(); toast("Zmieniono widoczność w docku. Aplikacja pozostaje na liście wszystkich aplikacji.") }
                4 -> safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.component.packageName}")))
            }
        })
    }
    private fun editPin(key: String) {
        showDialog(AlertDialog.Builder(this).setTitle("Opcje aplikacji").setItems(arrayOf("Przesuwaj lewo/prawo", "Przenieś na początek", "Nazwa i widoczność", "Ustawienia launchera")) { _, n ->
            when (n) {
                0 -> beginMove(key)
                1 -> moveToFront(key)
                2 -> apps.find { it.component.flattenToString() == key }?.let { appOptions(it) }
                3 -> settings()
            }
        })
    }
    private fun moveToFront(key: String) {
        val keys = currentOrder().toMutableList(); keys.remove(key); keys.add(0, key)
        prefs.edit().putString("appOrder", keys.joinToString("|")).apply()
        rebuildDock(); focusDockItem(key)
    }
    private fun beginMove(key: String) {
        if ((0 until row.childCount).none { row.getChildAt(it).tag == key }) return
        for (i in 0 until row.childCount) row.getChildAt(i).isPressed = false
        movingKey = key; suppressMoveRelease = true
        main.removeCallbacks(hideDock)
        focusDockItem(key); updateDockPosition()
    }
    private fun moveSelected(direction: Int) {
        val selected = (0 until row.childCount).map { row.getChildAt(it) }.find { it.tag == movingKey } ?: return
        val current = row.indexOfChild(selected)
        val target = (current + direction).coerceIn(0, row.childCount - 1)
        if (target == current) return
        row.removeView(selected); row.addView(selected, target)
        connectDockNavigation(movingKey)
    }
    private fun finishMove(save: Boolean) {
        val key = movingKey ?: return
        if (save) prefs.edit().putString("appOrder", currentOrder().joinToString("|")).apply()
        movingKey = null; suppressMoveRelease = false
        if (!save) rebuildDock()
        focusDockItem(key); updateDockPosition(); resetIdle()
    }
    private fun focusDockItem(key: String) {
        showDock(false)
        main.post { if (!isDestroyed) (0 until row.childCount).map { row.getChildAt(it) }.find { it.tag == key }?.requestFocus() }
    }
    private fun settings() {
        showDialog(AlertDialog.Builder(this).setTitle("Ambient Home · Ustawienia").setItems(arrayOf("Tapety i albumy", "Dock i aplikacje", "Pogoda i miejscowość", "Ustawienia telewizora", "Przycisk HOME", "Debloat · narzędzia ADB", "Informacje i źródła")) { _, n ->
            when (n) {
                0 -> wallpaperSettings()
                1 -> dockSettings()
                2 -> cityDialog()
                3 -> safeStart(Intent(Settings.ACTION_SETTINGS))
                4 -> homeSettings()
                5 -> { if (debloatMenu == null) debloatMenu = DebloatMenu(this); debloatMenu?.show() }
                6 -> {
                    val source = selectedPhoto
                    showDialog(AlertDialog.Builder(this).setTitle("Ambient Home Universal 0.5 beta").setMessage("${album.list().count { it.category != "custom" }} fotografii offline, w tym 21 astro.\n\nAktualna fotografia: ${source?.title.orEmpty()}\n${source?.credit.orEmpty()}\n${source?.source.orEmpty()}\n\nESO: CC BY 4.0 · eso.org/public/outreach/copyright/\nZmiany: dopasowanie/kadrowanie do ekranu. Unsplash: unsplash.com/license.\n\nPogoda: Open-Meteo (CC BY 4.0), geokodowanie GeoNames. HTTPS ze współrzędnymi miasta, bez GPS i analityki.\n\nRAM i sieć: TV. CPU apl.: udział procesu, gdy CPU TV jest niedostępne.\n\nDługie OK: przesuwanie. Menu: nazwa i widoczność. QuickBars zachowuje własne skróty.").setPositiveButton("Zamknij", null))
                }
            }
        }.setNegativeButton("Zamknij", null))
    }
    private fun dockSettings() {
        showDialog(AlertDialog.Builder(this).setTitle("Dock i aplikacje").setItems(arrayOf("Wszystkie aplikacje · nazwy i widoczność", "Czas ukrycia docka", "Statystyki: " + if (prefs.getBoolean("stats", true)) "włączone" else "wyłączone")) { _, n ->
            when (n) {
                0 -> allApps()
                1 -> showDialog(AlertDialog.Builder(this).setTitle("Ukryj dock po").setSingleChoiceItems(arrayOf("10 sekund", "15 sekund", "30 sekund", "60 sekund", "Nigdy"), listOf(10, 15, 30, 60, 0).indexOf(prefs.getInt("idle", 15))) { d, index -> prefs.edit().putInt("idle", listOf(10, 15, 30, 60, 0)[index]).apply(); d.dismiss() })
                2 -> { val enabled = !prefs.getBoolean("stats", true); prefs.edit().putBoolean("stats", enabled).apply(); stats.visibility = if (enabled) View.VISIBLE else View.GONE }
            }
        }.setNegativeButton("Zamknij", null))
    }
    private fun homeSettings() {
        val resolved = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        val helper = HomeRedirectService.isActive()
        showDialog(AlertDialog.Builder(this).setTitle("Przycisk HOME")
            .setMessage(if (resolved == packageName) "Ambient jest domyślnym ekranem HOME.\n\nZmiana launchera i opcjonalne przekierowanie Google TV to osobne ustawienia. Ustawienie HOME nie uruchamia debloatu ani nie zmienia QuickBars." else "Systemowy HOME: ${resolved ?: "nieustalony"}.\n\nOpcjonalny helper reaguje na okna zainstalowanych launcherów, bez odczytu treści ani klawiszy. Stan: ${if (helper) "włączony" else "wyłączony"}.")
            .setNeutralButton("Wybór launchera HOME") { _, _ -> safeStart(Intent(Settings.ACTION_HOME_SETTINGS)) }
            .setPositiveButton(if (helper) "Wyłącz helper" else "Ustawienia dostępności") { _, _ ->
                if (helper) HomeRedirectService.disableFromApp()
                else safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.setNegativeButton("Zamknij", null))
    }
    private fun wallpaperSettings() {
        val mode = prefs.getString("photoMode", "astro")
        val interval = prefs.getInt("photoMinutes", 15)
        showDialog(AlertDialog.Builder(this).setTitle("Tapety i albumy").setItems(arrayOf("Wybierz fotografię · podgląd albumu", "Album: " + when (mode) { "astro" -> "Nocne niebo"; "nature" -> "Natura"; "custom" -> "Moje zdjęcia"; else -> "Wszystkie" }, "Zmieniaj: " + if (interval == 0) "stała fotografia" else if (interval == 1440) "codziennie" else "co $interval min", "Następna fotografia", "Dodaj zdjęcia z pliku / USB", "Usuń własną fotografię")) { _, n ->
            when (n) {
                0 -> photoGallery()
                1 -> showDialog(AlertDialog.Builder(this).setTitle("Album").setSingleChoiceItems(arrayOf("Nocne niebo · 21 fotografii", "Natura", "Wszystkie fotografie", "Moje zdjęcia"), listOf("astro", "nature", "all", "custom").indexOf(mode)) { d, index ->
                    val selected = listOf("astro", "nature", "all", "custom")[index]
                    if (selected == "custom" && album.list().none { it.category == "custom" }) { toast("Najpierw dodaj własne zdjęcia"); return@setSingleChoiceItems }
                    prefs.edit().putString("photoMode", selected).remove("photoChosen").apply(); invalidatePhoto(); d.dismiss()
                })
                2 -> showDialog(AlertDialog.Builder(this).setTitle("Częstotliwość zmiany").setSingleChoiceItems(arrayOf("Co 5 minut", "Co 15 minut", "Co 30 minut", "Co godzinę", "Raz dziennie", "Stała fotografia"), listOf(5,15,30,60,1440,0).indexOf(interval)) { d, index ->
                    prefs.edit().putInt("photoMinutes", listOf(5,15,30,60,1440,0)[index]).putString("photoChosen", selectedPhoto?.id).putLong("photoChosenSlot", Long.MIN_VALUE).apply(); invalidatePhoto(); d.dismiss()
                })
                3 -> nextPhoto()
                4 -> {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    val picker = packageManager.resolveActivity(intent, 0)?.activityInfo
                    if (picker == null || picker.packageName == "com.google.android.tv.frameworkpackagestubs") openPhotoTransfer()
                    else try { startActivityForResult(intent, 40) } catch (_: Exception) { openPhotoTransfer() }
                }
                5 -> {
                    val custom = album.list().filter { it.category == "custom" }
                    if (custom.isEmpty()) toast("Brak własnych fotografii") else photoGallery(custom, true)
                }
            }
        }.setNegativeButton("Zamknij", null))
    }
    private fun openPhotoTransfer() {
        val network = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val address = network.getLinkProperties(network.activeNetwork)?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress
        if (address == null) { toast("Połącz TV z siecią lokalną, aby dodać zdjęcia"); return }
        transferServer?.close()
        io.execute {
            try {
                val server = PhotoTransferServer(this, album) { p -> main.post {
                    if (!isDestroyed) { prefs.edit().putString("photoMode", "custom").putString("photoChosen", p.id).putLong("photoChosenSlot", photoSlot()).apply(); invalidatePhoto(); toast("Dodano fotografię do własnego albumu") }
                } }
                main.post {
                    if (isDestroyed || !visible) { server.close(); return@post }
                    transferServer = server
                    val message = "Na tym TV brak działającego systemowego wyboru plików. Otwórz ten adres na telefonie lub komputerze w tej samej sieci:\n\nhttp://$address:${server.port}/?token=${server.token}\n\nWybierz zdjęcia w przeglądarce. Transfer trafia bezpośrednio na TV, bez chmury. Zamknięcie okna lub opuszczenie Ambient kończy transfer."
                    dialogDepth++; main.removeCallbacks(hideDock)
                    transferDialog = AlertDialog.Builder(this).setTitle("Dodaj własne zdjęcia · transfer lokalny").setMessage(message).setNegativeButton("Zakończ transfer", null).create().apply {
                        setOnDismissListener { server.close(); if (transferServer === server) transferServer = null; dialogDepth = (dialogDepth - 1).coerceAtLeast(0); resetIdle() }
                        show()
                    }
                }
            } catch (_: Exception) { main.post { toast("Nie można rozpocząć transferu w sieci lokalnej") } }
        }
    }
    private fun photoGallery(choices: List<WallAlbum.Photo> = album.list(), delete: Boolean = false) {
        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val columns = if (widthDp < 800) 3 else 4
        val cellWidth = (minOf(730, widthDp - 48) - 56) / columns - 6
        val grid = GridLayout(this).apply { columnCount = columns; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val dialog = AlertDialog.Builder(this).setTitle(if (delete) "Wybierz zdjęcie do usunięcia" else "Wybierz tapetę · OK ustawia od razu").setView(ScrollView(this).apply { addView(grid) }).setNegativeButton("Zamknij", null).create()
        for (p in choices) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; focusable(this); setPadding(dp(5), dp(5), dp(5), dp(5)); contentDescription = p.title }
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            cell.addView(image, LinearLayout.LayoutParams(-1, dp(74)))
            cell.addView(text(p.title, 10f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, dp(32)))
            thumbs.get(p.id)?.let { image.setImageBitmap(it) } ?: io.execute {
                val bitmap = album.decode(p, true)
                if (bitmap != null) { thumbs.put(p.id, bitmap); main.post { if (!isDestroyed && dialog.isShowing) image.setImageBitmap(bitmap) } }
            }
            cell.setOnClickListener {
                if (delete) showDialog(AlertDialog.Builder(this).setTitle("Usunąć kopię z Ambient?").setMessage("Oryginalny plik na USB lub w pamięci pozostanie bez zmian.").setPositiveButton("Usuń") { _, _ -> album.delete(p); thumbs.remove(p.id); prefs.edit().remove("photoChosen").apply(); invalidatePhoto(); dialog.dismiss() }.setNegativeButton("Anuluj", null))
                else { prefs.edit().putString("photoChosen", p.id).putLong("photoChosenSlot", photoSlot()).apply(); invalidatePhoto(); dialog.dismiss(); toast(if (prefs.getInt("photoMinutes", 15) == 0) "Ustawiono stałą tapetę" else "Tapeta ustawiona do następnej automatycznej zmiany") }
            }
            grid.addView(cell, GridLayout.LayoutParams().apply { width = dp(cellWidth); height = dp(120); setMargins(dp(3),dp(3),dp(3),dp(3)) })
        }
        dialogDepth++; main.removeCallbacks(hideDock)
        dialog.setOnDismissListener { dialogDepth = (dialogDepth - 1).coerceAtLeast(0); resetIdle() }
        dialog.show(); dialog.window?.setLayout(minOf(dp(730), resources.displayMetrics.widthPixels - dp(48)), (resources.displayMetrics.heightPixels * 0.82).roundToInt())
        grid.getChildAt(0)?.requestFocus()
    }
    @Deprecated("Activity result compatibility with Android TV 8+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 40 || resultCode != RESULT_OK || data == null) return
        val clip = data.clipData
        val uris = if (clip != null) (0 until minOf(20, clip.itemCount)).map { clip.getItemAt(it).uri } else listOfNotNull(data.data)
        toast("Dodawanie ${uris.size} zdjęć…")
        io.execute {
            var count = 0; var last: WallAlbum.Photo? = null; var error = ""
            for (uri in uris) try { last = album.importPhoto(uri); count++ } catch (e: Exception) { error = e.message.orEmpty() }
            main.post { if (!isDestroyed) {
                if (last != null) { prefs.edit().putString("photoMode", "custom").putString("photoChosen", last?.id).putLong("photoChosenSlot", photoSlot()).apply(); invalidatePhoto() }
                toast("Dodano $count z ${uris.size} zdjęć" + if (error.isNotEmpty()) ". $error" else "")
            } }
        }
    }

    private fun cityDialog() {
        val input = EditText(this).apply { setText(prefs.getString("city", "")); isSingleLine = true; selectAll() }
        showDialog(AlertDialog.Builder(this).setTitle("Miejscowość, opcjonalnie kraj").setView(input).setPositiveButton("Szukaj") { _, _ ->
            val query = input.text.toString().trim()
            if (query.length >= 2) geocode(query) else toast("Wpisz co najmniej 2 znaki")
        }.setNegativeButton("Anuluj", null))
    }
    private fun geocode(query: String) {
        toast("Szukam miejscowości…")
        io.execute {
            try {
                val array = json("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(query, "UTF-8")}&count=6&language=pl&format=json").optJSONArray("results")
                val results = if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }
                main.post {
                    if (isDestroyed || !visible) return@post
                    if (results.isEmpty()) toast("Nie znaleziono miejscowości") else showDialog(AlertDialog.Builder(this).setTitle("Wybierz miejscowość").setItems(results.map { "${it.getString("name")}, ${it.optString("admin1")}, ${it.optString("country")}" }.toTypedArray()) { _, n ->
                        val result = results[n]
                        prefs.edit().putString("city", result.getString("name")).putString("lat", result.getDouble("latitude").toString()).putString("lon", result.getDouble("longitude").toString()).remove("weather").remove("weatherTime").apply()
                        nextWeatherTry = 0; refreshWeather()
                    })
                }
            } catch (_: Exception) { main.post { if (!isDestroyed) toast("Nie udało się wyszukać. Sprawdź internet.") } }
        }
    }
    private fun json(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000; connection.readTimeout = 8000
        connection.setRequestProperty("User-Agent", "AmbientHome/0.1 personal Android TV")
        try {
            check(connection.responseCode == 200)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            check(body.length < 256000)
            return JSONObject(body)
        } finally { connection.disconnect() }
    }
    private fun refreshWeather() {
        if (!prefs.contains("lat") || !prefs.contains("lon")) { weather.text = "Pogoda · wybierz miejscowość w ustawieniach"; return }
        val cached = prefs.getString("weather", null)
        val age = System.currentTimeMillis() - prefs.getLong("weatherTime", 0)
        weather.text = if (cached != null) cached + if (age > 3600000) " · zapisane ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(prefs.getLong("weatherTime", 0)))}" else "" else "${prefs.getString("city", "")} · pogoda —"
        if (weatherBusy || (cached != null && age in 0..1799999) || SystemClock.elapsedRealtime() < nextWeatherTry) return
        weatherBusy = true
        val lat = prefs.getString("lat", "")!!; val lon = prefs.getString("lon", "")!!
        val city = prefs.getString("city", "")!!
        io.execute {
            var result: String? = null
            try {
                val c = json("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto").getJSONObject("current")
                val code = c.getInt("weather_code")
                val description = when (code) { 0 -> "bezchmurnie"; 1, 2 -> "przejaśnienia"; 3 -> "pochmurno"; 45, 48 -> "mgła"; in 51..67 -> "deszcz"; in 71..77 -> "śnieg"; in 80..82 -> "przelotny deszcz"; 85, 86 -> "przelotny śnieg"; in 95..99 -> "burza"; else -> "pogoda" }
                result = "$city   ${c.getDouble("temperature_2m").roundToInt()}°  ·  $description"
            } catch (_: Exception) { /* Keep cached data, never replace it with invented values. */ }
            val finalResult = result
            main.post {
                weatherBusy = false
                if (isDestroyed) return@post
                // Ignore results requested for a location that has since changed.
                if (lat != prefs.getString("lat", "") || lon != prefs.getString("lon", "")) { nextWeatherTry = 0; if (visible) refreshWeather(); return@post }
                nextWeatherTry = SystemClock.elapsedRealtime() + if (finalResult == null) 300000 else 1800000
                if (finalResult != null) prefs.edit().putString("weather", finalResult).putLong("weatherTime", System.currentTimeMillis()).apply()
                if (visible) { weather.text = finalResult ?: (prefs.getString("weather", null)?.plus(" · offline") ?: "$city · pogoda niedostępna") }
            }
        }
    }
    private fun photoSlot(): Long {
        val minutes = prefs.getInt("photoMinutes", 15)
        return when (minutes) { 0 -> 0L; 1440 -> LocalDate.now().toEpochDay(); else -> Math.floorDiv(System.currentTimeMillis(), minutes * 60000L) }
    }
    private fun photoChoices(): List<WallAlbum.Photo> {
        val all = album.list()
        val mode = prefs.getString("photoMode", "astro")
        return all.filter { mode == "all" || it.category == mode }.ifEmpty { all.filter { it.category == "astro" } }
            .shuffled(java.util.Random(81723))
    }
    private fun invalidatePhoto() { photoToken = ""; photoGeneration++; loadDailyPhoto() }
    private fun nextPhoto() {
        val choices = photoChoices()
        val index = choices.indexOfFirst { it.id == selectedPhoto?.id }
        val next = choices[(index + 1) % choices.size]
        prefs.edit().putString("photoChosen", next.id).putLong("photoChosenSlot", photoSlot()).apply(); invalidatePhoto()
    }
    private fun loadDailyPhoto() {
        main.removeCallbacks(photoTick)
        if (!visible) return
        val minutes = prefs.getInt("photoMinutes", 15)
        if (minutes > 0) {
            val delay = if (minutes == 1440) java.time.Duration.between(java.time.ZonedDateTime.now(), LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault())).toMillis() else minutes * 60000L - Math.floorMod(System.currentTimeMillis(), minutes * 60000L)
            main.postDelayed(photoTick, delay.coerceAtLeast(1000))
        }
        val choices = photoChoices()
        val slot = photoSlot()
        val chosenId = prefs.getString("photoChosen", null)
        val manual = if (minutes == 0 || prefs.getLong("photoChosenSlot", Long.MIN_VALUE) == slot) album.list().find { it.id == chosenId } else null
        val selected = manual ?: choices[Math.floorMod(slot, choices.size.toLong()).toInt()]
        val token = selected.id
        if (token == photoToken) return
        val generation = ++photoGeneration
        photoToken = token
        io.execute {
            val bitmap = album.decode(selected)
            main.post {
                if (isDestroyed || generation != photoGeneration) return@post
                if (bitmap != null) {
                    photo.setImageBitmap(bitmap); photoBitmap = bitmap; selectedPhoto = selected
                    credit.text = "${selected.credit}  ·  ${if (selected.category == "astro") "CC BY 4.0 · ESO" else if (selected.category == "custom") "MOJA KOLEKCJA" else "UNSPLASH"}"
                } else { photoToken = ""; toast("Nie można wczytać fotografii") }
            }
        }
    }

    private fun cpu(): String {
        if (procReadable) {
            try {
                val v = File("/proc/stat").bufferedReader().use { it.readLine() }.trim().split(Regex("\\s+")).drop(1).take(8).map { it.toLong() }
                val sample = longArrayOf(v.sum(), v[3] + v[4]); val previous = lastCpu; lastCpu = sample
                if (previous != null && sample[0] > previous[0]) return "CPU TV ${((1.0 - (sample[1] - previous[1]).toDouble() / (sample[0] - previous[0])) * 100).coerceIn(0.0, 100.0).roundToInt()}%"
                return "CPU TV —"
            } catch (_: Exception) { procReadable = false }
        }
        val now = SystemClock.elapsedRealtime(); val app = Process.getElapsedCpuTime()
        val value = if (lastAppTime > 0 && now > lastAppTime) String.format(Locale.US, "%.1f%%", (100.0 * (app - lastAppCpu) / (now - lastAppTime) / Runtime.getRuntime().availableProcessors()).coerceIn(0.0, 100.0)) else "—"
        lastAppTime = now; lastAppCpu = app
        return "CPU apl. $value"
    }
    private fun updateStats() {
        if (!prefs.getBoolean("stats", true)) { stats.visibility = View.GONE; return }
        stats.visibility = View.VISIBLE
        val mem = ActivityManager.MemoryInfo(); (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
        val ram = "RAM ${(mem.totalMem - mem.availMem) / 1048576} / ${mem.totalMem / 1048576} MB"
        val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes(); val now = SystemClock.elapsedRealtime()
        val connected = (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork != null
        val net = if (!connected) "Sieć offline" else if (rx < 0 || tx < 0) "Sieć · transfer niedostępny" else if (lastNet[2] > 0 && now > lastNet[2] && rx >= lastNet[0] && tx >= lastNet[1]) "↓ ${speed((rx - lastNet[0]) * 1000.0 / (now - lastNet[2]))}   ↑ ${speed((tx - lastNet[1]) * 1000.0 / (now - lastNet[2]))}" else "Sieć · pomiar…"
        lastNet = longArrayOf(rx, tx, now)
        stats.text = "$ram\n${cpu()}\n$net"
    }
    private fun speed(bytes: Double) = if (bytes >= 1048576) String.format(Locale.US, "%.1f MB/s", bytes / 1048576) else "${(bytes / 1024).roundToInt()} KB/s"
    private fun showInputs() { InputMenu(this).show() }
    private fun resetIdle() {
        main.removeCallbacks(hideDock)
        if (dock.visibility == View.VISIBLE) { dock.animate().cancel(); dock.alpha = 1f }
        if (visible && dialogDepth == 0 && movingKey == null && prefs.getInt("idle", 15) > 0) main.postDelayed(hideDock, prefs.getInt("idle", 15) * 1000L)
    }
    private fun showDock(focus: Boolean) {
        dock.animate().cancel(); dock.visibility = View.VISIBLE; dock.alpha = 1f
        dockPosition.visibility = View.VISIBLE
        if (focus) (row.getChildAt(0) ?: shortcuts.getChildAt(0))?.requestFocus()
        updateDockPosition()
        resetIdle()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (dialogDepth > 0) return super.dispatchKeyEvent(event)
        if (event.keyCode == wakeKey) {
            if (event.action == KeyEvent.ACTION_UP) wakeKey = -1
            return true
        }
        val navigation = event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU)
        if (!navigation) return super.dispatchKeyEvent(event)
        if (dock.visibility != View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN) { wakeKey = event.keyCode; showDock(true) }
            return true
        }
        if (movingKey != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.action == KeyEvent.ACTION_DOWN) moveSelected(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) suppressMoveRelease = false
                    if (event.action == KeyEvent.ACTION_UP) { if (suppressMoveRelease) suppressMoveRelease = false else finishMove(true) }
                }
                KeyEvent.KEYCODE_BACK -> if (event.action == KeyEvent.ACTION_UP) finishMove(false)
            }
            return true
        }
        resetIdle()
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
            val key = currentFocus?.tag as? String
            if (key != null && key != "all" && key != "settings") editPin(key) else settings()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) { dock.animate().cancel(); dock.visibility = View.INVISIBLE; dockPosition.visibility = View.INVISIBLE; root.requestFocus() }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    private fun toast(message: String) { if (!isDestroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}




