package hu.sx4.diagnosztika

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit read-only foreground measurement. Owns its socket, network callback, worker and, when recording,
 * the CSV. Keeps measuring with the screen off and while the app is in the background; a dropped TCP link
 * is re-opened on the same Wi-Fi network a few times before the session ends.
 */
class DriveService : Service() {
    data class State(val id: String = "", val active: Boolean = false, val demo: Boolean = false,
        val source: String = "", val startedAt: Long = 0, val supported: Set<Int> = emptySet(),
        val events: List<DriveRecorder.Event> = emptyList(), val message: String = "Nincs folyamatos mérés",
        val recording: Boolean = true, val sequence: List<Int> = emptyList(), val reconnects: Int = 0) {
        /** Measured average time of one poll from the buffered events; 500 ms until two events exist. */
        fun avgPollMs(): Long = if (events.size < 2) 500L else
            ((events.last().atMs - events.first().atMs) / (events.size - 1)).coerceAtLeast(1L)
    }
    private val elm = Elm327()
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private var poll: ScheduledFuture<*>? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var log: MeasurementLog? = null // worker only
    private var recorder: DriveRecorder? = null // worker only
    private val events = ArrayDeque<DriveRecorder.Event>() // worker only
    private var notifiedAt = 0L
    private var ownId = ""
    private var host = ""
    private var port = 0
    private var network: Network? = null
    private var maxMs = RECORD_LIMIT_MS
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSession("Felhasználói leállítás"); return START_NOT_STICKY }
        if (finishing.get() && intent?.action == START) {
            if (state.id == intent.getStringExtra("id")) state = state.copy(active=false, message="Az előző mérés még lezárul; indítsd újra néhány másodperc múlva")
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action != START || !running.compareAndSet(false, true)) {
            if (!running.get() && !finishing.get()) stopSelf()
            return START_NOT_STICKY
        }
        val demo = intent.getBooleanExtra("demo", false)
        val record = intent.getBooleanExtra("record", true)
        val id = intent.getStringExtra("id") ?: UUID.randomUUID().toString()
        ownId = id
        maxMs = if (record) RECORD_LIMIT_MS else LIVE_LIMIT_MS
        state = State(id, true, demo, if(demo) "DEMO" else intent.getStringExtra("source") ?: "", SystemClock.elapsedRealtime(),
            message = if (record) "Folyamatos mérés indítása…" else "Élő mérés indítása…", recording = record)
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Folyamatos OBD-mérés", NotificationManager.IMPORTANCE_LOW))
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION, notification)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:drive").apply {
                setReferenceCounted(false); acquire(maxMs + 5 * 60_000L)
            }
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sx4-drive").apply { setReferenceCounted(false); acquire() }
            if (demo) worker.execute { beginRecorder(true, intent.getBooleanExtra("oxygen", true), record) }
            else connect(intent.getStringExtra("host") ?: "", intent.getIntExtra("port", 0),
                intent.getStringExtra("source") ?: "", intent.getBooleanExtra("oxygen", true), record)
        } catch (e: Exception) { stopSession("A folyamatos mérés nem indítható: ${e.message}") }
        return START_NOT_STICKY // Never silently reconnect or resume a recording after process death.
    }

    private fun connect(host: String, port: Int, source: String, oxygenOnly: Boolean, record: Boolean) {
        this.host = host; this.port = port
        var selected: Network? = null
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(this) {
                    if (!running.get() || selected != null) return
                    selected = network
                    this@DriveService.network = network
                }
                worker.execute {
                    if (!running.get()) return@execute
                    try {
                        elm.connect(host, port, { network.socketFactory.createSocket() }, { !running.get() })
                        check(running.get()) { "Megszakított indítás" }
                        check(source in elm.ecuSources) { "A korábban kiválasztott ECU nem válaszol; válassz újra az appban" }
                        elm.selectEcu(source)
                        beginRecorder(false, oxygenOnly, record)
                    } catch (e: Exception) { stopSession("Kapcsolati hiba: ${e.message}") }
                }
            }
            override fun onLost(network: Network) { if (network == selected) stopSession("Az adapter Wi-Fi-kapcsolata megszakadt") }
            override fun onUnavailable() { stopSession("Nem érhető el az adapter Wi-Fi-hálózata") }
        }
        callback = networkCallback
        cm.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback, 10_000)
    }

    private fun beginRecorder(demo: Boolean, oxygenOnly: Boolean, record: Boolean) {
        try {
            check(running.get())
            val supported = if (demo) DemoSamples.supported else elm.supported.toSet()
            val source = if (demo) "DEMO" else elm.selectedSource!!
            log = if (!record) null else MeasurementLog(File(filesDir, "measurements"), if(demo) "DEMO" else "REAL", source,
                if(oxygenOnly) "CONTINUOUS_OXYGEN_WITH_CONTEXT" else "CONTINUOUS_ALL_PIDS", BuildConfig.VERSION_NAME,
                System.currentTimeMillis(), SystemClock.elapsedRealtime())
            recorder = DriveRecorder({ cmd ->
                if (demo) "41 ${cmd.takeLast(2)} " + DemoSamples.data(cmd.takeLast(2).toInt(16), SystemClock.elapsedRealtime())
                    .joinToString(" ") { "%02X".format(it) }
                else elm.command(cmd)
            }, supported, oxygenOnly, log, { SystemClock.elapsedRealtime() }, { running.get() })
            publish(state.copy(source=source, supported=supported, sequence=recorder!!.sequence,
                message=if (record) "Rögzítés folyamatban • képernyő nélkül is" else "Élő mérés folyamatban • képernyő nélkül is"))
            schedule(demo)
        } catch(e: Exception) { stopSession("Rögzítési hiba: ${e.message}") }
    }

    private fun schedule(demo: Boolean) {
        if (!running.get()) return
        poll = worker.schedule(loop@{
            if (!running.get()) return@loop
            if (SystemClock.elapsedRealtime() - state.startedAt >= maxMs) { stopSession("Elérte a ${maxMs / 60_000} perces korlátot"); return@loop }
            try {
                val event = recorder!!.step()
                if (!running.get()) return@loop
                events.addLast(event)
                while (events.size > 1024 || events.firstOrNull()?.let { event.atMs-it.atMs > 60_000 } == true) events.removeFirst()
                publish(state.copy(events=events.toList()))
                if (!demo && !elm.isConnected() && !reconnect()) { stopSession("A TCP-kapcsolat megszakadt, az újracsatlakozás sikertelen"); return@loop }
                if (recorder!!.limitReached) { stopSession("Elérte a napló idő- vagy méretkorlátját"); return@loop }
                schedule(demo)
            } catch(e: Exception) { stopSession("A mérés megszakadt: ${e.message}") }
        }, POLL_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * A single late reply closes the socket on purpose (a late prompt would shift every later answer), so the
     * session re-opens it on the same Wi-Fi network instead of ending. Worker thread only; read-only commands.
     */
    private fun reconnect(): Boolean {
        val network = network ?: return false
        val source = state.source
        for (attempt in 1..RECONNECT_ATTEMPTS) {
            if (!running.get()) return false
            publish(state.copy(message = "A kapcsolat megszakadt • újracsatlakozás ($attempt/$RECONNECT_ATTEMPTS)…"))
            try { Thread.sleep(1000L * attempt) } catch (_: InterruptedException) { return false }
            if (!running.get()) return false
            try {
                elm.connect(host, port, { network.socketFactory.createSocket() }, { !running.get() })
                check(source in elm.ecuSources) { "A kiválasztott ECU nem válaszol" }
                elm.selectEcu(source)
                val count = state.reconnects + 1
                publish(state.copy(reconnects = count, message = (if (state.recording) "Rögzítés" else "Élő mérés") +
                    " folyamatban • újracsatlakozva ($count. alkalom)"))
                return true
            } catch (e: Exception) {
                if (!running.get()) return false
            }
        }
        return false
    }

    private fun publish(value: State) {
        if (state.id != ownId) return
        state = value
        main.post {
            val now = SystemClock.elapsedRealtime()
            if (running.get() && now - notifiedAt > 2000) {
                notifiedAt = now
                if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
            }
        }
    }

    private fun stopSession(reason: String) {
        if (!finishing.compareAndSet(false, true)) return
        running.set(false); poll?.cancel(false); elm.disconnect()
        if (state.id == ownId) state = state.copy(message="Mérés lezárása… $reason")
        worker.execute {
            val message = finishLog(reason)
            main.post {
                if (state.id == ownId) state = state.copy(active=false, message=message)
                releaseResources()
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
    }

    private fun finishLog(reason: String): String {
        val target = log; log = null
        return try {
            val file = target?.finish(reason, SystemClock.elapsedRealtime())
            reason + (file?.let { "\nMentve: ${it.name} • ${target?.rows ?: 0} minta" }
                ?: if (state.recording) "\nNem indult rögzítés" else "\nÉlő mérés, nem készült CSV")
        } catch(e: Exception) { target?.abort(); "A mérés leállt, a mentés SIKERTELEN: ${e.message}" }
    }

    private fun releaseResources() {
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }; callback = null
        wake?.let { if(it.isHeld) it.release() }; wake = null
        wifiLock?.let { if(it.isHeld) it.release() }; wifiLock = null
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DriveService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle((if(state.demo) "SX4 • DEMO / SZIMULÁLT " else "SX4 • valódi OBD-") + if (state.recording) "rögzítés" else "élő mérés")
            .setContentText(state.message.lineSequence().first()).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null, if (state.recording) "Mérés leállítása és mentése" else "Mérés leállítása", stop).build()).build()
    }

    override fun onDestroy() {
        running.set(false); poll?.cancel(false); elm.close()
        if (state.id == ownId && state.active) {
            state = state.copy(active=false, message="A mérési szolgáltatás leállt")
            worker.execute { val message=finishLog("A szolgáltatás leállt"); if(state.id == ownId) state=state.copy(message=message) }
        }
        releaseResources(); worker.shutdown(); super.onDestroy()
    }

    companion object {
        const val START = "hu.sx4.diagnosztika.DRIVE_START"
        const val STOP = "hu.sx4.diagnosztika.DRIVE_STOP"
        private const val CHANNEL = "sx4-continuous"
        private const val NOTIFICATION = 74
        /** Pause between two requests; the request itself (50–300 ms on Wi-Fi) dominates the refresh rate. */
        const val POLL_DELAY_MS = 25L
        private const val RECONNECT_ATTEMPTS = 3
        private const val RECORD_LIMIT_MS = 3_600_000L
        private const val LIVE_LIMIT_MS = 4 * 3_600_000L
        @Volatile var state = State()
            private set
        fun launch(context: Context, demo: Boolean, host: String, port: Int, source: String, oxygen: Boolean, record: Boolean = true) {
            check(!state.active) { "Már fut folyamatos mérés" }
            val id = UUID.randomUUID().toString()
            state = State(id=id, active=true, demo=demo, source=source, message="Mérési szolgáltatás indítása…", recording=record)
            try {
                context.startForegroundService(Intent(context, DriveService::class.java).setAction(START)
                    .putExtra("id", id).putExtra("demo", demo).putExtra("host", host).putExtra("port", port)
                    .putExtra("source", source).putExtra("oxygen", oxygen).putExtra("record", record))
            } catch(e: Exception) { state=state.copy(active=false, message="Indítási hiba: ${e.message}"); throw e }
        }
    }
}
