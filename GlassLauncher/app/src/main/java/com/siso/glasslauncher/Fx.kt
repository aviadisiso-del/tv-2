package com.siso.glasslauncher

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.Equalizer
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ln

/** Created before any activity or service, so everything shares one set of settings. */
class GlassApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Fx.init(this)
    }
}

object Fx {
    lateinit var sp: SharedPreferences
        private set

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("glass_fx", Context.MODE_PRIVATE)
        AudioFx.load()
        DisplayFx.load()
        RemoteKeys.load()
        animations = sp.getBoolean("animations", true)
    }

    var animations by mutableStateOf(true)
        private set

    fun setAnimations(v: Boolean) {
        animations = v
        sp.edit().putBoolean("animations", v).apply()
    }
}

// ───────────────────────── Equalizer ─────────────────────────

class EqPreset(val id: String, val name: String, val gains: List<Float>, val custom: Boolean)

/**
 * Every preset is 5 gains (dB) at fixed reference frequencies. They are mapped onto
 * whatever bands the device's equalizer really has, so one preset works on any box.
 */
object AudioFx {
    val bandFreqs = listOf(60.0, 230.0, 910.0, 3600.0, 14000.0)
    val bandLabels = listOf("60", "230", "910", "3.6k", "14k")
    const val MIN_DB = -12f
    const val MAX_DB = 12f

    val builtins = listOf(
        EqPreset("flat", "שטוח (ללא שינוי)", listOf(0f, 0f, 0f, 0f, 0f), false),
        EqPreset("bass", "בס חזק", listOf(7f, 5f, 1f, 0f, 0f), false),
        EqPreset("treble", "צלילים גבוהים", listOf(0f, 0f, 0f, 3f, 6f), false),
        EqPreset("speech", "דיבור ברור", listOf(-3f, -1f, 3f, 4f, 1f), false),
        EqPreset("cinema", "קולנוע", listOf(5f, 3f, 0f, 1f, 3f), false),
        EqPreset("music", "מוזיקה", listOf(5f, 2f, -1f, 2f, 5f), false),
        EqPreset("night", "לילה (שקט)", listOf(-5f, -2f, 1f, 2f, 0f), false),
    )

    var customs by mutableStateOf<List<EqPreset>>(emptyList())
        private set
    var enabled by mutableStateOf(true)
        private set
    var selectedId by mutableStateOf("flat")
        private set
    var status by mutableStateOf("")
        private set

    val all: List<EqPreset> get() = builtins + customs
    val current: EqPreset get() = all.firstOrNull { it.id == selectedId } ?: builtins[0]

    private var globalEq: Equalizer? = null
    private val sessionEqs = HashMap<Int, Equalizer>()

    fun load() {
        val sp = Fx.sp
        enabled = sp.getBoolean("eq_on", true)
        selectedId = sp.getString("eq_sel", "flat") ?: "flat"
        customs = (sp.getString("eq_customs", "") ?: "").split("\n").mapNotNull { line ->
            val p = line.split("|")
            if (p.size != 3) {
                null
            } else {
                val g = p[2].split(",").mapNotNull { it.toFloatOrNull() }
                if (g.size == 5) EqPreset(p[0], p[1], g, true) else null
            }
        }
    }

    private fun saveCustoms() {
        val text = customs.joinToString("\n") {
            it.id + "|" + it.name.replace("|", " ").replace("\n", " ") + "|" + it.gains.joinToString(",")
        }
        Fx.sp.edit().putString("eq_customs", text).apply()
    }

    fun select(id: String) {
        selectedId = id
        Fx.sp.edit().putString("eq_sel", id).apply()
        apply()
    }

    fun setEnabled(v: Boolean) {
        enabled = v
        Fx.sp.edit().putBoolean("eq_on", v).apply()
        apply()
    }

    /** Copies [from] into a new custom preset ("מותאם 1", "מותאם 2"…) and selects it. */
    fun newCustom(from: EqPreset): EqPreset {
        var n = customs.size + 1
        while (customs.any { it.name == "מותאם $n" }) n++
        val p = EqPreset("c" + System.currentTimeMillis(), "מותאם $n", from.gains, true)
        customs = customs + p
        saveCustoms()
        select(p.id)
        return p
    }

    fun setGain(id: String, band: Int, db: Float) {
        customs = customs.map {
            if (it.id == id) {
                val g = it.gains.toMutableList()
                g[band] = db.coerceIn(MIN_DB, MAX_DB)
                EqPreset(it.id, it.name, g, true)
            } else it
        }
        saveCustoms()
        if (selectedId == id) apply()
    }

