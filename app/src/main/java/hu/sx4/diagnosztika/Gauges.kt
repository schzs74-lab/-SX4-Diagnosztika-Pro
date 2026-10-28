package hu.sx4.diagnosztika

import java.util.Locale

/** One dashboard instrument bound to a standard mode 01 PID; scale limits are display choices, not ECU limits. */
data class GaugeSpec(val pid: Int, val label: String, val unit: String, val min: Double, val max: Double,
    val redFrom: Double? = null, val centered: Boolean = false, val decimals: Int = 0, val majorTicks: Int = 5)

/** What an instrument may show: a value is only current while it is younger than its stale limit. */
sealed class GaugeReading {
    object Unsupported : GaugeReading()
    object NoData : GaugeReading()
    data class Value(val value: Double, val stale: Boolean, val ageMs: Long) : GaugeReading()
}

object Gauges {
    val rpm = GaugeSpec(0x0C, "Fordulatszám", "1/perc", 0.0, 7000.0, redFrom = 6200.0, majorTicks = 7)
    /** Page 1: engine. */
    val engineDials = listOf(
        GaugeSpec(0x0D, "Sebesség", "km/h", 0.0, 200.0, majorTicks = 5),
        GaugeSpec(0x05, "Hűtőfolyadék", "°C", 40.0, 120.0, redFrom = 110.0, majorTicks = 4),
        GaugeSpec(0x06, "Rövid korr. (STFT)", "%", -25.0, 25.0, centered = true, decimals = 1),
        GaugeSpec(0x07, "Hosszú korr. (LTFT)", "%", -25.0, 25.0, centered = true, decimals = 1))
    val engineDigital = listOf(
        GaugeSpec(0x04, "Terhelés", "%", 0.0, 100.0),
        GaugeSpec(0x11, "Fojtószelep", "%", 0.0, 100.0),
        GaugeSpec(0x0F, "Beszívott levegő", "°C", -20.0, 80.0))
    /** Page 2: mixture control. */
    val mixtureDials = listOf(
        GaugeSpec(0x14, "B1S1 első szonda", "V", 0.0, 1.275, decimals = 2, majorTicks = 5),
        GaugeSpec(0x15, "B1S2 hátsó szonda", "V", 0.0, 1.275, decimals = 2, majorTicks = 5),
        GaugeSpec(0x0E, "Előgyújtás", "°", -10.0, 50.0, decimals = 1, majorTicks = 6),
        GaugeSpec(0x10, "Légtömegáram", "g/s", 0.0, 50.0, decimals = 1, majorTicks = 5))
    val mixtureDigital = listOf(
        GaugeSpec(0x42, "Modulfeszültség", "V", 10.0, 16.0, decimals = 1),
        GaugeSpec(0x0B, "Szívócsőnyomás", "kPa", 0.0, 110.0),
        GaugeSpec(0x2F, "Üzemanyagszint", "%", 0.0, 100.0))
    val all = listOf(rpm) + engineDials + engineDigital + mixtureDials + mixtureDigital

    /** Hungarian number format with decimal comma; centred scales show an explicit sign. */
    fun format(spec: GaugeSpec, value: Double): String {
        val text = String.format(Locale.ROOT, "%.${spec.decimals}f", value).replace('.', ',')
        return if (spec.centered && value > 0 && !text.trim('0', ',').isEmpty()) "+$text" else text
    }

    /** Position on the scale, clamped to 0..1 so out-of-range values pin the needle instead of wrapping. */
    fun fraction(spec: GaugeSpec, value: Double): Double =
        ((value - spec.min) / (spec.max - spec.min)).coerceIn(0.0, 1.0)

    fun reading(spec: GaugeSpec, supported: Set<Int>, latest: Map<Int, Pair<Double?, Long>>, now: Long, staleAfterMs: (Int) -> Long): GaugeReading {
        if (spec.pid !in supported) return GaugeReading.Unsupported
        val (value, at) = latest[spec.pid] ?: return GaugeReading.NoData
        if (value == null) return GaugeReading.NoData
        val age = (now - at).coerceAtLeast(0)
        return GaugeReading.Value(value, age > staleAfterMs(spec.pid), age)
    }
}
