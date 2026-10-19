package hu.sx4.diagnosztika

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.view.View
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val elm = Elm327()
    private val worker = Executors.newSingleThreadExecutor()
    private val measuring = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var values: TextView
    private lateinit var errors: TextView
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var demo: Switch
    private var latestCodes: List<String> = emptyList()
    private val descriptions = mapOf("P0420" to "Katalizátor hatásfoka a küszöb alatt (1. hengersor)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 30, 28, 30) }
        scroll.addView(root); setContentView(scroll)
        fun title(s: String) = TextView(this).apply { text = s; textSize = 22f; setTextColor(Color.rgb(20,55,72)); setPadding(0,18,0,12); root.addView(this) }
        fun button(s: String, action: () -> Unit) = Button(this).apply { text = s; setOnClickListener { action() }; root.addView(this) }
        fun field(value: String, hint: String): EditText = EditText(this).apply { setText(value); this.hint = hint; setSingleLine(true); root.addView(this) }
        title("SX4 Diagnosztika Pro")
        TextView(this).apply { text="Suzuki SX4 2007 • M15A • kísérleti 0.1 verzió"; root.addView(this) }
        status = TextView(this).apply { text = "Nincs kapcsolat"; textSize = 17f; setPadding(0,15,0,15); root.addView(this) }
        host = field("192.168.0.10", "Adapter IP-címe")
        port = field("35000", "TCP-port").apply { inputType = 2 }
        demo = Switch(this).apply { text = "DEMO mód – szimulált adatok"; isChecked = true; root.addView(this) }
        button("Csatlakozás az adapterhez") {
            if (demo.isChecked) { status.text = "DEMO mód: nincs valódi ECU-kapcsolat"; return@button }
            val ip = host.text.toString().trim(); val p = port.text.toString().toIntOrNull()
            if (p == null || p !in 1..65535) { status.text="Hibás TCP-port"; return@button }
            status.text="Kapcsolódás…"
            runTask {
                val id = elm.connect(ip,p)
                show { status.text = "Adapter válaszol: $id" }
            }
        }
        button("Kapcsolat bontása") { measuring.set(false); worker.execute { elm.disconnect(); show { status.text="Nincs kapcsolat" } } }
        title("Élő motoradatok")
        values = TextView(this).apply { textSize = 17f; text = "Még nincs mérés"; setPadding(0,10,0,15); root.addView(this) }
        button("Élő adatok indítása") { startReading() }
        button("Mérés leállítása") { measuring.set(false) }
        title("Hibakód-diagnosztika")
        errors = TextView(this).apply { text = "Még nem történt kiolvasás"; textSize=16f; setPadding(0,10,0,15); root.addView(this) }
        button("Tárolt hibakódok olvasása") {
            if (demo.isChecked) { latestCodes=listOf("P0420"); errors.text="DEMO • P0420 – ${descriptions["P0420"]}" }
            else runTask { val c=Obd.dtcs(elm.command("03")); latestCodes=c; show { errors.text=if(c.isEmpty()) "Nem érkezett értelmezhető tárolt hibakód" else c.joinToString("\n") { "$it – ${descriptions[it] ?: "Gyártóspecifikus vagy ismeretlen hibakód"}" } } }
        }
        button("Hibakódok törlése") {
            if (demo.isChecked) { status.text="DEMO módban a törlés tiltva"; return@button }
            if (!elm.isConnected()) { status.text="Előbb csatlakozz az adapterhez"; return@button }
            AlertDialog.Builder(this).setTitle("Hibakódok törlése?")
                .setMessage("A 04 parancs törölheti a tárolt hibakódokat, a Freeze Frame adatokat, és alaphelyzetbe állíthatja a readiness monitorokat. Az alkalmazás a törlés előtti tárolt hibakódválaszt lokálisan menti, de a Freeze Frame mentése ebben a verzióban még nem teljes. Csak álló járműnél végezd el. Folytatod?")
                .setNegativeButton("Mégsem", null)
                .setPositiveButton("Igen, törlés") { _, _ -> runTask { val before = elm.command("03")
                    getSharedPreferences("diagnosztika", MODE_PRIVATE).edit()
                        .putString("torles_elotti_hibakodok", before)
                        .putLong("torles_idopont", System.currentTimeMillis()).apply()
                    val r=elm.command("04"); if (r.contains("44")) { latestCodes=emptyList(); show { errors.text="Törlés visszaigazolva. Olvasd ki ismét a hibakódokat." } } else show { status.text="Nem igazolt a törlés: $r" } } }.show()
        }
        TextView(this).apply { text="Figyelem: az alkalmazás fejlesztői prototípus. Csak a motor szabványos OBD2 funkcióit célozza. A hibakódtörlés nem javítja meg a hibát. Az emisszióellenőrzés átírása nincs beépítve."; setPadding(0,24,0,20); root.addView(this) }
    }
    private fun startReading() {
        if (!measuring.compareAndSet(false,true)) return
        if (demo.isChecked) {
            values.text="DEMO / SZIMULÁLT\nFordulatszám: 780 1/perc\nHűtőfolyadék: 88 °C\nSebesség: 0 km/h\nSTFT: +3,1%\nLTFT: +5,5%\nFojtószelep: 12%"
            measuring.set(false); return
        }
        if (!elm.isConnected()) { measuring.set(false); status.text="Előbb csatlakozz az adapterhez"; return }
        worker.execute {
            try {
                val supported = Obd.pids(elm.command("0100"))
                val items = listOf(0x0C,0x05,0x0D,0x06,0x07,0x11,0x14,0x15)
                while(measuring.get()) {
                    val readings = mutableListOf<String>()
                    for(pid in items.filter{it in supported}) {
                        if(!measuring.get()) break
                        val bytes=Obd.bytesFor(elm.command("01%02X".format(pid)),1,pid)
                        if (bytes.isEmpty()) continue
                        val a=bytes[0]; val b=bytes.getOrElse(1){0}
                        val line = when(pid) {
                            0x0C -> "Fordulatszám: ${(256*a+b)/4} 1/perc"
                            0x05 -> "Hűtőfolyadék: ${a-40} °C"
                            0x0D -> "Sebesség: $a km/h"
                            0x06 -> "STFT B1: ${"%.1f".format((a-128)*100.0/128)} %"
                            0x07 -> "LTFT B1: ${"%.1f".format((a-128)*100.0/128)} %"
                            0x11 -> "Fojtószelep: ${"%.1f".format(a*100.0/255)} %"
                            0x14 -> "O2 B1S1: ${"%.3f".format(a/200.0)} V; korrekció: ${"%.1f".format((b-128)*100.0/128)} %"
                            0x15 -> "O2 B1S2: ${"%.3f".format(a/200.0)} V; korrekció: ${"%.1f".format((b-128)*100.0/128)} %"
                            else -> ""
                        }
                        if (line.isNotEmpty()) readings += line
                    }
                    show { values.text="VALÓDI ECU-ADATOK\n"+(readings.ifEmpty { listOf("A vezérlő nem adott ki támogatott adatot") }.joinToString("\n")) }
                    Thread.sleep(600)
                }
            } catch(e:Exception) { show { status.text="Olvasási hiba: ${e.message}" } }
            finally { measuring.set(false) }
        }
    }
    private fun runTask(block: () -> Unit) = worker.execute { try { block() } catch(e: Exception) { show { status.text="Hiba: ${e.message}" } } }
    private fun show(block: () -> Unit) { runOnUiThread(block) }
    override fun onDestroy() { measuring.set(false); elm.disconnect(); worker.shutdownNow(); super.onDestroy() }
}
