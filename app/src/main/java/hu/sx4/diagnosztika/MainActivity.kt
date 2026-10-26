package hu.sx4.diagnosztika

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private var ecuDialog: AlertDialog? = null
    private val ui = Handler(Looper.getMainLooper())
    private var activeSequence: List<Int> = emptyList()
    private val ageTick = object : Runnable {
        override fun run() {
            if (!active) return
            renderDriveState()
            if (!DriveService.state.active && measuring && activeSequence.isNotEmpty()) renderLatest(activeSequence)
            oxygenChart.tick(SystemClock.elapsedRealtime())
            ui.postDelayed(this, 500)
        }
    }
    private lateinit var status: TextView
    private lateinit var values: TextView
    private lateinit var errors: TextView
    private lateinit var statusReport: TextView
    private lateinit var identity: TextView
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var demo: Switch
    private lateinit var oxygenMode: Switch
    private lateinit var oxygenChart: OxygenChartView
    private lateinit var recordingInfo: TextView
    private lateinit var diagnosticReportInfo: TextView
    private lateinit var drivingInfo: TextView
    private var telemetry = PollingTelemetry()
    private var driveRenderedId = ""
    private var finishedDriveId = ""
    private val driveLastSample = mutableMapOf<Int, Long>()
    private val recordLock = Any()
    private val recordEpoch = AtomicInteger()
    @Volatile private var recording: MeasurementLog? = null
    private var recordStartPending = false
    private var pendingExportName: String? = null
    /** Export copy converted for Hungarian spreadsheet settings; the stored measurement stays unchanged. */
    private var pendingExportHungarian = false
    private var pendingReportExportName: String? = null
    private val reportsDir get() = File(filesDir, "reports")
    private val measurementsDir get() = File(filesDir, "measurements")
    /** Latest decoded line per PID with its receive time; read and written on the UI thread only. */
    private val latest = LinkedHashMap<Int, Pair<String, Long>>()
    /** Moving average of one poll (delay + request), the basis of each PID's stale limit. */
    @Volatile private var avgPollMs = 500L
    private val clearState by lazy { getSharedPreferences("diagnostic-state", Context.MODE_PRIVATE) }
    private val clearJournal by lazy { ClearJournal(clearState) }
    private fun clearWarning(): String = clearJournal.warning()
    private fun sessionStatus(message: String): String =
        if (clearState.getBoolean("clearPending", false) && !message.contains("BIZONYTALAN")) clearWarning() + "\n" + message else message
    private val cm by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExportName = savedInstanceState?.getString("pendingExportName")
        pendingExportHungarian = savedInstanceState?.getBoolean("pendingExportHungarian") ?: false
        pendingReportExportName = savedInstanceState?.getString("pendingReportExportName")
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 30, 28, 30) }
        scroll.addView(root); setContentView(scroll)
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(28 + insets.systemWindowInsetLeft, 30 + insets.systemWindowInsetTop,
                28 + insets.systemWindowInsetRight, 30 + insets.systemWindowInsetBottom)
            insets
        }
        fun title(s: String) = TextView(this).apply { text = s; textSize = 22f; setTextColor(Color.rgb(20,55,72)); setPadding(0,18,0,12); root.addView(this) }
        fun button(s: String, action: () -> Unit) = Button(this).apply {
            text = s
            setOnClickListener {
                val allowed = s in setOf("Folyamatos mérés leállítása és mentése", "Mérés leállítása", "Kapcsolat bontása",
                    "Mentett mérés megosztása", "Mentett mérés mentése fájlba", "Mentett jelentés megtekintése",
                    "Mentett jelentés megosztása", "Mentett jelentés mentése fájlba", "Korábbi törlési kísérlet áttekintése")
                if (DriveService.state.active && !allowed) status.text = "Előbb állítsd le és mentsd a folyamatos mérést"
                else action()
            }
            root.addView(this)
        }
        fun field(value: String, hint: String) = EditText(this).apply { setText(value); this.hint = hint; setSingleLine(true); root.addView(this) }
        // Packaged photo: no network connection is needed on the opening screen.
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.sx4_header)
            contentDescription = "Fekete, első generációs Suzuki SX4"
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxHeight = (220 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        title("SX4 Diagnosztika Teszt")
        TextView(this).apply {
            text = "Külön telepített tesztváltozat\nMegadott profil: Suzuki SX4 2007 • 1.5 benzin • v${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})\n" +
                "Build ${BuildConfig.BUILD_NUMBER} • commit ${BuildConfig.GIT_SHA} • ${BuildConfig.BUILD_TYPE}"
            root.addView(this)
        }
        status = TextView(this).apply { text = "DEMO mód • nincs ECU-kapcsolat"; textSize = 17f; root.addView(this) }
        host = field("192.168.0.10", "Adapter IPv4-címe")
        port = field("35000", "TCP-port").apply { inputType = 2 }
        demo = Switch(this).apply { text = "DEMO mód – szimulált adatok"; isChecked = true; root.addView(this) }
        button("Csatlakozás az adapterhez") { connectWifi() }
        button("Kapcsolat bontása") { if(DriveService.state.active) stopDriving() else resetSession("Kapcsolat bontva • a korábbi adatok érvénytelenek") }
        button("Vezérlő kiválasztása") { chooseEcu() }
        title("Vezérlőazonosítás")
        identity = TextView(this).apply { text = "Azonosítók még nincsenek kiolvasva"; textSize = 16f; root.addView(this) }
        button("Adapter és ECU azonosítóinak olvasása") { readIdentity() }
        TextView(this).apply {
            text = "Szabványos OBD-azonosítók. Gyártói szervizfunkciók és paraméterírás még nincs beépítve."
            root.addView(this)
        }
        title("Élő motoradatok")
        values = TextView(this).apply { textSize = 17f; text = "Még nincs mérés"; root.addView(this) }
        oxygenMode = Switch(this).apply { text = "Gyors szondamérés • csak B1S1 és B1S2"; root.addView(this) }
        button("Élő adatok indítása") { startReading() }
        button("Mérés leállítása") { if(DriveService.state.active) stopDriving() else pauseMeasurement("kézi leállítás") }
        title("Lambdaszondák • utolsó 60 másodperc")
        oxygenChart = OxygenChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (280*resources.displayMetrics.density).toInt())
            root.addView(this)
        }
        TextView(this).apply {
            text = "A szondák eltérő időpontban frissülnek. A pontok valódi minták; 5 másodpercnél nagyobb adathiány megszakítja a görbét. A grafikon önmagában nem katalizátorteszt."
            root.addView(this)
        }
        title("Mérésmentés • CSV")
        recordingInfo = TextView(this).apply { text = "Nincs aktív rögzítés"; textSize = 16f; root.addView(this) }
        button("Rögzítés indítása") { startRecording() }
        button("Rögzítés lezárása és mentése") { stopRecording("kézi mentés") }
        button("Mentett mérés megosztása") { chooseMeasurement(share = true) }
        button("Mentett mérés mentése fájlba") { chooseMeasurement(share = false) }
        TextView(this).apply {
            text = "A rögzítés legfeljebb 60 percig vagy 10 MB-ig tart. Diagnosztikai művelet, vezérlőváltás vagy háttérbe lépés lezárja. Megosztás után új adapterkapcsolat szükséges. A belső mentéseket az alkalmazás eltávolítása törli; a Fájlba mentés külön példányt készít."
            root.addView(this)
        }
        oxygenMode.setOnCheckedChangeListener { _, _ ->
            pauseMeasurement("mérési mód váltása")
            latest.clear(); oxygenChart.reset()
            values.text = "Mérési mód megváltozott • indítsd újra az élő adatokat"
        }
        title("Folyamatos mérés • kikapcsolt képernyővel is")
        drivingInfo = TextView(this).apply { textSize = 16f; text = "Nincs folyamatos mérés"; root.addView(this) }
        button("Folyamatos mérés és rögzítés indítása") { startDriving() }
        button("Folyamatos mérés leállítása és mentése") { stopDriving() }
        TextView(this).apply {
            text = "Valódi módban előbb csatlakozz és válassz ECU-t. Az indítás új, külön mérési kapcsolatot nyit. Legfeljebb 60 percig rögzít; értesítésből is leállítható. A gyors profil a szondák mellett fordulatszámot és szabályozási állapotot is kér, ha támogatottak. Vezetés közben a telefont ne kezeld."
            root.addView(this)
        }
        title("Hibakód-diagnosztika")
        errors = TextView(this).apply { text = "Még nem történt kiolvasás"; textSize = 16f; root.addView(this) }
        button("Tárolt hibakódok olvasása") {
            if (demo.isChecked) errors.text = "DEMO / SZIMULÁLT\nP0420 – Katalizátor hatásfoka a küszöb alatt (1. hengersor)"
            else {
                task(onAccepted = { errors.text = "VALÓDI ECU • hibakód-kiolvasás folyamatban…" },
                    onFailure = { errors.text = "A hibakód-kiolvasás sikertelen: $it" }) { token ->
                val codes = Obd.dtcs(elm.command("03"), elm.canProtocol)
                show(token) {
                    // A fresh read is current evidence, not proof of an earlier write outcome.
                    status.text = sessionStatus("VALÓDI • ECU: ${elm.selectedSource} • a hibakódok frissen kiolvasva")
                    errors.text = "VALÓDI ECU • tárolt hibakódok\n" + codes.ifEmpty { listOf("Nincs tárolt hibakód a sikeresen dekódolt válaszban") }.joinToString("\n")
                }
                }
            }
        }
        button("Összes hibakódtípus olvasása") { readAllDtcs() }
        button("Hibakódok törlése") { prepareClear() }
        button("Korábbi törlési kísérlet áttekintése") { reviewClearAttempt() }
        title("Diagnosztikai jelentés")
        diagnosticReportInfo = TextView(this).apply { text = "Még nincs készített jelentés"; textSize = 16f; root.addView(this) }
        button("Friss diagnosztikai jelentés készítése") { createDiagnosticReport() }
        button("Mentett jelentés megtekintése") { chooseReport("view") }
        button("Mentett jelentés megosztása") { chooseReport("share") }
        button("Mentett jelentés mentése fájlba") { chooseReport("save") }
        TextView(this).apply {
            text = "Egy kiválasztott ECU szabványos OBD-jelentése: három hibakódtípus, azonosítók, readiness és Freeze Frame. Az olvasás szünetelteti az élő mérést. A jelentés VIN-t is tartalmazhat. Nem teljes gyári szervizvizsgálat."
            root.addView(this)
        }
        title("Readiness, Freeze Frame, járműállapot")
        statusReport = TextView(this).apply { text = "Még nem történt kiolvasás"; textSize = 16f; root.addView(this) }
        button("Állapot és Freeze Frame olvasása") { readStatus() }
        TextView(this).apply {
            text = "Fejlesztői prototípus. Álló járműnél használd. A hibakódtörlés nem javítja meg a hibát. ECU-paraméterírás és emissziófelügyelet-módosítás nincs beépítve. A törlés előtti mentés részleges, nem teljes járműdiagnosztikai mentés."
            setPadding(0,24,0,20); root.addView(this)
        }
        demo.setOnCheckedChangeListener { _, checked -> resetSession(if (checked) "DEMO mód • nincs ECU-kapcsolat" else "VALÓDI mód • előbb csatlakozz") }
    }

    private fun resetSession(message: String) {
        driveRenderedId = ""; driveLastSample.clear()
        generation.incrementAndGet(); measuring = false; readingGeneration.incrementAndGet(); poll?.cancel(false)
        stopRecording(message); oxygenChart.reset()
        connecting = false; operation = false
        clearDialog?.dismiss(); clearDialog = null
        ecuDialog?.dismiss(); ecuDialog = null
        elm.disconnect()
        networkCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: IllegalArgumentException) {} }
        networkCallback = null
        status.text = sessionStatus(message)
        values.text = "Nincs érvényes mérés"
        errors.text = "Nincs érvényes hibakód-kiolvasás"
        statusReport.text = "Nincs érvényes állapot-kiolvasás"
        identity.text = "Nincs érvényes azonosító-kiolvasás"
        diagnosticReportInfo.text = "Nincs folyamatban vizsgálat • ${DiagnosticReport.saved(reportsDir).size} korábbi, időbélyegzett jelentés"
    }

    private fun readAllDtcs() {
        if (demo.isChecked) {
            errors.text = "DEMO / SZIMULÁLT\n" + DtcScan.display(DiagnosticReport.demo(BuildConfig.VERSION_NAME).dtcs)
            return
        }
        task(onAccepted = { errors.text = "VALÓDI ECU • három hibakódtípus olvasása folyamatban…" },
            onFailure = { errors.text = "A hibakódolvasás sikertelen: $it" }) { token ->
            val source = elm.selectedSource!!
            val results = DtcScan({ elm.command(it) }, elm.canProtocol,
                { token == generation.get() && elm.isConnected() }).readAll()
            show(token) {
                errors.text = "VALÓDI ECU: $source • friss, egymás utáni olvasások\n" + DtcScan.display(results)
                if (results.first().state == DtcScan.State.OK) {
                    // A fresh read is current evidence, not proof of an earlier write outcome.
                    status.text = sessionStatus("VALÓDI • ECU: $source • a tárolt hibakódok frissen kiolvasva")
                }
            }
        }
    }

    private fun createDiagnosticReport() {
        if (operation || connecting) { status.text = "Várd meg a folyamatban lévő műveletet"; return }
        if (demo.isChecked) {
            operation = true
            pauseMeasurement("DEMO jelentés készítése")
            val token = generation.get()
            diagnosticReportInfo.text = "DEMO jelentés mentése folyamatban…"
            worker.execute {
                try {
                    if (token != generation.get()) return@execute
                    val file = DiagnosticReport.save(reportsDir, DiagnosticReport.demo(BuildConfig.VERSION_NAME))
                    show(token) { diagnosticReportInfo.text = "DEMO / SZIMULÁLT jelentés mentve\n${file.name}" }
                } catch (e: Exception) { show(token) { diagnosticReportInfo.text = "Jelentésmentési hiba: ${e.message}" } }
                finally { show(token) { operation = false } }
            }
            return
        }
        task(onAccepted = { diagnosticReportInfo.text = "Friss vizsgálat folyamatban… Ez több percig is tarthat." },
            onFailure = { diagnosticReportInfo.text = "Nem készült új jelentés: $it" }) { token ->
            val source = elm.selectedSource!!
            val snapshot = DiagnosticReport({ elm.command(it, 8000) }, elm.supported.toSet(), elm.canProtocol,
                { elm.isConnected() }, { token == generation.get() }).collect(source, BuildConfig.VERSION_NAME,
                clearState.getBoolean("clearPending", false)) { section ->
                show(token) { diagnosticReportInfo.text = "Friss vizsgálat folyamatban…\n$section" }
            }
            check(token == generation.get() && elm.isConnected()) { "Megszakított vizsgálat" }
            val file = DiagnosticReport.save(reportsDir, snapshot)
            show(token) {
                errors.text = "VALÓDI ECU: $source\n" + DtcScan.display(snapshot.dtcs)
                diagnosticReportInfo.text = "VALÓDI ECU: $source • időbélyegzett jelentés mentve\n${file.name}\nA hiányzó vagy hibás adatok a jelentésben külön szerepelnek."
            }
        }
    }

    private fun chooseReport(action: String) {
        if (operation || connecting) { status.text = "Várd meg a folyamatban lévő műveletet"; return }
        val files = DiagnosticReport.saved(reportsDir).take(100)
        if (files.isEmpty()) { diagnosticReportInfo.text = "Még nincs mentett jelentés"; return }
        AlertDialog.Builder(this).setTitle("Korábbi diagnosztikai jelentések")
            .setItems(files.map { it.name }.toTypedArray()) { _, index ->
                val file = files[index]
                try {
                    when (action) {
                        "share" -> startActivity(Intent.createChooser(ReportSharing.shareIntent(this, file), "Jelentés megosztása"))
                        "save" -> {
                            pendingReportExportName = file.name
                            startActivityForResult(ReportSharing.saveIntent(file), 4103)
                        }
                        else -> {
                            val view = TextView(this).apply { text = file.readText(Charsets.UTF_8); setPadding(24, 16, 24, 16); setTextIsSelectable(true) }
                            AlertDialog.Builder(this).setTitle("Korábbi, időbélyegzett jelentés")
                                .setView(ScrollView(this).apply { addView(view) }).setPositiveButton("Bezárás", null).show()
                        }
                    }
                } catch (e: Exception) { diagnosticReportInfo.text = "A jelentés nem nyitható meg: ${e.message}" }
            }.setNegativeButton("Mégsem", null).show()
    }

    private fun readIdentity() {
        if (demo.isChecked) {
            identity.text = "DEMO mód • nincs valódi adapter- vagy ECU-azonosító. Kapcsold ki a DEMO módot, és csatlakozz az adapterhez."
            return
        }
        task(onAccepted = { identity.text = "Azonosítók kiolvasása folyamatban… Ez több másodpercig tarthat." },
            onFailure = { identity.text = "Azonosítás sikertelen: $it" }) { token ->
            val lines = EcuIdentity({ elm.command(it, 8000) }, elm.canProtocol,
                { token == generation.get() && elm.isConnected() }).report()
            show(token) { identity.text = "VALÓDI KAPCSOLAT • ECU: ${elm.selectedSource} • szabványos azonosítók\n" + lines.joinToString("\n") }
        }
    }

    private fun readStatus() {
        if (demo.isChecked) {
            statusReport.text = "DEMO / SZIMULÁLT\nHibalámpa (MIL): BE • ECU szerinti hibakódszám: 1\nKatalizátor: NEM KÉSZ\nLambdaszonda: KÉSZ\n" +
                "Freeze Frame kiváltó hibakód: P0420\nFordulatszám: 2480.00 1/perc\nHűtőfolyadék: 89 °C"
            return
        }
        task(onAccepted = { statusReport.text = "VALÓDI ECU • állapot-kiolvasás folyamatban… (K-line-on több másodperc)" },
            onFailure = { statusReport.text = "Az állapot-kiolvasás sikertelen: $it" }) { token ->
            val sections = VehicleStatus({ elm.command(it) }, elm.supported, { elm.isConnected() }).report()
            show(token) { statusReport.text = "VALÓDI ECU\n" + sections.joinToString("\n\n") { "▸ ${it.title}\n" + it.lines.joinToString("\n") } }
        }
    }

    private fun chooseEcu() {
        if (demo.isChecked || !elm.isConnected()) { status.text = "Előbb csatlakozz valódi módban"; return }
        if (connecting || operation || clearDialog?.isShowing == true) { status.text = "Várd meg a folyamatban lévő műveletet"; return }
        val sources = elm.ecuSources.keys.sorted()
        ecuDialog?.dismiss()
        ecuDialog = AlertDialog.Builder(this).setTitle("Válaszoló vezérlő kiválasztása")
            .setItems(sources.map { "ECU: $it" }.toTypedArray()) { _, index ->
                if (!elm.isConnected() || operation) return@setItems
                generation.incrementAndGet()
                pauseMeasurement("vezérlőváltás")
                oxygenChart.reset()
                elm.selectEcu(sources[index])
                latest.clear()
                values.text = "Új vezérlő • nincs érvényes mérés"
                errors.text = "Új vezérlő • nincs érvényes hibakód-kiolvasás"
                statusReport.text = "Új vezérlő • nincs érvényes állapot-kiolvasás"
                identity.text = "Új vezérlő • nincs érvényes azonosító-kiolvasás"
                diagnosticReportInfo.text = "Új vezérlő • a mentett jelentések korábbi vizsgálatok"
                status.text = sessionStatus("VALÓDI • kiválasztott ECU: ${sources[index]}. A cím önmagában nem azonosítja a vezérlő feladatát.")
            }.setNegativeButton("Mégsem", null).create()
        ecuDialog?.show()
    }

    private fun pauseMeasurement(reason: String) {
        stopRecording(reason)
        if (!measuring) return
        measuring = false; readingGeneration.incrementAndGet(); poll?.cancel(false)
        oxygenChart.pause(SystemClock.elapsedRealtime())
        values.text = "Mérés szünetel: $reason • a korábbi kijelzés érvénytelen"
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
                        show(token) {
                            connecting = false
                            status.text = sessionStatus("VALÓDI • $id • kiválasztva: ${elm.selectedSource ?: "nincs, válassz vezérlőt"}")
                            if (elm.selectedSource == null) chooseEcu()
                        }
                    } catch (e: Exception) {
                        show(token) { resetSession("Kapcsolódási hiba: ${e.message}") }
                    }
                }
            }
            override fun onLost(network: Network) {
                val lostCallback = this
                runOnUiThread {
                    // ECU selection invalidates readings, but the same network callback remains valid.
                    if (active && !isDestroyed && networkCallback === lostCallback && network == selected)
                        resetSession("Az adapter Wi-Fi-kapcsolata megszakadt")
                }
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
        if (DriveService.state.active || measuring || operation || connecting) return
        val simulated = demo.isChecked
        if (!simulated && !elm.isConnected()) { status.text = "Előbb csatlakozz az adapterhez"; return }
        if (!simulated && elm.selectedSource == null) { status.text = "Előbb válassz vezérlőt"; chooseEcu(); return }
        val available = if (simulated) DemoSamples.supported else elm.supported
        val sequence = if (oxygenMode.isChecked) LivePlan.oxygenSequence(available) else LivePlan.sequence(available)
        if (sequence.isEmpty()) { values.text = "Ehhez a mérési módhoz nincs támogatott PID"; return }
        telemetry = PollingTelemetry()
        measuring = true; activeSequence = sequence; latest.clear()
        oxygenChart.start(simulated, available, SystemClock.elapsedRealtime())
        renderLatest(sequence)
        scheduleReading(generation.get(), readingGeneration.incrementAndGet(), sequence, 0, simulated)
    }

    private fun scheduleReading(token: Int, readingToken: Int, sequence: List<Int>, index: Int, simulated: Boolean) {
        poll = worker.schedule({
            if (!measuring || token != generation.get() || readingToken != readingGeneration.get()) return@schedule
            val pid = sequence[index]
            val started = System.nanoTime()
            try {
                val data = if (simulated) DemoSamples.data(pid, SystemClock.elapsedRealtime())
                    else Obd.bytesFor(elm.command("01%02X".format(pid)), 1, pid)
                val sample = LiveSample.decode(pid, data)
                avgPollMs = (avgPollMs * 7 + 200 + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)) / 8
                val at = SystemClock.elapsedRealtime()
                if (measuring && token == generation.get() && readingToken == readingGeneration.get()) {
                    telemetry.success(sample, at, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started))
                    appendRecording(sample, at, token)
                    show(token) { if (measuring && readingToken == readingGeneration.get()) {
                        latest[pid] = sample.reading to at; oxygenChart.sample(sample, at); renderLatest(sequence)
                    } }
                }
            } catch (e: Exception) {
                if (measuring && token == generation.get() && readingToken == readingGeneration.get()) {
                    telemetry.failure(pid, SystemClock.elapsedRealtime(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started))
                    appendRecordingFailure(pid, e.message ?: "Ismeretlen hiba", SystemClock.elapsedRealtime(), token)
                    show(token) { if (measuring && readingToken == readingGeneration.get()) {
                        latest.remove(pid); oxygenChart.gap(pid)
                        renderLatest(sequence, "%02X: nincs érvényes friss mérés • %s".format(pid, e.message))
                    } }
                }
                if (!simulated && !elm.isConnected()) { show(token) { resetSession("A TCP-kapcsolat megszakadt: ${e.message}") }; return@schedule }
            }
            if (measuring && token == generation.get() && readingToken == readingGeneration.get())
                scheduleReading(token, readingToken, sequence, (index + 1) % sequence.size, simulated)
        }, 200, TimeUnit.MILLISECONDS)
    }

    /** Each value keeps its own age; nothing older than its stale limit is shown as current. */
    private fun renderLatest(sequence: List<Int>, note: String? = null) {
        val now = SystemClock.elapsedRealtime()
        val lines = LivePlan.displayed(sequence.toSet()).map { pid ->
            val entry = latest[pid] ?: return@map "%02X: még nincs érvényes adat".format(pid)
            val age = now - entry.second
            if (age > LivePlan.staleAfterMs(pid, sequence, avgPollMs)) "ELAVULT (${age / 1000} s): ${entry.first}" else "${entry.first}  (${age} ms)"
        }
        values.text = (if (demo.isChecked) "DEMO / SZIMULÁLT" else "VALÓDI ECU") + " • PID-enként külön időpontban olvasva\n" + lines.joinToString("\n") + "\n" + samplingSummary(now) + (note?.let { "\n$it" } ?: "")
    }

    private fun startRecording() {
        if (recording != null || recordStartPending) { recordingInfo.text = "A rögzítés már folyamatban van"; return }
        if (operation || connecting) { recordingInfo.text = "Várd meg a diagnosztikai művelet végét"; return }
        startReading()
        if (!measuring) return
        val token = generation.get()
        val request = recordEpoch.incrementAndGet()
        val mode = if (demo.isChecked) "DEMO" else "REAL"
        val source = if (demo.isChecked) "DEMO" else elm.selectedSource ?: return
        val profile = if (oxygenMode.isChecked) "OXYGEN_ONLY" else "ALL_LIVE_PIDS"
        recordStartPending = true
        recordingInfo.text = "Rögzítés előkészítése…"
        worker.execute {
            if (request != recordEpoch.get() || token != generation.get() || !measuring) return@execute
            var opened: MeasurementLog? = null
            try {
                val target = MeasurementLog(measurementsDir, mode, source, profile, BuildConfig.VERSION_NAME,
                    System.currentTimeMillis(), SystemClock.elapsedRealtime())
                opened = target
                val accepted = synchronized(recordLock) {
                    if (request == recordEpoch.get() && token == generation.get() && measuring) {
                        recording = target; true
                    } else false
                }
                if (!accepted) { target.finish("Megszakított indítás", SystemClock.elapsedRealtime()); return@execute }
                show(token) { if (request == recordEpoch.get()) {
                    recordStartPending = false
                    recordingInfo.text = "${if (mode == "DEMO") "DEMO / SZIMULÁLT" else "VALÓDI ECU: $source"} • rögzítés folyamatban\n${target.file.name}"
                } }
            } catch (e: Exception) {
                opened?.abort()
                synchronized(recordLock) { if (recording === opened) recording = null }
                show(token) { if (request == recordEpoch.get()) {
                    recordStartPending = false; recordingInfo.text = "Rögzítési hiba: ${e.message}"
                } }
            }
        }
    }

    private fun stopRecording(reason: String) {
        val wasPending = recordStartPending
        recordStartPending = false
        val (request, target) = synchronized(recordLock) {
            val request = recordEpoch.incrementAndGet()
            val previous = recording; recording = null
            request to previous
        }
        if (target == null) {
            if (wasPending) recordingInfo.text = "A rögzítés indítása megszakítva"
            return
        }
        val at = SystemClock.elapsedRealtime()
        val token = generation.get()
        recordingInfo.text = "Mérés lezárása és mentése…"
        worker.execute {
            try {
                val file = target.finish(reason, at)
                show(token) { if (request == recordEpoch.get()) {
                    recordingInfo.text = "Mentve • ${target.rows} minta • $reason\n${file.name}"
                } }
            } catch (e: Exception) {
                target.abort()
                show(token) { if (request == recordEpoch.get()) { recordingInfo.text = "A mérés mentése sikertelen: ${e.message}" } }
            }
        }
    }

    /** Local file failures never become OBD transport failures. Called only by the polling worker. */
    private fun appendRecording(sample: LiveSample, at: Long, token: Int) = recordRow(token) { it.append(sample, at, telemetry.metrics(sample.pid, at)) }
    private fun appendRecordingFailure(pid: Int, error: String, at: Long, token: Int) = recordRow(token) { it.failure(pid, error, at, telemetry.metrics(pid, at)) }
    private fun recordRow(token: Int, append: (MeasurementLog) -> Boolean) {
        val target = recording ?: return
        val request = recordEpoch.get()
        try {
            if (!append(target)) {
                synchronized(recordLock) { if (recording === target) recording = null }
                show(token) { if (request == recordEpoch.get()) {
                    recordingInfo.text = "Rögzítés lezárva • idő- vagy méretkorlát • ${target.rows} minta\n${target.file.name}"
                } }
            }
        } catch (e: Exception) {
            target.abort()
            synchronized(recordLock) { if (recording === target) recording = null }
            show(token) { if (request == recordEpoch.get()) { recordingInfo.text = "Rögzítési hiba: ${e.message}" } }
        }
    }

    private fun refreshRecordingSummary() {
        val files = MeasurementLog.saved(measurementsDir)
        recordingInfo.text = if (files.isEmpty()) "Nincs aktív rögzítés • még nincs mentett mérés"
            else "${files.size} mentett mérés • legutóbbi:\n${files.first().name}"
    }

    private fun chooseMeasurement(share: Boolean) {
        if (recording != null || recordStartPending) { recordingInfo.text = "Előbb zárd le és mentsd a rögzítést"; return }
        val files = MeasurementLog.saved(measurementsDir).take(100)
        if (files.isEmpty()) { recordingInfo.text = "Még nincs mentett mérés"; return }
        AlertDialog.Builder(this).setTitle(if (share) "Melyik mérést osztod meg?" else "Melyik mérést mented fájlba?")
            .setItems(files.map { "${it.name}\n${(it.length()+1023)/1024} KB" }.toTypedArray()) { _, index ->
                try {
                    val file = files[index]
                    if (share) startActivity(Intent.createChooser(MeasurementSharing.shareIntent(this, file), "Mérés megosztása"))
                    else chooseExportFormat(file)
                } catch (e: Exception) { recordingInfo.text = "A fájlművelet nem indítható: ${e.message}" }
            }.setNegativeButton("Mégsem", null).show()
    }

    private fun chooseExportFormat(file: File) {
        AlertDialog.Builder(this).setTitle("Milyen formátumban mented?")
            .setItems(arrayOf("Szabványos CSV (vessző, tizedespont) • programokhoz",
                "Magyar Excel (pontosvessző, tizedesvessző)")) { _, index ->
                try {
                    pendingExportName = file.name
                    pendingExportHungarian = index == 1
                    val title = if (pendingExportHungarian) CsvExport.hungarianName(file.name) else file.name
                    startActivityForResult(MeasurementSharing.saveIntent(file, title), 4102)
                } catch (e: Exception) { recordingInfo.text = "A fájlművelet nem indítható: ${e.message}" }
            }.setNegativeButton("Mégsem", null).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingExportName", pendingExportName)
        outState.putBoolean("pendingExportHungarian", pendingExportHungarian)
        outState.putString("pendingReportExportName", pendingReportExportName)
        super.onSaveInstanceState(outState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in listOf(4102, 4103)) return
        val report = requestCode == 4103
        val destinationInfo = if (report) diagnosticReportInfo else recordingInfo
        val name = if (report) pendingReportExportName else pendingExportName
        val hungarian = !report && pendingExportHungarian
        if (report) pendingReportExportName = null else { pendingExportName = null; pendingExportHungarian = false }
        if (resultCode != RESULT_OK || data?.data == null || name == null) { destinationInfo.text = "Fájlba mentés megszakítva"; return }
        val file = (if (report) DiagnosticReport.saved(reportsDir) else MeasurementLog.saved(measurementsDir)).firstOrNull { it.name == name }
        if (file == null) { destinationInfo.text = "A forrásfájl már nem érhető el"; return }
        val uri = data.data!!
        if (uri.scheme != "content") { destinationInfo.text = "Nem támogatott célhely"; return }
        val token = generation.get()
        destinationInfo.text = "Fájl másolása…"
        worker.execute {
            try {
                // Stage and validate completely with bounded memory before opening the chosen URI.
                CsvExport.prepare(file, cacheDir, hungarian).use { prepared ->
                    ExportDestination.copy(contentResolver, uri, prepared.file)
                }
                show(token) { destinationInfo.text = "Fájl elmentve a választott helyre" + (if (hungarian) " (magyar Excel-formátum)" else "") + ":\n${file.name}" }
            } catch (e: Exception) { show(token) { destinationInfo.text = "A fájlba mentés sikertelen: ${e.message}" } }
        }
    }

    /** Serialize operations with single-PID polls, never queue an infinite read loop. */
    private fun task(onAccepted: () -> Unit = {}, onFailure: (String) -> Unit = {}, block: (Int) -> Unit) {
        if (DriveService.state.active) { status.text = "Előbb állítsd le a folyamatos mérést"; return }
        if (!elm.isConnected() || demo.isChecked) { status.text = "Előbb csatlakozz valódi módban"; return }
        if (elm.selectedSource == null) { status.text = "Előbb válassz vezérlőt"; chooseEcu(); return }
        if (operation) { status.text = "Egy művelet már folyamatban van"; return }
        operation = true
        pauseMeasurement("diagnosztikai művelet")
        onAccepted()
        val token = generation.get()
        worker.execute {
            if (token != generation.get()) return@execute
            try { block(token) }
            catch (e: Exception) { show(token) {
                when {
                    e is ClearOutcomeUnknown -> resetSession(e.message ?: "A törlés eredménye BIZONYTALAN")
                    !elm.isConnected() -> resetSession("Kapcsolati hiba: ${e.message}")
                    else -> { status.text = "Hiba: ${e.message}"; onFailure(e.message ?: "Ismeretlen hiba") }
                }
            } }
            finally { show(token) { operation = false } }
        }
    }

    private fun prepareClear() {
        if (clearState.getBoolean("clearPending", false)) { status.text = clearWarning(); reviewClearAttempt(); return }
        if (demo.isChecked || !elm.isConnected()) { status.text = "Törléshez valódi ECU-kapcsolat szükséges"; return }
        if (operation) { status.text = "Egy művelet már folyamatban van"; return }
        elm.clearAvailabilityReason()?.let { status.text = it; return }
        task(onAccepted = { status.text = "Törlés előkészítése • részleges mentés folyamatban…" }) { token ->
            // Mandatory Mode 03 must decode; optional data is recorded with explicit per-query errors.
            val stored = elm.command("03")
            val codes = Obd.dtcs(stored, elm.canProtocol)
            val permit = elm.prepareClearPermit(stored)
            val report = JSONObject().put("schema", 1).put("source", "REAL_ECU")
                .put("timestampUtcMillis", System.currentTimeMillis()).put("complete", false)
                .put("canProtocol", elm.canProtocol).put("selectedEcuSource", elm.selectedSource)
                .put("responseEncoding", "selected_ecu_normalized_obd_payloads_not_raw_bus_frames")
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("appVersionCode", BuildConfig.VERSION_CODE).put("gitSha", BuildConfig.GIT_SHA)
                .put("limitations", "RÉSZLEGES: csak a kiválasztott vezérlő szabványos OBD-adatai (DTC, readiness, 0. Freeze Frame, járműállapot); más ECU-k és gyártóspecifikus adatok nélkül.")
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
            // Decoded readiness, vehicle status and every freeze frame PID the ECU supports.
            check(token == generation.get()) { "Megszakított mentés" }
            val decoded = JSONObject()
            VehicleStatus({ elm.command(it) }, elm.supported, { elm.isConnected() }).report()
                .forEach { decoded.put(it.title, org.json.JSONArray(it.lines)) }
            report.put("decodedStatus", decoded)
            val dir = File(filesDir, "diagnostic-backups").apply { check(mkdirs() || isDirectory) }
            val file = File(dir, "partial-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.json")
            val content = report.toString(2)
            FileOutputStream(file).use { output -> output.write(content.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            check(file.readText(Charsets.UTF_8) == content) { "A mentés visszaolvasása sikertelen; törlés tiltva" }
            show(token) {
                val remainingMs = (ClearPermit.LIFETIME_NANOS - (System.nanoTime() - permit.createdAtNanos)) / 1_000_000
                if (remainingMs <= 0) { status.text = "A mentés lejárt; indítsd újra a törlés előkészítését"; return@show }
                status.text = "Részleges mentés elkészült • megerősítésre vár"
                clearDialog = AlertDialog.Builder(this).setTitle("Részleges mentés elkészült. Törlöd a hibakódokat?")
                    .setMessage("Mentve az alkalmazás saját tárhelyére: ${file.name}\nRÉSZLEGES mentés: tárolt és függő DTC, readiness, a 0. Freeze Frame összes támogatott PID-je és járműállapot. Más ECU-k és gyártóspecifikus adatok nincsenek benne; egyes adatok hiányozhatnak. Az alkalmazás eltávolítása a mentést is törli.\n\nA megerősítés legfeljebb 2 percig érvényes a hibakódok mentésétől számítva. Megváltozott hibakódlista esetén új mentés szükséges. A Mode 04 törölheti a hibakódokat, Freeze Frame adatokat és alaphelyzetbe állíthatja a readiness monitorokat. Csak álló járműnél, ráadott gyújtással és LEÁLLÍTOTT motorral: a 04 elküldése előtt az alkalmazás frissen ellenőrzi, hogy a fordulatszám és a sebesség 0. A hiányzó adatokat a mentésből nem lehet helyreállítani.")
                    .setNegativeButton("Mégsem", null)
                    .setPositiveButton("Részleges mentéssel is törlöm") { _, _ ->
                        if (token == generation.get() && !demo.isChecked) task(onAccepted = {
                            status.text = "Törlési előfeltételek friss ellenőrzése…"
                        }) { clearToken ->
                            var attemptId: String? = null
                            try {
                                elm.clearDtcs(permit, onDispatch = {
                                    attemptId = clearJournal.dispatch(permit.source, elm.protocolCode, elm.connectionId)
                                }) { clearToken == generation.get() }
                                clearJournal.finish(attemptId, "ACKNOWLEDGED")
                            } catch (e: Exception) {
                                if (e is ClearRejected) clearJournal.finish(attemptId, "REJECTED_%02X".format(e.code))
                                throw e
                            }
                            show(clearToken) {
                                status.text = "VALÓDI • a törlés visszaigazolva"
                                errors.text = "VALÓDI ECU • törlés visszaigazolva; olvasd ki ismét a hibakódokat"
                            }
                        }
                    }.create()
                val shownDialog = clearDialog
                val expire = Runnable {
                    if (clearDialog === shownDialog && shownDialog?.isShowing == true) {
                        shownDialog.dismiss()
                        status.text = "A törlési megerősítés lejárt; új mentés szükséges"
                    }
                }
                shownDialog?.setOnDismissListener { ui.removeCallbacks(expire); if (clearDialog === shownDialog) clearDialog = null }
                shownDialog?.show()
                ui.postDelayed(expire, remainingMs)
            }
        }
    }

    private fun startDriving() {
        if (operation || connecting || DriveService.state.active || clearDialog?.isShowing == true) {
            status.text = "Várd meg vagy állítsd le a folyamatban lévő műveletet"; return
        }
        val simulated = demo.isChecked
        if (!simulated && (!elm.isConnected() || elm.selectedSource == null)) {
            status.text = "Előbb csatlakozz valódi módban és válassz ECU-t"; return
        }
        val ip = host.text.toString().trim(); val p = port.text.toString().toIntOrNull() ?: 0
        val source = if(simulated) "DEMO" else elm.selectedSource!!
        val oxygen = oxygenMode.isChecked
        resetSession("Átadás a folyamatos mérésnek…")
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 74)
            DriveService.launch(this, simulated, ip, p, source, oxygen)
            renderDriveState()
        } catch(e: Exception) { drivingInfo.text = "A folyamatos mérés nem indítható: ${e.message}" }
    }

    private fun stopDriving() {
        if (!DriveService.state.active) { drivingInfo.text = "Nincs aktív folyamatos mérés"; return }
        startService(Intent(this, DriveService::class.java).setAction(DriveService.STOP))
        drivingInfo.text = "Folyamatos mérés lezárása és mentése…"
    }

    private fun samplingSummary(now: Long): String = listOf(20, 21).filter { it in activeSequence }.joinToString("\n") { pid ->
        val m = telemetry.metrics(pid, now)
        "B1S${pid-19}: ${m.hz?.let { String.format(java.util.Locale.ROOT, "%.2f minta/s", it) } ?: "még nincs elég friss minta"} • utolsó válasz ${m.requestMs} ms • ${m.errors} válaszhiba"
    } + "\nA sebesség az utolsó 10 s sikeres mintáiból számított érték."

    private fun renderDriveState() {
        val s = DriveService.state
        demo.isEnabled = !s.active; oxygenMode.isEnabled = !s.active; host.isEnabled = !s.active; port.isEnabled = !s.active
        if (s.id.isEmpty() || (!s.active && finishedDriveId == s.id)) return
        val now = SystemClock.elapsedRealtime()
        if (s.id != driveRenderedId && s.supported.isNotEmpty()) {
            driveRenderedId = s.id; driveLastSample.clear()
            oxygenChart.start(s.demo, s.supported, s.startedAt)
        }
        for(e in s.events) if(e.atMs > (driveLastSample[e.pid] ?: -1L)) {
            driveLastSample[e.pid] = e.atMs
            if(e.sample != null) oxygenChart.sample(e.sample, e.atMs) else oxygenChart.gap(e.pid)
        }
        val rates = listOf(20,21).filter { it in s.supported }.joinToString("\n") { pid ->
            val samples = s.events.filter { it.pid == pid && it.sample != null && now-it.atMs in 0..10_000 }
            val hz = if(samples.size >= 2 && samples.last().atMs > samples.first().atMs)
                String.format(java.util.Locale.ROOT, "%.2f minta/s", (samples.size-1)*1000.0/(samples.last().atMs-samples.first().atMs)) else "nincs elég friss minta"
            val last = s.events.lastOrNull { it.pid == pid }
            "B1S${pid-19}: $hz • utolsó válasz ${last?.metrics?.requestMs ?: 0} ms • ${last?.metrics?.errors ?: 0} válaszhiba"
        }
        drivingInfo.text = (if(s.demo) "DEMO / SZIMULÁLT" else "VALÓDI ECU: ${s.source}") + "\n${s.message}\n$rates\nA sebesség az utolsó 10 s sikeres mintáiból számított érték."
        if (s.active) {
            status.text = sessionStatus("Folyamatos ${if(s.demo) "DEMO" else "VALÓDI"} mérés • ECU: ${s.source}")
            val lastByPid = s.events.groupBy { it.pid }.values.map { it.last() }.sortedBy { it.pid }
            values.text = (if(s.demo) "DEMO / SZIMULÁLT" else "VALÓDI ECU") + " • folyamatos mérés\n" + lastByPid.joinToString("\n") { e ->
                val age = now-e.atMs
                if(e.sample == null) "%02X: nincs érvényes friss minta • %s".format(e.pid, e.error)
                else "${if(age > 5000) "ELAVULT • " else ""}${e.sample.reading} (${age} ms)"
            } + "\nMotorállapot: " + contextLabel(s.events.lastOrNull()?.metrics)
            recordingInfo.text = "A folyamatos mérés saját CSV-be rögzít. Leállítás után a mentett mérések között találod."
        } else {
            oxygenChart.pause(s.events.lastOrNull()?.atMs ?: now)
            recordingInfo.text = s.message
            finishedDriveId = s.id
        }
    }

    private fun contextLabel(m: PollingTelemetry.Metrics?): String = when(m?.context) {
        "MOTOR_ALL" -> "motor áll"
        "MOTOR_JAR_ZART_KOR" -> "járó motor, zárt szabályozási kör"
        "MOTOR_JAR_NYITOTT_KOR" -> "járó motor, nyitott szabályozási kör"
        "MOTOR_JAR_VISSZACSATOLASI_HIBA" -> "visszacsatolási hiba jelzett"
        else -> "nincs elegendő friss állapotadat"
    }

    private fun reviewClearAttempt() {
        if (!clearState.getBoolean("clearPending", false)) { status.text = "Nincs áttekintendő bizonytalan törlési kísérlet"; return }
        AlertDialog.Builder(this).setTitle("Korábbi bizonytalan törlési kísérlet")
            .setMessage(clearWarning() + "\n\nA tudomásul vétel csak a figyelmeztetést zárja le. Nem igazolja a törlés sikerét; a kísérlet adatai megmaradnak.")
            .setNegativeButton("Mégsem", null).setPositiveButton("Bizonytalan eredményt tudomásul vettem") { _, _ ->
                try { clearJournal.acknowledgeUnknown(); status.text = "A korábbi bizonytalan kísérlet áttekintve; eredménye továbbra sem igazolt" }
                catch(e: Exception) { status.text = "A tudomásul vétel nem menthető: ${e.message}" }
            }.show()
    }

    private fun show(token: Int, block: () -> Unit) { runOnUiThread { if (active && token == generation.get() && !isDestroyed) block() } }
    override fun onStart() {
        super.onStart(); active = true; ui.removeCallbacks(ageTick); ui.post(ageTick)
        if (clearState.getBoolean("clearPending", false)) status.text = clearWarning()
        diagnosticReportInfo.text = "${DiagnosticReport.saved(reportsDir).size} mentett jelentés • korábbi, időbélyegzett vizsgálatok"
        if (recording == null && !recordStartPending) refreshRecordingSummary()
    }
    override fun onStop() { active = false; ui.removeCallbacks(ageTick); resetSession(if(DriveService.state.active) "A folyamatos mérés az értesítésben tovább fut" else "Az alkalmazás háttérbe került; új kapcsolódás szükséges"); super.onStop() }
    override fun onDestroy() { active = false; elm.close(); worker.shutdown(); super.onDestroy() }
}



