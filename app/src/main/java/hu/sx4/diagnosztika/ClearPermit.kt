package hu.sx4.diagnosztika

import java.util.concurrent.atomic.AtomicBoolean

/** Snapshot bound to one connection and source, with a monotonic two-minute lifetime. */
data class ClearPermit internal constructor(
    val session: Long, val source: String, val codes: Set<String>, val createdAtNanos: Long,
) {
    private val dispatched = AtomicBoolean(false)
    fun validate(currentSession: Long, currentSource: String?, nowNanos: Long, currentCodes: Set<String>? = null) {
        check(!dispatched.get()) { "Ezzel a megerősítéssel már történt törlési kísérlet; új mentés szükséges" }
        check(session == currentSession && source == currentSource) { "A mentés másik kapcsolathoz vagy vezérlőhöz tartozik" }
        check(nowNanos - createdAtNanos in 0..LIFETIME_NANOS) { "A mentés lejárt; új törlés-előkészítés szükséges" }
        check(currentCodes == null || currentCodes == codes) { "A hibakódok megváltoztak; új mentés szükséges. Törlés nem történt." }
    }
    internal fun markDispatched() { check(dispatched.compareAndSet(false, true)) { "A törlési megerősítés már felhasznált" } }
    companion object { const val LIFETIME_NANOS = 120_000_000_000L }
}

class ClearOutcomeUnknown(cause: Throwable? = null) : Exception(
    "A törlés eredménye BIZONYTALAN. Csatlakozz újra, és olvasd ki a hibakódokat; ne ismételd meg automatikusan a törlést.", cause)

class ClearRejected(val code: Int) : Exception("A kiválasztott ECU elutasította a törlést (NRC %02X). Nincs sikeres törlési visszaigazolás; automatikus ismétlés nem történik.".format(code))

