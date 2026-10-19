package hu.sx4.diagnosztika

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private val elm = Elm327()
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val generation = AtomicInteger()
    @Volatile private var measuring = false
    @Volatile private var active = false
    @Volatile private var poll: ScheduledFuture<*>? = null
    private val readingGeneration = AtomicInteger()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connecting = false
    private var operation = false
    private var clearDialog: AlertDialog? = null
    private lateinit var status: TextView
    private lateinit var values: TextView
    private lateinit var errors: TextView
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var demo: Switch
    /** Latest decoded line per PID with its receive time; read and written on the UI thread only. */
    private val latest = LinkedHashMap<Int, Pair<String, Long>>()
    private val cm by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 30, 28, 30) }
        scroll.addView(root); setContentView(scroll)
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(28 + insets.systemWindowInsetLeft, 30 + insets.systemWindowInsetTop,
                28 + insets.systemWindowInsetRight, 30 + insets.systemWindowInsetBottom)
            insets
        }
        fun title(s: String) = TextView(this).apply { text = s; textSize = 22f; setTextColor(Color.rgb(20,55,72)); setPadding(0,18,0,12); root.addView(this) }
        fun button(s: String, action: () -> Unit) = Button(this).apply { text = s; setOnClickListener { action() }; root.addView(this) }
        fun field(value: String, hint: String) = EditText(this).apply { setText(value); this.hint = hint; setSingleLine(true); root.addView(this) }
        title("SX4 Diagnosztika Pro")
        TextView(this).apply {
            text = "Suzuki SX4 2007 • M15A • v${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})\n" +
                "Build ${BuildConfig.BUILD_NUMBER} • commit ${BuildConfig.GIT_SHA} • ${BuildConfig.BUILD_TYPE}"
            root.addView(this)
        }
        status = TextView(this).apply { text = "DEMO mód • nincs ECU-kapcsolat"; textSize = 17f; root.addView(this) }
        host = field("192.168.0.10", "Adapter IPv4-címe")
        port = field("35000", "TCP-port").apply { inputType = 2 }
        demo = Switch(this).apply { text = "DEMO mód – szimulált adatok"; isChecked = true; root.addView(this) }
        button("Csatlakozás az adapterhez") { connectWifi() }
        button("Kapcsolat bontása") { resetSession("Kapcsolat bontva • a korábbi adatok érvénytelenek") }
        title("Élő motoradatok")
        values = TextView(this).apply { textSize = 17f; text = "Még nincs mérés"; root.addView(this) }
        button("Élő adatok indítása") { startReading() }
        button("Mérés leállítása") {
            measuring = false; readingGeneration.incrementAndGet(); poll?.cancel(false)
            values.text = "Mérés leállítva • a korábbi kijelzés érvénytelen"
        }
        title("Hibakód-diagnosztika")
        errors = TextView(this).apply { text = "Még nem történt kiolvasás"; textSize = 16f; root.addView(this) }
        button("Tárolt hibakódok olvasása") {
            if (demo.isChecked) errors.text = "DEMO / SZIMULÁLT\nP0420 – Katalizátor hatásfoka a küszöb alatt (1. hengersor)"
            else {
                errors.text = "VALÓDI ECU • hibakód-kiolvasás folyamatban…"
                task { token ->
                val codes = Obd.dtcs(elm.command("03"), elm.canProtocol)
                show(token) { errors.text = "VALÓDI ECU • tárolt hibakódok\n" + codes.ifEmpty { listOf("Nincs tárolt hibakód a sikeresen dekódolt válaszban") }.joinToString("\n") }
                }
            }
        }
        button("Hibakódok törlése") { prepareClear() }
        TextView(this).apply {
            text = "Fejlesztői prototípus. Álló járműnél használd. A hibakódtörlés nem javítja meg a hibát. ECU-paraméterírás és emissziófelügyelet-módosítás nincs beépítve. A törlés előtti mentés részleges, nem teljes járműdiagnosztikai mentés."
            setPadding(0,24,0,20); root.addView(this)
        }
        demo.setOnCheckedChangeListener { _, checked -> resetSession(if (checked) "DEMO mód • nincs ECU-kapcsolat" else "VALÓDI mód • előbb csatlakozz") }
    }

    private fun resetSession(message: String) {
        generation.incrementAndGet(); measuring = false; readingGeneration.incrementAndGet(); poll?.cancel(false)
        connecting = false; operation = false
        clearDialog?.dismiss(); clearDialog = null
        elm.disconnect()
        networkCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: IllegalArgumentException) {} }
        networkCallback = null
        status.text = message
        values.text = "Nincs érvényes mérés"
        errors.text = "Nincs érvényes hibakód-kiolvasás"
    }

    private fun connectWifi() {
        if (demo.isChecked) { status.text = "DEMO mód • nincs valódi ECU-kapcsolat"; return }
        if (connecting || operation) return
        val ip = host.text.toString().trim()
        val p = port.text.toString().toIntOrNull()
        if (!ip.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) || ip.split('.').any { it.toInt() !in 0..255 } || p == null || p !in 1..65535) {
            status.text = "Érvényes IPv4-cím és 1–65535 közötti port szükséges"; return
        }
        resetSession("Wi-Fi hálózat keresése… Csatlakozz az adapter hálózatához internet nélkül is.")
        connecting = true
        val token = generation.get()
        var selected: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = show(token) {
                if (selected != null) return@show
                selected = network
                status.text = "Wi-Fi TCP-kapcsolódás…"
                worker.execute {
                    if (token != generation.get()) return@execute
                    try {
                        val id = elm.connect(ip, p, { network.socketFactory.createSocket() }, { token != generation.get() })
                        show(token) { connecting = false; status.text = "VALÓDI • $id" }
                    } catch (e: Exception) {
                        show(token) { resetSession("Kapcsolódási hiba: ${e.message}") }
                    }
                }
            }
            override fun onLost(network: Network) = show(token) {
                if (network == selected) resetSession("Az adapter Wi-Fi-kapcsolata megszakadt")
            }
            override fun onUnavailable() = show(token) { resetSession("Nem érhető el Wi-Fi hálózat") }
        }
        networkCallback = callback
        try {
            // No INTERNET/VALIDATED requirement: mobile data can stay the default route.
            cm.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback, 10000)
        } catch (e: Exception) { resetSession("Wi-Fi kérési hiba: ${e.message}") }
    }

    private fun startReading() {
        if (demo.isChecked) {
            values.text = "DEMO / SZIMULÁLT\nFordulatszám: 780 1/perc\nHűtőfolyadék: 88 °C\nSebesség: 0 km/h\nSTFT: +3,1%\nLTFT: +5,5%\nFojtószelep: 12%"
            return
        }
        if (!elm.isConnected()) { status.text = "Előbb csatlakozz az adapterhez"; return }
        if (measuring || operation) return
        val supported = LivePlan.sequence(elm.supported)
        if (supported.isEmpty()) { values.text = "VALÓDI ECU • nincs támogatott kijelzési PID"; return }
        measuring = true
        latest.clear()
        val token = generation.get()
        scheduleReading(token, readingGeneration.incrementAndGet(), supported, 0)
    }

    private fun scheduleReading(token: Int, readingToken: Int, supported: List<Int>, index: Int) {
        poll = worker.schedule({
            if (!measuring || token != generation.get() || readingToken != readingGeneration.get()) return@schedule
            val pid = supported[index]
            try {
                val line = Obd.reading(pid, Obd.bytesFor(elm.command("01%02X".format(pid)), 1, pid))
                val at = System.currentTimeMillis()
                show(token) { if (measuring && readingToken == readingGeneration.get()) { latest[pid] = line to at; renderLatest(supported) } }
            } catch (e: Exception) {
                show(token) { if (measuring && readingToken == readingGeneration.get()) {
                    latest.remove(pid); renderLatest(supported, "%02X: nincs érvényes friss mérés • %s".format(pid, e.message))
                } }
                if (!elm.isConnected()) { show(token) { resetSession("A TCP-kapcsolat megszakadt: ${e.message}") }; return@schedule }
            }
            if (measuring && token == generation.get() && readingToken == readingGeneration.get()) scheduleReading(token, readingToken, supported, (index + 1) % supported.size)
        }, 200, TimeUnit.MILLISECONDS)
    }

    /** Each value keeps its own age; nothing older than its stale limit is shown as current. */
    private fun renderLatest(sequence: List<Int>, note: String? = null) {
        val now = System.currentTimeMillis()
        val lines = LivePlan.displayed(sequence.toSet()).map { pid ->
            val entry = latest[pid] ?: return@map "%02X: még nincs érvényes adat".format(pid)
            val age = now - entry.second
            if (age > LivePlan.staleAfterMs(pid)) "ELAVULT (${age / 1000} s): ${entry.first}" else "${entry.first}  (${age} ms)"
        }
        values.text = "VALÓDI ECU • PID-enként külön időpontban olvasva\n" + lines.joinToString("\n") + (note?.let { "\n$it" } ?: "")
    }

    /** Serialize operations with single-PID polls, never queue an infinite read loop. */
    private fun task(block: (Int) -> Unit) {
        if (!elm.isConnected() || demo.isChecked) { status.text = "Előbb csatlakozz valódi módban"; return }
        if (operation) { status.text = "Egy művelet már folyamatban van"; return }
        operation = true
        val token = generation.get()
        worker.execute {
            if (token != generation.get()) return@execute
            try { block(token) }
            catch (e: Exception) { show(token) {
                if (!elm.isConnected()) resetSession("Kapcsolati hiba: ${e.message}")
                else status.text = "Hiba: ${e.message}"
            } }
            finally { show(token) { operation = false } }
        }
    }

    private fun prepareClear() {
        if (demo.isChecked || !elm.isConnected()) { status.text = "Törléshez valódi ECU-kapcsolat szükséges"; return }
        measuring = false; readingGeneration.incrementAndGet(); poll?.cancel(false)
        values.text = "Mérés szünetel a törlés előkészítése miatt"
        task { token ->
            // Mandatory Mode 03 must decode; optional data is recorded with explicit per-query errors.
            val stored = elm.command("03")
            val codes = Obd.dtcs(stored, elm.canProtocol)
            val report = JSONObject().put("schema", 1).put("source", "REAL_ECU")
                .put("timestampUtcMillis", System.currentTimeMillis()).put("complete", false)
                .put("canProtocol", elm.canProtocol).put("appVersion", BuildConfig.VERSION_NAME)
                .put("appVersionCode", BuildConfig.VERSION_CODE).put("gitSha", BuildConfig.GIT_SHA)
                .put("limitations", "RÉSZLEGES: nem teljes Freeze Frame vagy ECU-mentés; csak a felsorolt lekérdezések.")
                .put("storedDtcRaw", stored).put("storedDtcDecoded", codes.joinToString(","))
            val queries = JSONObject()
            for (cmd in listOf("07", "0A", "0101", "020200", "020000")) {
                check(token == generation.get()) { "Megszakított mentés" }
                try {
                    val raw = elm.command(cmd)
                    val entry = JSONObject().put("raw", raw)
                    try {
                        when (cmd) {
                            "07", "0A" -> Obd.dtcs(raw, elm.canProtocol, cmd.toInt(16))
                            "0101" -> require(Obd.bytesFor(raw, 1, 1).size == 4)
                            else -> {
                                val data = Obd.bytesFor(raw, 2, cmd.substring(2,4).toInt(16))
                                require(data.firstOrNull() == 0 && data.size == (if (cmd == "020200") 3 else 5))
                            }
                        }
                        entry.put("status", "decoded")
                    } catch (e: Exception) { entry.put("status", "unavailable_or_invalid").put("error", e.message) }
                    queries.put(cmd, entry)
                } catch (e: Exception) {
                    queries.put(cmd, JSONObject().put("status", "failed").put("error", e.message))
                    if (!elm.isConnected()) throw e
                }
            }
            report.put("queries", queries)
            val dir = File(filesDir, "diagnostic-backups").apply { check(mkdirs() || isDirectory) }
            val file = File(dir, "partial-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.json")
            val content = report.toString(2)
            FileOutputStream(file).use { output -> output.write(content.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            check(file.readText(Charsets.UTF_8) == content) { "A mentés visszaolvasása sikertelen; törlés tiltva" }
            show(token) {
                clearDialog = AlertDialog.Builder(this).setTitle("Részleges mentés elkészült. Törlöd a hibakódokat?")
                    .setMessage("Mentve az alkalmazás saját tárhelyére: ${file.name}\nRÉSZLEGES mentés: tárolt DTC és a felsorolt kiegészítő lekérdezések. Nem teljes Freeze Frame-mentés; egyes adatok hiányozhatnak. Az alkalmazás eltávolítása a mentést is törli.\n\nA Mode 04 törölheti a hibakódokat, Freeze Frame adatokat és alaphelyzetbe állíthatja a readiness monitorokat. Csak álló járműnél, ráadott gyújtással és LEÁLLÍTOTT motorral: a 04 elküldése előtt az alkalmazás frissen ellenőrzi, hogy a fordulatszám és a sebesség 0. A hiányzó adatokat a mentésből nem lehet helyreállítani.")
                    .setNegativeButton("Mégsem", null)
                    .setPositiveButton("Részleges mentéssel is törlöm") { _, _ ->
                        if (token == generation.get() && !demo.isChecked) task { clearToken ->
                            // Fresh engine-off / standstill proof immediately before Mode 04; unverifiable means no clear.
                            val rpm = try { Obd.bytesFor(elm.command("010C"), 1, 0x0C) } catch (e: Exception) { if (!elm.isConnected()) throw e; null }
                            val speedSupported = 0x0D in elm.supported
                            val speed = if (!speedSupported) null else try { Obd.bytesFor(elm.command("010D"), 1, 0x0D) } catch (e: Exception) { if (!elm.isConnected()) throw e; null }
                            Obd.clearBlockReason(rpm, speed, speedSupported)?.let { reason -> error("$reason. A 04 parancs NEM lett elküldve.") }
                            val reply = elm.command("04")
                            check(Obd.clearAcknowledged(reply)) { "A törlés NEM igazolt: $reply. Ismételt kiolvasás szükséges." }
                            show(clearToken) { errors.text = "VALÓDI ECU • törlés visszaigazolva; olvasd ki ismét a hibakódokat" }
                        }
                    }.create()
                clearDialog?.show()
            }
        }
    }

    private fun show(token: Int, block: () -> Unit) { runOnUiThread { if (active && token == generation.get() && !isDestroyed) block() } }
    override fun onStart() { super.onStart(); active = true }
    override fun onStop() { active = false; resetSession("Az alkalmazás háttérbe került; új kapcsolódás szükséges"); super.onStop() }
    override fun onDestroy() { active = false; elm.close(); worker.shutdownNow(); super.onDestroy() }
}