    fun delete(id: String) {
        customs = customs.filter { it.id != id }
        saveCustoms()
        if (selectedId == id) select("flat")
    }

    private fun gainAt(hz: Double, gains: List<Float>): Float {
        val x = ln(hz.coerceAtLeast(20.0))
        val f = bandFreqs.map { ln(it) }
        if (x <= f.first()) return gains.first()
        if (x >= f.last()) return gains.last()
        for (i in 0 until f.size - 1) {
            if (x <= f[i + 1]) {
                val t = ((x - f[i]) / (f[i + 1] - f[i])).toFloat()
                return gains[i] + (gains[i + 1] - gains[i]) * t
            }
        }
        return gains.last()
    }

    private fun configure(e: Equalizer, gains: List<Float>) {
        val range = e.bandLevelRange
        val lo = range[0].toInt()
        val hi = range[1].toInt()
        for (b in 0 until e.numberOfBands.toInt()) {
            val hz = e.getCenterFreq(b.toShort()) / 1000.0 // milliHertz -> Hz
            val mb = (gainAt(hz, gains) * 100f).toInt().coerceIn(lo, hi)
            e.setBandLevel(b.toShort(), mb.toShort())
        }
    }

    /** (Re)applies the selected preset. Safe to call at any time, from any process thread that has a Looper. */
    fun apply() {
        val gains = if (enabled) current.gains else listOf(0f, 0f, 0f, 0f, 0f)
        try {
            val e = globalEq ?: Equalizer(1000, 0).also { globalEq = it }
            e.setEnabled(enabled)
            configure(e, gains)
            status = if (enabled) "האקולייזר פעיל: ${current.name}" else "האקולייזר כבוי"
        } catch (t: Throwable) {
            globalEq = null
            status = "אקולייזר כללי לא נתמך בסטרימר הזה. הוא יעבוד רק באפליקציות ששולחות session (למשל Spotify)."
        }
        for (e in sessionEqs.values) {
            try {
                e.setEnabled(enabled)
                configure(e, gains)
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    /** Fallback for apps that announce their audio session (Spotify, YouTube Music, …). */
    fun onSessionOpen(session: Int) {
        if (session <= 0 || globalEq != null || sessionEqs.containsKey(session)) return
        try {
            val e = Equalizer(0, session)
            sessionEqs[session] = e
            e.setEnabled(enabled)
            configure(e, if (enabled) current.gains else listOf(0f, 0f, 0f, 0f, 0f))
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun onSessionClose(session: Int) {
        val e = sessionEqs.remove(session) ?: return
        try {
            e.release()
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun release() {
        try {
            globalEq?.release()
        } catch (t: Throwable) {
            // ignore
        }
        globalEq = null
        for (e in sessionEqs.values) {
            try {
                e.release()
            } catch (t: Throwable) {
                // ignore
            }
        }
        sessionEqs.clear()
    }
}

// ───────────────────────── Display profiles ─────────────────────────

class DisplayProfile(
    val id: String,
    val name: String,
    val note: String,
    val dim: Int,   // 0..70  (% black)
    val warm: Int,  // 0..60  (% warm tint)
    val custom: Boolean,
)

/**
 * A streamer cannot change the TV panel's own picture processing (contrast, sharpness, local dimming…).
 * What it CAN do is put a transparent layer over everything it outputs: dim it and warm it up.
 */
object DisplayFx {
    val builtins = listOf(
        DisplayProfile("std", "רגיל", "ללא שינוי", 0, 0, false),
        DisplayProfile("cinema", "קולנוע", "גוון חם ועמעום קל", 8, 14, false),
        DisplayProfile("evening", "ערב", "פחות אור כחול", 10, 28, false),
        DisplayProfile("night", "לילה", "עמעום חזק וגוון חם", 45, 30, false),
        DisplayProfile("eco", "חיסכון באנרגיה", "עמעום בינוני", 25, 0, false),
    )

    var customs by mutableStateOf<List<DisplayProfile>>(emptyList())
        private set
    var selectedId by mutableStateOf("std")
        private set
    var serviceActive by mutableStateOf(false)

    val all: List<DisplayProfile> get() = builtins + customs
    val current: DisplayProfile get() = all.firstOrNull { it.id == selectedId } ?: builtins[0]

    fun load() {
        val sp = Fx.sp
        selectedId = sp.getString("dp_sel", "std") ?: "std"
        customs = (sp.getString("dp_customs", "") ?: "").split("\n").mapNotNull { line ->
            val p = line.split("|")
            val dim = p.getOrNull(2)?.toIntOrNull()
            val warm = p.getOrNull(3)?.toIntOrNull()
            if (p.size == 4 && dim != null && warm != null) {
                DisplayProfile(p[0], p[1], "פרופיל מותאם אישית", dim, warm, true)
            } else null
        }
    }

    private fun saveCustoms() {
        val text = customs.joinToString("\n") {
            it.id + "|" + it.name.replace("|", " ").replace("\n", " ") + "|" + it.dim + "|" + it.warm
        }
        Fx.sp.edit().putString("dp_customs", text).apply()
    }

    fun select(id: String) {
        selectedId = id
        Fx.sp.edit().putString("dp_sel", id).apply()
        apply()
    }

    fun newCustom(from: DisplayProfile): DisplayProfile {
        var n = customs.size + 1
        while (customs.any { it.name == "מותאם $n" }) n++
        val p = DisplayProfile("d" + System.currentTimeMillis(), "מותאם $n", "פרופיל מותאם אישית", from.dim, from.warm, true)
        customs = customs + p
        saveCustoms()
        select(p.id)
        return p
    }

    fun setValues(id: String, dim: Int, warm: Int) {
        customs = customs.map {
            if (it.id == id) DisplayProfile(it.id, it.name, it.note, dim.coerceIn(0, 70), warm.coerceIn(0, 60), true) else it
        }
        saveCustoms()
        if (selectedId == id) apply()
    }

    fun delete(id: String) {
        customs = customs.filter { it.id != id }
        saveCustoms()
        if (selectedId == id) select("std")
    }

    fun apply() {
        val p = current
        val svc = KeyService.instance
        serviceActive = svc != null
        svc?.setTint(p.dim, p.warm)
    }
}

// ───────────────────────── Remote control keys ─────────────────────────

enum class RemoteAction(val label: String, val hint: String) {
    SETTINGS("לחצן ההגדרות ⚙", "לחיצה קצרה: הגדרות האפליקציה · לחיצה ארוכה: הגדרות אנדרואיד"),
    QUICK("לחצן הטלוויזיה 📺", "פותח את התפריט המהיר: תצוגה וסאונד"),
    DISPLAY("פרופילי תצוגה", "פותח ישר את פרופילי התצוגה"),
    SOUND("אקולייזר", "פותח ישר את האקולייזר"),
}

object RemoteKeys {
    private val defaults: Map<Int, RemoteAction> = mapOf(
        KeyEvent.KEYCODE_SETTINGS to RemoteAction.SETTINGS,
        KeyEvent.KEYCODE_TV to RemoteAction.QUICK,
        KeyEvent.KEYCODE_TV_INPUT to RemoteAction.QUICK,
    )

    /** Keys we never allow to be re-assigned, so nobody can lock themselves out of the remote. */
    private val protectedKeys = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
    )

    var map by mutableStateOf(defaults)
        private set
    var learning by mutableStateOf(false)
        private set
    var learnTarget by mutableStateOf<RemoteAction?>(null)
        private set
    var message by mutableStateOf("")
        private set

    fun load() {
        val raw = Fx.sp.getString("keys", null) ?: return
        val m = HashMap<Int, RemoteAction>()
        for (part in raw.split(",")) {
            val kv = part.split(":")
            if (kv.size != 2) continue
            val code = kv[0].toIntOrNull() ?: continue
            val action = RemoteAction.values().firstOrNull { it.name == kv[1] } ?: continue
            m[code] = action
        }
        if (m.isNotEmpty()) map = m
    }

    private fun save() {
        Fx.sp.edit().putString("keys", map.entries.joinToString(",") { "${it.key}:${it.value.name}" }).apply()
    }

    fun actionFor(code: Int): RemoteAction? = map[code]

    fun keysFor(a: RemoteAction): List<Int> = map.filterValues { it == a }.keys.toList()

    fun nameOf(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_") + " ($code)"

    fun startLearning(a: RemoteAction) {
        learnTarget = a
        learning = true
        message = "לחץ עכשיו על הלחצן בשלט…"
    }

    fun cancelLearning() {
        learning = false
        learnTarget = null
    }

    /** Called with the next key pressed while [learning]. Returns true if the key was consumed. */
    fun learned(code: Int): Boolean {
        if (!learning) return false
        val target = learnTarget
        if (target == null) {
            cancelLearning()
            return false
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            message = "הלימוד בוטל"
            cancelLearning()
            return true
        }
        if (code in protectedKeys) {
            message = "הלחצן הזה שמור למערכת. נסה לחצן אחר."
            return true
        }
        map = map.filterValues { it != target } + (code to target)
        save()
        message = "נשמר: ${nameOf(code)} ← ${target.label}"
        cancelLearning()
        return true
    }

    fun reset() {
        map = defaults
        save()
        message = "הלחצנים אופסו לברירת המחדל"
    }
}
