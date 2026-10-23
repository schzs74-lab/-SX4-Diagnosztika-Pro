package hu.sx4.diagnosztika

/** Standard emission DTC services for one already selected responder. Never sends a write. */
class DtcScan(
    private val send: (String) -> String,
    private val canProtocol: Boolean,
    private val connected: () -> Boolean = { true },
) {
    enum class State { OK, UNAVAILABLE }
    data class Result(val mode: Int, val state: State, val codes: List<String>, val detail: String) {
        val title: String get() = when (mode) {
            3 -> "Tárolt hibakódok (03)"
            7 -> "Függő hibakódok (07)"
            else -> "Állandó hibakódok (0A)"
        }
        fun lines(): List<String> = when (state) {
            State.OK -> if (codes.isEmpty()) listOf("Nincs hibakód a sikeresen dekódolt válaszban")
                else codes.map { "$it – ${DtcMeaning.describe(it)}" }
            State.UNAVAILABLE -> listOf("NEM ELLENŐRIZHETŐ: $detail", "Ez nem igazolja a hibakódok hiányát vagy a funkció támogatását.")
        }
    }
    fun read(mode: Int): Result {
        require(mode in listOf(3, 7, 10))
        check(connected()) { "A hibakódolvasás megszakadt" }
        return try {
            val raw = send("%02X".format(mode))
            check(connected()) { "A hibakódolvasás megszakadt" }
            Result(mode, State.OK, Obd.dtcs(raw, canProtocol, mode), "")
        } catch (e: Exception) {
            if (!connected()) throw e
            Result(mode, State.UNAVAILABLE, emptyList(), e.message ?: "Nincs értelmezhető válasz")
        }
    }
    fun readAll(): List<Result> = listOf(3, 7, 10).map { read(it) }
    companion object {
        const val NOTE = "Az állandó (0A) hibakódokat a vezérlő saját ellenőrzése törli; a hibakódtörlés nem távolítja el őket. A kódleírás nem bizonyítja egy alkatrész meghibásodását."
        fun display(results: List<Result>): String = results.joinToString("\n\n") { it.title + "\n" + it.lines().joinToString("\n") } + "\n\n" + NOTE
    }
}

/** Small, deliberately explicit generic glossary. Unknown/manufacturer codes are never guessed. */
object DtcMeaning {
    private val generic = mapOf(
        "P0100" to "Légtömegmérő áramköri hiba",
        "P0115" to "Hűtőfolyadék-hőmérséklet érzékelő áramköri hiba",
        "P0130" to "Lambdaszonda áramköri hiba (B1S1)",
        "P0133" to "Lambdaszonda lassú válasz (B1S1)",
        "P0135" to "Lambdaszonda fűtőáramköri hiba (B1S1)",
        "P0136" to "Lambdaszonda áramköri hiba (B1S2)",
        "P0141" to "Lambdaszonda fűtőáramköri hiba (B1S2)",
        "P0171" to "Túl szegény keverék (1. hengersor)",
        "P0172" to "Túl dús keverék (1. hengersor)",
        "P0300" to "Véletlenszerű vagy többhengert érintő gyújtáskimaradás",
        "P0301" to "Gyújtáskimaradás az 1. hengerben",
        "P0302" to "Gyújtáskimaradás a 2. hengerben",
        "P0303" to "Gyújtáskimaradás a 3. hengerben",
        "P0304" to "Gyújtáskimaradás a 4. hengerben",
        "P0420" to "Katalizátor hatásfoka a küszöb alatt (1. hengersor)",
    )
    fun describe(code: String): String = generic[code] ?: "Nincs ellenőrzött magyar leírás; gyári dokumentáció alapján azonosítandó"
}
